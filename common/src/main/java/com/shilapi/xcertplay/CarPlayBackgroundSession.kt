package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.platform.HeadUnitProfile
import java.io.File
import java.lang.ref.WeakReference

/** Foreground-service runtime. The Activity owns only a weakly subscribed presentation binding.
 * All state transitions run on main; Controller and media completion arrive from worker threads.
 */
internal object CarPlayBackgroundSession {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var active = false
    @Volatile var progress: String? = null
    private var owner: Any? = null
    private var binding = WeakReference<Binding>(null)
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private val stopWaiters = mutableListOf<() -> Unit>()
    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var display: CarPlaySessionDisplay? = null
    private var currentPlan: CarPlaySessionPlan? = null
    private var pendingPlan: CarPlaySessionPlan? = null
    private var replacementAllowed = true
    private var closing = false
    private var closeOperation: Any? = null
    private var closeCompletion: (() -> Unit)? = null
    private var releaseFailure: Throwable? = null
    private var generation = 0L
    private var serviceEpoch = 0L
    private var phone: AirPlaySession? = null
    private var status: CarPlayStatus? = null
    private val retry = ConnectionRetryScheduler(main)
    private val retryBudget = WirelessStartupRetryBudget()
    private var retryAttempt = 0
    var retryStopped = false
        private set
    private var retryPaused = false
    private var deferredRetry: Pair<String, WirelessStartupFailure?>? = null
    private var logs: Logs? = null
    private val snapshotPending = java.util.concurrent.atomic.AtomicBoolean()
    private val snapshotExecutor = java.util.concurrent.ThreadPoolExecutor(0, 1, 5,
        java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue<Runnable>(),
        { task -> Thread(task, "carplay-environment").apply { isDaemon = true } })

    data class Logs(val session: SessionLogFile, val connection: SessionLogFile) {
        fun append(message: String) {
            AsyncDiagnosticLog.append(session, message)
            AsyncDiagnosticLog.append(connection, message)
        }
    }
    data class Snapshot(val controller: CarPlayController, val sink: AndroidMediaSink,
        val width: Int, val height: Int, val display: CarPlaySessionDisplay)
    class Binding(val listener: AirPlaySessionListener, val status: (CarPlayStatus) -> Unit,
        val available: () -> Unit, val stopped: () -> Unit, val closing: () -> Unit = {},
        val beforeRestart: () -> Unit = {})

    fun obtainLogs(context: Context): Logs {
        logs?.let { return it }
        val app = context.applicationContext
        val transport = if (AirPlayPersistence.loadWirelessEnabled(app)) "wireless" else "usb"
        return Logs(SessionLogFile(File(app.filesDir, "logs/diplay.log")),
            SessionLogFile(File(app.filesDir, "logs/connection-$transport/diplay.log"))).also {
            it.session.reset("DiPlay runtime started ${java.time.Instant.now()}")
            it.connection.reset("Connection capture transport=$transport started=${java.time.Instant.now()}")
            val target = it.connection
            if (snapshotPending.compareAndSet(false, true)) {
                snapshotExecutor.execute {
                    try { ConnectionEnvironmentSnapshot.capture(app).lineSequence().forEach(target::append) }
                    finally { snapshotPending.set(false) }
                }
            } else target.append("Environment capture already pending; export includes a fresh environment check")
            logs = it
        }
    }

    fun bind(candidate: Any, next: Binding) {
        owner = WeakReference(candidate)
        binding = WeakReference(next)
        phone?.let(next.listener::onSessionActive)
        status?.let(next.status)
        if (closing) next.closing()
    }
    fun unbind(candidate: Any) {
        if (!isOwner(candidate)) return
        owner = null
        binding.clear()
        sink?.setScreenStreamActiveChangedListener(null)
        // A vanished window cannot hold a resource transition hostage. Reuse the last stable
        // inputs; a later window can submit its own settled size before the next replacement.
        replacementAllowed = true
        installPendingIfReady()
    }
    fun isOwner(candidate: Any): Boolean = (if (owner is WeakReference<*>) (owner as WeakReference<*>).get() else owner) === candidate
    fun hasSession(): Boolean = stopAction != null || stopping || closing || pendingPlan != null
    fun isClosing(): Boolean = closing
    fun snapshot(): Snapshot? = controller?.let { c -> sink?.let { s -> display?.let { Snapshot(c, s, width, height, it) } } }

