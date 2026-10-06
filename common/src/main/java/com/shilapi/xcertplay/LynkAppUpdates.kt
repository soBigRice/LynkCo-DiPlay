// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CancellationException

/** Public metadata only. APK installation remains an explicit action in the system installer. */
internal object LynkAppUpdates {
    const val PACKAGE = "com.shihab.diplay.lynk"
    const val WEBSITE = "https://soBigRice.github.io/LynkCo-DiPlay/"
    const val REPOSITORY = "https://github.com/soBigRice/LynkCo-DiPlay"
    const val MANIFEST = WEBSITE + "updates/latest.json"
    const val INTERVAL_MS = 24 * 60 * 60 * 1000L

    data class Release(
        val versionCode: Long,
        val versionName: String,
        val minSdk: Int,
        val downloadUrl: String,
        val releaseUrl: String,
        val notes: String,
    )

    sealed interface Result {
        data object NoRelease : Result
        data object Current : Result
        data class Available(val release: Release) : Result
        data class Incompatible(val release: Release) : Result
        data object Failed : Result
    }

    fun parse(json: String, installedPackage: String, installedCode: Long, sdk: Int): Result {
        val root = JSONObject(json)
        require(root.get("schemaVersion") == 1)
        require(installedPackage == PACKAGE && root.getString("packageName") == installedPackage)
        require(root.has("release"))
        if (root.isNull("release")) return Result.NoRelease
        val item = root.getJSONObject("release")
        val release = Release(
            positiveInteger(item, "versionCode"), item.getString("versionName"),
            positiveInteger(item, "minSdk").also { require(it <= Int.MAX_VALUE) }.toInt(),
            item.getString("downloadUrl"), item.getString("releaseUrl"), item.getString("notes"),
        )
        require(release.versionCode > 0 && release.minSdk >= 28)
        require(release.versionName.isNotBlank() && release.versionName.length <= 100)
        require(release.notes.length <= 8000)
        require(allowedUrl(release.downloadUrl, "/soBigRice/LynkCo-DiPlay/releases/download/") &&
            URI(release.downloadUrl).path.endsWith(".apk"))
        require(allowedUrl(release.releaseUrl, "/soBigRice/LynkCo-DiPlay/releases/tag/"))
        if (release.versionCode <= installedCode) return Result.Current
        return if (release.minSdk > sdk) Result.Incompatible(release) else Result.Available(release)
    }

    private fun positiveInteger(item: JSONObject, key: String): Long {
        val value = item.get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it > 0) }
    }

    private fun allowedUrl(value: String, prefix: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.rawQuery == null && uri.fragment == null &&
            uri.rawPath.startsWith(prefix) && !uri.rawPath.contains("%", ignoreCase = true) &&
            uri.normalize().rawPath == uri.rawPath && uri.rawPath.length > prefix.length
    }.getOrDefault(false)
}

/** One bounded request, with no persistent worker. Closing a screen cancels its connection. */
internal class LynkUpdateRequest(
    private val openConnection: () -> HttpURLConnection = {
        URL(LynkAppUpdates.MANIFEST).openConnection() as HttpURLConnection
    },
) : AutoCloseable {
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    fun fetch(): String {
        if (cancelled) throw CancellationException()
        val active = openConnection()
        connection = active
        val deadline = System.nanoTime() + 25_000_000_000L
        try {
            if (cancelled) throw CancellationException()
            active.connectTimeout = 8000
            active.readTimeout = 8000
            active.instanceFollowRedirects = false
            active.useCaches = false
            active.setRequestProperty("Accept", "application/json")
            active.setRequestProperty("User-Agent", "LynkCo-CarPlay-UpdateCheck")
            if (active.responseCode != 200) throw IOException("Update metadata unavailable")
            return active.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException()
                    if (System.nanoTime() >= deadline) throw IOException("Update metadata timed out")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 64 * 1024) throw IOException("Update metadata too large")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            active.disconnect()
            connection = null
        }
    }

    override fun close() {
        cancelled = true
        connection?.disconnect()
    }
}

internal class LynkUpdatePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("lynk_app_updates", Context.MODE_PRIVATE)
    var automatic: Boolean
        get() = preferences.getBoolean("automatic", true)
        set(value) { preferences.edit().putBoolean("automatic", value).apply() }
    fun due(now: Long): Boolean {
        val last = preferences.getLong("last_attempt", 0)
        return automatic && (last == 0L || now < last || now - last >= LynkAppUpdates.INTERVAL_MS)
    }
    fun attempted(now: Long) { preferences.edit().putLong("last_attempt", now).apply() }
    fun save(json: String) { preferences.edit().putString("manifest", json).apply() }
    fun cached(): String? = preferences.getString("manifest", null)
}
