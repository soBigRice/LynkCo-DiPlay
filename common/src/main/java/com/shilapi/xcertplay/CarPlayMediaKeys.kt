package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.graphics.drawable.toBitmap
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.Executor

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * Android delivers media keys to a media session; BYD picks the session of the audio-focus
 * owner. Once CarPlay plays music, DiPlay holds audio focus and an active session until the
 * CarPlay session ends, so play also works after a pause. Keys go to the iPhone as CarPlay media
 * HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = Bitmap::recycle,
    )
    private var artworkOwner: Any? = null
    private var controller: CarPlayController? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var resumeFocus: (() -> Unit)? = null
    private var appContext: Context? = null
    private var mediaAudioActive = false
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private var placeholder: Bitmap? = null
    private var notificationObserver: Pair<Any, () -> Unit>? = null
    private var lastPublishedPlaying: Boolean? = null
    private var keySamples = 0
    private var commands = 0
    private var activeCallback: CarPlayMediaCallback? = null

    data class NotificationState(val token: MediaSession.Token, val playing: Boolean,
        val title: String?, val artist: String?)

    @Synchronized fun notificationState(): NotificationState? = session?.takeIf { it.isActive }?.let {
        NotificationState(it.sessionToken, isPlayingLocked(), nowPlaying.title, nowPlaying.artist)
    }

    @Synchronized fun observeNotification(owner: Any, changed: () -> Unit) {
        notificationObserver = owner to changed
    }

    @Synchronized fun removeNotificationObserver(owner: Any) {
        if (notificationObserver?.first === owner) notificationObserver = null
    }

    private fun notifyChangedLocked() { notificationObserver?.second?.let { runCatching(it) } }

    @Synchronized fun diagnosticState(): String =
        "attached=${controller != null} sessionPresent=${session != null} active=${session?.isActive} " +
            "mediaAudioActive=$mediaAudioActive playing=${isPlayingLocked()} actions=$ACTIONS " +
            "titlePresent=${nowPlaying.title != null} artistPresent=${nowPlaying.artist != null} " +
            "artworkPresent=${artwork != null} rendererOwnsFocus=${resumeFocus != null} commands=$commands " +
            "otherSessions=not_requested"

    /** Only the visible CarPlay activity uses this path; background selection stays with Android. */
    @Synchronized fun dispatchForegroundKey(event: KeyEvent): Boolean {
        if (controller == null || session?.isActive != true ||
            CarPlayMediaButton.forKeyCode(event.keyCode, bydHardwareToggle = false) == null) return false
        return activeCallback?.onForegroundKey(event) == true
    }

    @Synchronized fun observeForegroundKey(event: KeyEvent) {
        observeKey(event, "activity")
    }

    private fun observeKey(event: KeyEvent, path: String) {
        if (!HeadUnitMediaDiagnostics.isControlKey(event.keyCode) || keySamples >= 48) return
        keySamples++
        controller?.recordMediaControlDiagnostic("input path=$path code=${event.keyCode} scan=${event.scanCode} " +
            "action=${event.action} repeat=${event.repeatCount} device=${event.deviceId} source=${event.source}")
    }

    @Synchronized fun dispatchNotification(index: Int): Boolean {
        if (session?.isActive != true || index !in listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE,
                CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS)) return false
        send(index, "notification")
        return true
    }

    @Synchronized
    fun attach(context: Context, next: CarPlayController, resumeFocus: (() -> Unit)? = null) {
        if (controller !== next) {
            releaseLocked()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        this.resumeFocus = resumeFocus
        controller = next
        next.recordAudioFocusDiagnostic("owner=${if (resumeFocus != null) "renderer" else "media-keys"}")
        next.playbackListener = { playing -> onIphonePlaying(next, playing) }
        next.nowPlayingListener = { update -> onNowPlayingChanged(next, update) }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        controller = null
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        if (playing) mainHandler.post {
            synchronized(this) {
                if (controller === expected) regainFocusLocked()
            }
        }
    }

    /** Publishes the iPhone's retained metadata through Android's system media session. */
    private fun onNowPlayingChanged(expected: CarPlayController, update: CarPlayNowPlaying) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                val previousArtwork = artwork
                if (nowPlaying.artworkTransferId != update.artworkTransferId) {
                    artwork = nextArtwork(update.artworkTransferId, artworkCache, artwork)
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                val metadataChanged = metadataChanged(nowPlaying, update) || artwork !== previousArtwork
                nowPlaying = update
                // A paused phone can publish metadata before opening an audio stream. Expose
                // its controls without requesting focus or pretending audio is playing.
                if (session == null && resumeFocus != null &&
                    (update.title != null || update.elapsedMillis != null)) appContext?.let(::start)
                // The iPhone repeats NowPlayingUpdate about twice a second for the position alone.
                // Republishing the metadata each time sent a copy of the artwork through system_server
                // to every media listener, and on a DiLink 5.0 Tang that exhausted memory within
                // minutes. The position goes in the playback state.
                if (metadataChanged) session?.setMetadata(androidMetadata(update, shownArtworkLocked()))
                publishPlaybackStateLocked()
                if (metadataChanged) notifyChangedLocked()
            }
        }
    }

    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, decoded: Bitmap?) {
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            session?.setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
        }
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() {
        resumeFocus?.let { it(); return }
        val request = focusRequest ?: return
        if (focusHeld) return
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        focusHeld = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "audio focus regained=$focusHeld")
        controller?.recordAudioFocusDiagnostic("media-keys regained=$focusHeld")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        mediaAudioActive = active
        if (active && session == null) start(context) else if (active) regainFocusLocked()
        publishPlaybackStateLocked()
    }

    private fun start(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        val request = if (resumeFocus != null) null else AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change ->
                Log.i(TAG, "audio focus change=$change")
                controller?.recordAudioFocusDiagnostic("media-keys change=$change")
                // Only a permanent loss moves the car's media keys elsewhere; transient losses come back.
                if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(this) { focusHeld = false }
            }, mainHandler)
            .build()
        val granted = request != null && audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = request
        focusHeld = granted
        val expected = controller
        val callback = CarPlayMediaCallback(
            bydHardwareToggle = { appContext?.let(com.shilapi.xcertplay.hud.BydOutputSettings::integrationAllowed) == true },
            observe = { event -> synchronized(this) { if (controller === expected) observeKey(event, "media_session") } },
            send = { index, source -> synchronized(this) { if (controller === expected) send(index, source) } },
        )
        activeCallback = callback
        session = MediaSession(context, "DiPlay CarPlay").apply {
            setCallback(callback, mainHandler)
            setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setSessionActivity(PendingIntent.getActivity(context, 2, Intent(context, CarPlayHostActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
            isActive = true
        }
        Log.i(TAG, "media keys active focusGranted=$granted")
        controller?.recordAudioFocusDiagnostic("media-session active independentRequest=${request != null} granted=$granted")
        controller?.recordMediaControlDiagnostic("session-created ${diagnosticState()}")
    }

    private fun releaseLocked() {
        artworkOwner = null
        artworkQueue.clear()
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        activeCallback = null
        lastPublishedPlaying = null
        keySamples = 0
        commands = 0
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        focusRequest?.let { request -> appContext?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
        focusRequest = null
        focusHeld = false
        resumeFocus = null
        notifyChangedLocked()
    }

    private fun isPlayingLocked(): Boolean = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaAudioActive
        }

    private fun publishPlaybackStateLocked() {
        val playing = isPlayingLocked()
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    nowPlaying.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                    // The iPhone sends elapsed time only on play, pause or seek, so Android must
                    // extrapolate from when it arrived, not from this republish.
                    elapsedUpdatedAt,
                )
                .build(),
        )
        if (session != null && lastPublishedPlaying != playing) {
            lastPublishedPlaying = playing
            controller?.recordMediaControlDiagnostic("state-published ${diagnosticState()}")
            notifyChangedLocked()
        }
    }

    private fun send(index: Int, source: String) {
        synchronized(this) {
            commands++
            controller?.recordMediaControlDiagnostic("command count=$commands source=$source index=$index")
        }
        // While the car's video player is on screen the wheel drives it: a CarPlay play/pause would
        // make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            return
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index, source) ?: false
        Log.i(TAG, "media key $source -> CarPlay $index sent=$sent")
    }

    /** Whether [next] changes what the media session's metadata shows; position and play state do not. */
    internal fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)

    internal fun androidMetadata(info: CarPlayNowPlaying, artwork: Bitmap? = null): MediaMetadata =
        MediaMetadata.Builder().apply {
            info.title?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            info.artist?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            info.sourceApp?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }.build()

    // Without art the car draws DiPlay's bright launcher icon instead.
    private fun shownArtworkLocked(): Bitmap? =
        artwork ?: placeholder ?: appContext?.let(::placeholderArt)?.also { placeholder = it }

    internal fun placeholderArt(context: Context): Bitmap? = context
        .getDrawable(R.drawable.art_now_playing_placeholder)
        ?.toBitmap(MAX_ARTWORK_DIMENSION, MAX_ARTWORK_DIMENSION)

    /**
     * The art to show once the iPhone names transfer [id]. A pending transfer keeps [current], so the
     * placeholder does not flash between tracks.
     */
    internal fun nextArtwork(id: Int?, cache: Map<Int, Bitmap?>, current: Bitmap?): Bitmap? = when {
        id == null -> null
        cache.containsKey(id) -> cache[id]
        else -> current
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= MAX_ARTWORK_DIMENSION) return decoded
        val scale = MAX_ARTWORK_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { scaled -> if (scaled !== decoded) decoded.recycle() }
    }

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}

/**
 * Media-session input → CarPlay presses. Only BYD hardware keys need the firmware toggle workaround;
 * media controllers (not hardware keys) call [onPlay] and [onPause] with an explicit intent.
 */
internal class CarPlayMediaCallback(
    private val bydHardwareToggle: () -> Boolean = { true },
    private val observe: (KeyEvent) -> Unit = {},
    private val send: (index: Int, source: String) -> Unit,
) : MediaSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        runCatching { observe(event) }
        if (CarPlayMediaButton.forKeyCode(event.keyCode, bydHardwareToggle()) == null)
            return super.onMediaButtonEvent(mediaButtonIntent)
        return dispatchKey(event, "")
    }

    fun onForegroundKey(event: KeyEvent): Boolean = dispatchKey(event, "activity_")

    private fun dispatchKey(event: KeyEvent, sourcePrefix: String): Boolean {
        val index = CarPlayMediaButton.forKeyCode(event.keyCode, bydHardwareToggle()) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, sourcePrefix + KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    override fun onPlay() = send(CarPlayMediaButton.PLAY, "play")
    override fun onPause() = send(CarPlayMediaButton.PAUSE, "pause")
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, "previous")
}