    fun start(plan: CarPlaySessionPlan) {
        check(!hasSession() && releaseFailure == null) { "Previous CarPlay resources require an app restart" }
        retryBudget.manualRetry()
        retryStopped = false
        val epoch = ++serviceEpoch
        plan.context.startForegroundService(Intent(plan.context, DiPlaySessionService::class.java)
            .putExtra(DiPlaySessionService.EXTRA_RUNTIME_EPOCH, epoch))
        install(plan)
    }
    fun ownsService(epoch: Long): Boolean = epoch == serviceEpoch
    fun stopServiceOwner(epoch: Long, completion: () -> Unit = {}) {
        if (epoch != serviceEpoch) return
        stop(completion)
    }

    fun restart(plan: CarPlaySessionPlan? = currentPlan, manual: Boolean = false) {
        if (plan == null || stopping || releaseFailure != null) return
        if (manual) { retryBudget.manualRetry(); retryStopped = false }
        retry.cancel()
        retryBudget.disconnected()
        pendingPlan = plan
        replacementAllowed = true
        beginClose()
    }
    fun hasPendingReplacement(): Boolean = pendingPlan != null
    fun updatePendingReplacement(plan: CarPlaySessionPlan?, allowed: Boolean) {
        if (pendingPlan == null) return
        if (plan != null) pendingPlan = plan
        replacementAllowed = allowed
        installPendingIfReady()
    }
    private fun installPendingIfReady() {
        if (closing || stopping || !replacementAllowed || releaseFailure != null) return
        val plan = pendingPlan ?: return
        pendingPlan = null
        install(plan)
    }

    fun pauseRetry(paused: Boolean) {
        retryPaused = paused
        if (paused && retry.isScheduled) { retry.cancel(); deferredRetry = "Recovery after settings" to null }
        if (!paused) deferredRetry?.let { deferredRetry = null; reconnect(it.first, it.second) }
    }
    fun reconnect(reason: String, failure: WirelessStartupFailure? = null) {
        if (closing || stopping || retryStopped || retry.isScheduled || currentPlan == null) return
        if (retryPaused) { deferredRetry = reason to failure; return }
        val delay = if (failure != null) {
            if (failure == WirelessStartupFailure.HOTSPOT_CONFIGURATION) null else retryBudget.nextDelayMillis()
        } else (2_000L * (1L shl retryAttempt++.coerceAtMost(4))).coerceAtMost(30_000L)
        if (delay == null) { retryStopped = true; logs?.append("Runtime startup retry stopped reason=$failure"); return }
        val expected = generation
        logs?.append("Runtime reconnect scheduled delayMs=$delay reason=$reason generation=$expected")
        retry.schedule(delay) { if (generation == expected && !retryPaused) restart() }
    }

    private fun install(plan: CarPlaySessionPlan) {
        val epoch = ++generation
        currentPlan = plan
        status = null
        val log = obtainLogs(plan.context)
        val listener = object : AirPlaySessionListener {
            private fun deliver(action: (Binding?) -> Unit) = main.post {
                if (epoch == generation && !closing && !stopping) action(binding.get())
            }
            override fun onDebugLog(message: String) {
                log.append(message)
                deliver { it?.listener?.onDebugLog(message) }
            }
            override fun onSessionActive(session: AirPlaySession) { deliver {
                phone = session; active = true; retryAttempt = 0; retry.cancel()
                it?.listener?.onSessionActive(session)
            } }
            override fun onSessionEnded(session: AirPlaySession) { deliver {
                if (phone != null && phone !== session) return@deliver
                phone = null; active = false; retryBudget.disconnected()
                it?.listener?.onSessionEnded(session)
                reconnect("AirPlay session ended")
            } }
            override fun onTransportError(message: String) { deliver {
                active = false
                it?.listener?.onTransportError(message)
                reconnect(message)
            } }
            override fun onVideoFrameRendered(session: AirPlaySession) { deliver {
                it?.listener?.onVideoFrameRendered(session)
                if (phone === session && retryBudget.firstFrame(session, SystemClock.elapsedRealtime())) {
                    main.postDelayed({ if (epoch == generation && phone === session)
                        retryBudget.resetIfStable(session, SystemClock.elapsedRealtime()) }, WirelessStartupPolicy.STABLE_SESSION_MILLIS)
                }
            } }
        }
        try {
            val next = plan.create(listener, { nextStatus -> main.post {
                if (epoch == generation && !closing && !stopping) {
                    status = nextStatus
                    binding.get()?.status?.invoke(nextStatus)
                    if (nextStatus is CarPlayStatus.Failed && !nextStatus.wifiResetRequired)
                        reconnect(nextStatus.message, nextStatus.startupFailure)
                }
            } }, log::append)
            controller = next.controller; sink = next.sink
            width = next.width; height = next.height; display = next.display
            stopAction = { completion -> stopResources(completion) }
            CarPlayMediaKeys.attach(plan.context, next.controller,
                resumeFocus = if (HeadUnitProfile.read(plan.context).coordinatedAudioFocus) next.sink::resumeAudioFocusForPlayback else null)
            if (plan.airPlay.videoInCar) CarPlayVideo.attach(plan.context, next.controller)
            binding.get()?.available?.invoke()
            next.controller.start()
        } catch (error: RuntimeException) {
            log.append("Runtime start failed: ${error.javaClass.simpleName}")
            status = CarPlayStatus.Failed(error.message ?: "CarPlay could not start")
            binding.get()?.status?.invoke(status!!)
            pendingPlan = null
            beginClose()
        }
    }

