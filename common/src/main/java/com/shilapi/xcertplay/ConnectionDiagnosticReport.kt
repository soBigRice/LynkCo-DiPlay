package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.hud.BydVehicleField
import com.shilapi.xcertplay.hud.BydVehicleFieldStore
import com.shilapi.xcertplay.hud.BydOutputSettings
import java.io.File

/** One report includes both transports even when only one is currently selected. */
internal object ConnectionDiagnosticReport {
    fun build(appContext: Context, version: String, setupReady: Boolean): String = buildString {
        appendLine("Report generated at=${java.time.Instant.now()}; package=${appContext.packageName}")
        appendLine("DiPlay ${version} · private beta diagnostic report")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("BYD integration: ${BydOutputSettings.integrationAllowed(appContext)}")
        appendLine("Selected connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
        appendLine("Authentication: local experimental beta identity; no remote fallback")
        appendLine("CarPlay setup: ${if (setupReady) "ready" else "authentication unavailable"}")
        appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
        appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
        appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
        appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
        appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
        appendLine()
        if (BydOutputSettings.integrationAllowed(appContext)) {
            appendLine("--- Current cluster display diagnostics (even when disabled) ---")
            appendLine(ClusterMapPresentation.diagnosticReport(appContext))
            appendLine()
            appendLine("--- ADB cluster activity routing ---")
            appendLine("adbClusterActivityEnabled=${AirPlayPersistence.loadAdbClusterEnabled(appContext)}")
            appendLine("clusterActivityMainTask=${ClusterActivityOutput.mainTaskId} surfaceValid=${ClusterActivityOutput.surface?.isValid}")
            AdbClusterRouter.report(appContext).lineSequence().forEach { line ->
                DiagnosticRedactor.redact(line)?.let { appendLine(it) }
            }
            appendLine()
            appendLine("--- Standalone HUD compatibility ---")
            appendLine(BydOutputSettings.standaloneHudDiagnosticReport(appContext))
            appendLine()
            appendLine("--- BYD vehicle-data probe ---")
            appendLine(
                "mode=${if (BydOutputSettings.legacyVehicleProbe(appContext)) "legacy-probe" else "default"} " +
                    "switches location=${AirPlayPersistence.loadLocationReportingEnabled(appContext)} " +
                    "battery=${BydOutputSettings.batteryToIphone(appContext)} " +
                    "wheelSpeed=${BydOutputSettings.wheelSpeedToIphone(appContext)} " +
                    "parkedVideo=${BydOutputSettings.videoWhileParked(appContext)}",
            )
            val bydCapabilities = BydVehicleFieldStore.load(appContext)
            if (bydCapabilities == null) {
                appendLine("no saved successful probe")
            } else {
                appendLine(
                    "catalog=${bydCapabilities.catalogAvailable} detectedAt=${bydCapabilities.detectedAtMillis} " +
                        "savedFirmware=${bydCapabilities.firmwareKey} " +
                        "currentFirmware=${BydVehicleFieldStore.firmwareKey()}",
                )
                for (field in BydVehicleField.entries) {
                    val probe = bydCapabilities.result(field)
                    appendLine("${field.name}: supported=${probe.supported} " +
                        (probe.address?.let { "tx=${it.transaction} dev=${it.device} fid=${it.fid} source=${it.source}" }
                            ?: "address=none"))
                }
            }
            appendLine()
        }
        appendLine("--- Recent own-app process exits (Android 11+) ---")
        appendLine(ProcessExitDiagnostics.report(appContext))
        appendLine()
        appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
        appendLine(DisplayDiagnosticSnapshot.report(appContext))
        appendLine()
        appendLine("--- Last received boot and app-launch result ---")
        appendLine(StartupDiagnosticSnapshot.report(appContext))
        appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
            "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
        appendLine()
        appendLine("--- Last retained fatal stack (may predate this attempt) ---")
        appendLine(ConnectionCrashLog.report(appContext))
        appendLine()
        appendLine("--- USB and wireless environment at export ---")
        appendLine(ConnectionEnvironmentSnapshot.capture(appContext))
        if (appContext.resources.getBoolean(com.shilapi.xcertplay.host.R.bool.config_simple_connection_flow)) {
            appendLine("--- Read-only environment checklist at export ---")
            try { appendLine(CarPlayEnvironmentCheck.capture(appContext).diagnosticText(appContext)) }
            catch (error: Exception) { appendLine("Checklist unavailable=${error.javaClass.simpleName}") }
        }
        appendLine("Log queue drained: ${AsyncDiagnosticLog.awaitIdle(2000)}; dropped=${AsyncDiagnosticLog.droppedCount}")
        appendLine("History: last 8 log segments per transport, plus general session logs. Missing history means no retained attempt, not successful connection.")
        for (directory in listOf("connection-wireless", "connection-usb", "requests", "")) {
            appendLine("--- ${directory.ifEmpty { "general" }} history ---")
            var count = 0
            for (name in SessionLogFile.REPORT_NAMES) {
                val file = File(appContext.filesDir, "logs/${if (directory.isEmpty()) "" else "$directory/"}$name")
                if (file.isFile) {
                    count++
                    appendLine("--- $name ---")
                    file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                }
            }
            appendLine("Retained segments: $count")
        }
        appendLine("--- END OF REPORT ---")
    }
}
