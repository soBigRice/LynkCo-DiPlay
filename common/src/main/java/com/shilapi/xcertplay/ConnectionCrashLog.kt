package com.shilapi.xcertplay

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/** Retains the last Java/Kotlin fatal stack across process restarts, without exception messages. */
internal object ConnectionCrashLog {
    private var installed = false
    private val fileLock = Any()

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler(handler(file(context), previous))
        installed = true
    }

    internal fun handler(
        target: File,
        previous: Thread.UncaughtExceptionHandler,
    ): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
        try {
            // An async queue may die with this process. Finish the bounded private write
            // before delegating to Android's original crash/termination handler.
            record(target, thread, error)
        } catch (_: Throwable) {
            Log.e("DiPlay-CrashLog", "Unable to persist fatal stack metadata")
        } finally {
            previous.uncaughtException(thread, error)
        }
    }

    @Suppress("DEPRECATION") // Thread.getId() is available on API 28; threadId() is not.
    private fun record(target: File, thread: Thread, error: Throwable) {
        val text = buildString {
            appendLine("Fatal stack at=${java.time.Instant.now()} pid=${Process.myPid()} threadId=${thread.id}")
            appendLine("Exception messages omitted; Java/Kotlin fatal errors only, not native crashes or system kills.")
            var cause: Throwable? = error
            repeat(4) { index ->
                val current = cause ?: return@repeat
                DiagnosticRedactor.redact("cause[$index]=${current.javaClass.name}")?.let { appendLine(it.take(250)) }
                current.stackTrace.take(12).forEach { frame ->
                    DiagnosticRedactor.redact("  at $frame")?.let { appendLine(it.take(250)) }
                }
                cause = current.cause?.takeUnless { it === current }
            }
        }
        // AtomicFile protects replacement, not concurrent access or backup restoration.
        synchronized(fileLock) {
            target.parentFile?.mkdirs()
            val atomic = AtomicFile(target)
            val stream = atomic.startWrite()
            try {
                stream.write(text.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }
    }

    fun report(context: Context): String = synchronized(fileLock) {
        try {
            AtomicFile(file(context)).openRead().bufferedReader().use { reader ->
                val buffer = CharArray(16 * 1024)
                var count = 0
                while (count < buffer.size) {
                    val read = reader.read(buffer, count, buffer.size - count)
                    if (read <= 0) break
                    count += read
                }
                if (count == 0) "No retained fatal stack." else String(buffer, 0, count)
            }
        } catch (_: FileNotFoundException) {
            "No retained fatal stack; this does not rule out a native crash or system kill."
        } catch (error: Exception) {
            "Fatal stack unavailable error=${error.javaClass.simpleName}"
        }
    }

    private fun file(context: Context) = File(context.filesDir, "logs/last-crash.txt")
}