    private fun beginClose() {
        if (closing) return
        closing = true
        val operation = Any()
        closeOperation = operation
        generation++
        retry.cancel()
        val old = snapshot()
        controller = null; sink = null; display = null
        active = false; phone = null
        old?.sink?.setScreenStreamActiveChangedListener(null)
        old?.controller?.let(CarPlayMediaKeys::detach)
        binding.get()?.closing?.invoke()
        val finish = { main.post {
            if (closeOperation !== operation) return@post
            closeOperation = null
            closing = false
            if (pendingPlan != null && !stopping) {
                // Let a still-bound window publish its final layout/permission snapshot.
                // With no window, the runtime can continue using its immutable plan.
                binding.get()?.beforeRestart?.invoke()
                installPendingIfReady()
            } else {
                stopAction = null
                currentPlan?.context?.stopService(Intent(currentPlan!!.context, DiPlaySessionService::class.java))
                currentPlan = null
                logs = null
                progress = null
                binding.get()?.stopped?.invoke()
                val completed = closeCompletion; closeCompletion = null
                completed?.invoke()
            }
        }; Unit }
        if (old == null) finish() else {
            old.controller.close()
            old.controller.whenClosed {
                old.sink.close()
                old.sink.whenTerminated { failure ->
                    if (failure == null) finish() else main.post {
                        if (closeOperation !== operation) return@post
                        closeOperation = null
                        // A failed release is not permission to overlap native resources.
                        pendingPlan = null
                        releaseFailure = failure
                        retryStopped = true
                        progress = "Media release failed; restart the app before reconnecting"
                        logs?.append("Runtime media release failed: ${failure.javaClass.simpleName}")
                        status = CarPlayStatus.Failed(progress!!)
                        binding.get()?.status?.invoke(status!!)
                        // Termination failed; quarantine this process's outputs, but settle the
                        // stop command so an explicit app exit is still possible.
                        closing = false; stopAction = null
                        val app = currentPlan?.context; currentPlan = null
                        app?.stopService(Intent(app, DiPlaySessionService::class.java))
                        val completed = closeCompletion; closeCompletion = null
                        completed?.invoke()
                    }
                }
            }
            main.postDelayed({ if (closing) logs?.append("Runtime teardown pending: waiting for owned transport/media resources") }, 4_000)
        }
    }

    fun stop(completion: () -> Unit = {}) {
        if (stopping) { stopWaiters.add(completion); return }
        val action = stopAction
        if (action == null && !closing) { completion(); return }
        stopping = true
        stopWaiters.add(completion)
        if (action != null) action(::completeStop) else stopResources(::completeStop)
    }
    private fun completeStop() {
        val callbacks = stopWaiters.toList()
        stopWaiters.clear()
        stopping = false
        callbacks.forEach { it() }
    }
    private fun stopResources(completion: () -> Unit) {
        pendingPlan = null; retry.cancel(); deferredRetry = null
        closeCompletion = completion
        beginClose()
    }

    // Resource adoption seam also used by non-Activity integration tests.
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int,
        owner: Any, display: CarPlaySessionDisplay, stop: (((() -> Unit)) -> Unit)? = null) {
        this.controller = controller; this.sink = sink; this.width = width; this.height = height
        this.owner = WeakReference(owner); this.display = display; stopAction = stop ?: ::stopResources
    }
    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        controller = null; sink = null; display = null; currentPlan = null; pendingPlan = null
        replacementAllowed = true
        if (!keepOwner) { owner = null; binding.clear(); stopAction = null }
        active = false; progress = null; phone = null; status = null
        retry.cancel(); retryPaused = false; deferredRetry = null
        retryBudget.manualRetry(); retryAttempt = 0; retryStopped = false
        logs = null; closeOperation = null; releaseFailure = null; closeCompletion = null; stopping = false; closing = false; stopWaiters.clear(); generation++
    }
}
