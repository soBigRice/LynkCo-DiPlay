package com.shilapi.xcertplay

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LynkAppUpdatesTest {
    private fun manifest(code: Long = 38, sdk: Int = 28): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("packageName", LynkAppUpdates.PACKAGE)
        .put("release", JSONObject().put("versionCode", code).put("versionName", "0.2.12-lynk-osn2-test20")
            .put("minSdk", sdk).put("downloadUrl", LynkAppUpdates.REPOSITORY + "/releases/download/lynk-test20/LynkCo-CarPlay-test20.apk")
            .put("releaseUrl", LynkAppUpdates.REPOSITORY + "/releases/tag/lynk-test20").put("notes", "Update checks"))
    private fun parse(item: JSONObject, code: Long = 37, sdk: Int = 28) =
        LynkAppUpdates.parse(item.toString(), LynkAppUpdates.PACKAGE, code, sdk)

    @Test fun unpublishedIsNotReportedAsUpToDate() {
        assertEquals(LynkAppUpdates.Result.NoRelease, parse(manifest().put("release", JSONObject.NULL)))
    }
    @Test fun numericCodeHandlesTestSuffixesAndNeverOffersDowngrade() {
        assertTrue(parse(manifest()) is LynkAppUpdates.Result.Available)
        assertEquals(LynkAppUpdates.Result.Current, parse(manifest(37)))
        assertEquals(LynkAppUpdates.Result.Current, parse(manifest(36)))
        assertEquals(LynkAppUpdates.Result.Current, parse(manifest(38), 39))
    }
    @Test fun unsupportedAndroidGetsNoInstallAction() {
        assertTrue(parse(manifest(sdk = 29)) is LynkAppUpdates.Result.Incompatible)
        assertTrue(parse(manifest(sdk = 29), sdk = 29) is LynkAppUpdates.Result.Available)
    }
    @Test fun wrongPackageSchemaMissingReleaseAndFractionalCodesAreRejected() {
        val candidates = listOf(
            manifest().put("packageName", "com.shihab.diplay"), manifest().put("schemaVersion", 2),
            manifest().apply { remove("release") },
            manifest().apply { getJSONObject("release").put("versionCode", 38.5) },
            manifest().apply { getJSONObject("release").put("versionCode", "38") },
            manifest().apply { getJSONObject("release").put("minSdk", 27) },
        )
        candidates.forEach { assertTrue(runCatching { parse(it) }.isFailure) }
        assertTrue(runCatching { LynkAppUpdates.parse(manifest().toString(), "com.shihab.diplay", 37, 28) }.isFailure)
    }
    @Test fun downloadIsRestrictedToApkFromThisRepository() {
        val urls = listOf(
            "http://github.com/soBigRice/LynkCo-DiPlay/releases/download/x/app.apk",
            "https://github.com/shihabal3amri/DiPlay/releases/download/x/app.apk",
            "https://github.com.evil.example/soBigRice/LynkCo-DiPlay/releases/download/x/app.apk",
            "https://github.com/soBigRice/LynkCo-DiPlay/releases/download/x/app.aab",
            "https://github.com/soBigRice/LynkCo-DiPlay/releases/download/../../evil.apk",
            "https://github.com/soBigRice/LynkCo-DiPlay/releases/download/%2e%2e/app.apk",
            "https://github.com/soBigRice/LynkCo-DiPlay/releases/download/x/app.apk?other=1",
        )
        urls.forEach { url ->
            val item = manifest().apply { getJSONObject("release").put("downloadUrl", url) }
            assertTrue(url, runCatching { parse(item) }.isFailure)
        }
    }
    @Test fun automaticChecksAreDailyDisabledAndClockRollbackSafe() {
        val preferences = LynkUpdatePreferences(RuntimeEnvironment.getApplication())
        val now = 1_000_000L
        assertTrue(preferences.due(now))
        preferences.attempted(now)
        assertFalse(preferences.due(now + LynkAppUpdates.INTERVAL_MS - 1))
        assertTrue(preferences.due(now + LynkAppUpdates.INTERVAL_MS))
        assertTrue(preferences.due(now - 1))
        preferences.automatic = false
        assertFalse(preferences.due(now + LynkAppUpdates.INTERVAL_MS))
    }
    @Test fun successfulMetadataCanBeRestoredWithoutNetwork() {
        val context = RuntimeEnvironment.getApplication()
        LynkUpdatePreferences(context).save(manifest().toString())
        assertTrue(parse(JSONObject(LynkUpdatePreferences(context).cached()!!)) is LynkAppUpdates.Result.Available)
    }
    @Test fun requestIsBoundedAndDisconnectsOnSuccess() {
        val connection = mock(HttpURLConnection::class.java)
        `when`(connection.responseCode).thenReturn(200)
        `when`(connection.inputStream).thenReturn(ByteArrayInputStream(manifest().toString().toByteArray()))
        assertTrue(parse(JSONObject(LynkUpdateRequest { connection }.fetch())) is LynkAppUpdates.Result.Available)
        verify(connection).disconnect()
        verify(connection).setInstanceFollowRedirects(false)
        verify(connection).setConnectTimeout(8000)
        verify(connection).setReadTimeout(8000)
    }
    @Test fun redirectsHttpErrorsAndOversizeResponsesFailAndReleaseConnection() {
        for (status in listOf(301, 404, 500, 200)) {
            val connection = mock(HttpURLConnection::class.java)
            `when`(connection.responseCode).thenReturn(status)
            `when`(connection.inputStream).thenReturn(ByteArrayInputStream(ByteArray(65 * 1024)))
            assertTrue(runCatching { LynkUpdateRequest { connection }.fetch() }.isFailure)
            verify(connection).disconnect()
        }
    }
    @Test fun cancelledRequestDoesNotOpenAConnection() {
        val connection = mock(HttpURLConnection::class.java)
        val request = LynkUpdateRequest { connection }
        request.close()
        assertTrue(runCatching { request.fetch() }.exceptionOrNull() is CancellationException)
        verifyNoInteractions(connection)
    }
}
