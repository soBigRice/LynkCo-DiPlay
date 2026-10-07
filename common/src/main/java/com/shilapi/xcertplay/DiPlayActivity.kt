// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.view.Window
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydFieldSource
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydVehicleCapabilities
import com.shilapi.xcertplay.hud.BydVehicleField
import com.shilapi.xcertplay.hud.BydVehicleFieldStore
import com.shilapi.xcertplay.hud.BydVehicleProbeOutcome
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.EvChargingConnectors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val dockWindow by lazy { LynkDockWindow(this) }

    private fun applyDockWindow() {
        val active = dockWindow.apply(LynkDockLayout.enabled(this))
        WindowCompat.setDecorFitsSystemWindows(window, !active)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            show(WindowInsetsCompat.Type.navigationBars())
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var settingsGroup = LynkSettingsGroup.GENERAL
    private var clusterSafeAreaDialog: Dialog? = null
    private var clusterContentRequestVersion = 0L
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var phonePickerShowing = false
    private val wirelessPreparationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Read the actual bond/radio state, not the broadcast extras. Enabling Bluetooth
            // and returning from OEM settings can complete after the activity result.
            resumeWirelessPreparation()
        }
    }
    private val simpleConnectionFlow get() = resources.getBoolean(R.bool.config_simple_connection_flow)
    private var bluetoothRecoveryInProgress = false
    private var bluetoothRecoveryError = false
    private val automaticHotspot get() = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private val lynkUpdatesEnabled get() = packageName == LynkAppUpdates.PACKAGE
    private val updatePreferences by lazy { LynkUpdatePreferences(applicationContext) }
    private var updateResult: LynkAppUpdates.Result? = null
    private var updateRequest: LynkUpdateRequest? = null
    private var updateThread: Thread? = null
    private var updateStatus: TextView? = null
    private var updateCheckButton: Button? = null
    private var updateDownloadButton: Button? = null
    private var updateNotesButton: Button? = null
    private var updateNotice: TextView? = null
    private var environmentReport: EnvironmentReport? = null
    private var environmentRunning = false
    private var environmentFailed = false
    private var environmentGroup = EnvironmentGroup.APP
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var rootScroll: ScrollView? = null
    private var renderedPage: String? = null
    private var pendingScrollY: Int? = null
    private var bydVehicleAdvancedExpanded = false
    private var adbAccessState: BydAdbAccess.State? = null
    private var adbCheckInProgress = false
    private var adbCheckMayAsk = false
    private var adbCheckFailed = false
    private var vehicleProbeAuthorizationInProgress = false
    private var vehicleProbeInProgress = false
    private var vehicleProbeOutcome: BydVehicleProbeOutcome? = null
    private var adbCheckGeneration = 0
    private var adbStatus: TextView? = null
    private var bydAdbControls: LinearLayout? = null
    private var adbSwitchChangePending = false
    private var pausedForAdbSwitchChange = false
    private var updatingAdbSwitches = false
    private val adbSwitches = mutableMapOf<Int, Pair<Switch, () -> Boolean>>()
    private var hotspotStartupResult: CarHotspotTethering.Result? = null
    @Volatile private var startupHotspotCancelled = false
    @Volatile private var vehicleProbeGeneration = 0
    @Volatile private var vehicleValidationGeneration = 0
    private val vehicleOperationLock = Any()
    private var automaticVehicleValidationStarted = false
    private var automaticVehicleValidationInProgress = false
    private var automaticVehicleValidationPending = false
    private var pendingVehicleReplacement: BydVehicleCapabilities? = null
    // The saved snapshot [pendingVehicleReplacement] was compared with.
    private var pendingVehicleReplacementExpected: BydVehicleCapabilities? = null
    private var pendingVehicleLostFields: Set<BydVehicleField> = emptySet()
    private var defaultVehicleStatus: BydAdbAccess.Status? = null
    private var vehicleDataReconnectPending = false
    private val automaticVehicleValidation = Runnable {
        automaticVehicleValidationPending = false
        validateSavedVehicleConfigurationAutomatically()
    }
    private data class VehicleProbeAttempt(
        val outcome: BydVehicleProbeOutcome,
        val heldCandidate: BydVehicleCapabilities? = null,
        val lostFields: Set<BydVehicleField> = emptySet(),
        val snapshotChanged: Boolean = false,
        val allowedOnlyOnce: Boolean = false,
    )

    private data class VehicleValidationAttempt(
        val status: BydAdbAccess.Status,
        val outcome: BydVehicleProbeOutcome? = null,
        val heldCandidate: BydVehicleCapabilities? = null,
        val lostFields: Set<BydVehicleField> = emptySet(),
        val snapshotChanged: Boolean = false,
        val savedFieldsReadable: Boolean = false,
    )
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (simpleConnectionFlow && pendingWireless) connect(true) else choosePhone()
        } else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val wirelessBluetoothSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        resumeWirelessPreparation()
    }
    private val enableWirelessBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        resumeWirelessPreparation()
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) {
            applyLocationReporting(true)
        } else {
            render()
            permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
        }
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languagePreferenceAtCreate = AppLocale.preference(this)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingWireless = savedInstanceState?.getBoolean("pending_wireless") ?: false
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        bydVehicleAdvancedExpanded = savedInstanceState?.getBoolean("byd_vehicle_advanced") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        settingsGroup = savedInstanceState?.getString("lynk_settings_group")?.let { name ->
            LynkSettingsGroup.entries.find { it.name == name }
        } ?: LynkSettingsGroup.GENERAL
        if (lynkUpdatesEnabled) updateResult = updatePreferences.cached()?.let {
            runCatching { parseUpdateManifest(it) }.getOrNull()
        }
        render()
        if (page == "environment") checkEnvironment()
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
        handleDiagnosticExport()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { pendingWireless = false; page = "home"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        pendingWireless = false
        page = intent.getStringExtra("page") ?: "home"; render()
        if (page == "environment") checkEnvironment()
        automaticVehicleValidationStarted = false
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
        handleDiagnosticExport()
    }
    private fun handleDiagnosticExport() {
        if (page != "export-connection-log") return
        page = "settings"
        intent.putExtra("page", "settings")
        render()
        exportDiagnostics()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_wireless", pendingWireless)
        outState.putString("page", page)
        outState.putString("lynk_settings_group", settingsGroup.name)
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        outState.putBoolean("byd_vehicle_advanced", bydVehicleAdvancedExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDockWindow()
        render()
    }
    private fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            android.widget.Toast.makeText(this, R.string.center_map_no_permission_screen, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (simpleConnectionFlow) androidx.core.content.ContextCompat.registerReceiver(this,
            wirelessPreparationReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        CenterMapOverlay.onDiPlayScreenShown()
        // Check only on the idle launcher, never during the connection/CarPlay handoff.
        if (lynkUpdatesEnabled && page == "home" && !CarPlayBackgroundSession.hasSession() &&
            !pendingWireless && !DiPlayPreferences.autoConnect(this) &&
            updatePreferences.due(System.currentTimeMillis())) checkAppUpdates()
    }

    override fun onStop() {
        cancelAppUpdateCheck()
        if (simpleConnectionFlow) unregisterReceiver(wirelessPreparationReceiver)
        clusterSafeAreaDialog?.dismiss()
        startupHotspotCancelled = true
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    override fun onResume() {
        super.onResume()
        applyDockWindow()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        recoverBluetoothIfNeeded { resumeWirelessPreparation() }
        if (simpleConnectionFlow && pendingWireless && page == "wireless") handler.post { resumeWirelessPreparation() }
        handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && !adbSwitchChangePending && !pausedForAdbSwitchChange &&
            (page == "home" || page == "settings" || page == "connection")) render()
        pausedForAdbSwitchChange = false
        if (!initialLaunch && page == "environment") checkEnvironment()
        if (initialLaunch) {
            initialLaunch = false
            startCarHotspotOnLaunch()
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                (!simpleConnectionFlow || (page == "home" && !pendingWireless)) &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() {
        WheelKeyService.cancelLearning()
        pausedForAdbSwitchChange = adbSwitchChangePending
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        cancelAppUpdateCheck()
        WheelKeyService.cancelLearning()
        handler.removeCallbacks(automaticVehicleValidation)
        adbCheckGeneration++
        synchronized(vehicleOperationLock) {
            vehicleProbeGeneration++
            vehicleValidationGeneration++
        }
        adbCheckInProgress = false
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        automaticVehicleValidationInProgress = false
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyDockWindow()
        render()
    }

    private val isCompactLayout: Boolean
        get() = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) ||
            resources.configuration.screenWidthDp < 550 ||
            resources.configuration.screenHeightDp < 450

    private fun render() {
        applyDockWindow()
        // A pending assignment belongs to the widgets being replaced, never to another page.
        WheelKeyService.cancelLearning()
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf { renderedPage == page }
        status = null; connectButton = null; disconnectButton = null; exportButton = null; lastRunning = null
        bydAdbControls = null
        adbSwitches.clear()
        adbStatus = null
        if (simpleConnectionFlow) { renderLynkPanel(); return }
        val compact = isCompactLayout
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        rootScroll = scroll
        val content = column().apply {
            if (compact) setPadding(dp(12), dp(10), dp(12), dp(12))
            else setPadding(dp(32), dp(24), dp(32), dp(32))
        }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_carplay)
                contentDescription = getString(R.string.carplay)
            },
            LinearLayout.LayoutParams(if (compact) dp(24) else dp(36), if (compact) dp(24) else dp(36)),
        )
        header.addView(
            label(getString(R.string.diplay), if (compact) 18 else 26, TEXT, true).apply {
                setPadding(if (compact) dp(8) else dp(12), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, if (compact) dp(36) else dp(56), 1f),
        )
        if (page != "home" || !compact) {
            header.addView(
                button(if (page == "home") getString(R.string.car_home) else getString(R.string.back), false) {
                    if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                    else { page = "home"; render() }
                },
                LinearLayout.LayoutParams(if (compact) dp(80) else dp(130), if (compact) dp(36) else dp(56)),
            )
        }
        content.addView(header)
        content.addView(space(if (compact) 8 else 24))
        when (page) {
            "connection" -> connectionSetup(content)
            "settings" -> settings(content)
            "about" -> about(content)
            else -> home(content)
        }
        setContentView(scroll)
        renderedPage = page
        refreshStatus()
        pendingScrollY = previousScrollY
        // A stopped window still dispatches pre-draw but skips layout, so wait for a real layout;
        // the listener stays on this view and goes away with it.
        previousScrollY?.let { y ->
            scroll.doOnLayout {
                if (rootScroll === scroll) {
                    scroll.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
    }

    private fun home(content: LinearLayout) {
        if (simpleConnectionFlow) { connectionHome(content); return }
        val compact = isCompactLayout
        if (compact) {
            val card = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
            status = label(getString(R.string.ready_when_you_are), 16, TEXT, true).apply {
                setPadding(0, 0, 0, dp(8))
            }
            card.addView(status)
            connectButton = button(getString(R.string.connect_phone), true) {
                if (CarPlayBackgroundSession.hasSession()) openProjection()
                else connect(true)
            }
            card.addView(connectButton, matchButton(0, 44))

            val buttonRow = row().apply {
                setPadding(0, dp(8), 0, 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            val usbBtn = button(getString(R.string.connect_with_usb), false) { connect(false) }
            val settingsBtn = button(getString(R.string.settings), false) { page = "settings"; render() }
            buttonRow.addView(usbBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
            buttonRow.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
            buttonRow.addView(settingsBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
            card.addView(buttonRow)

            disconnectButton = button(getString(R.string.disconnect), false) {
                disconnectButton?.isEnabled = false
                CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
            }.apply { visibility = View.GONE }
            card.addView(disconnectButton, matchButton(8, 38))

            content.addView(card)
            setupError?.let { content.addView(label(it, 13, WARNING).apply { setPadding(0, dp(6), 0, 0) }) }
            return
        }

        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        left.addView(label(getString(R.string.your_phone_your_drive), 12, ACCENT, true).apply { letterSpacing = .16f })
        left.addView(label(getString(R.string.a_familiar_drive), if (wide) 42 else 36, TEXT, true).apply { setPadding(0, dp(12), 0, dp(10)) })
        left.addView(label(getString(R.string.your_maps_music_and_conversations_carplay_right_here_on_yo), 19, MUTED))
        val card = card()
        card.addView(label(getString(R.string.wireless_carplay), 12, ACCENT, true).apply { letterSpacing = .12f })
        status = label(getString(R.string.ready_when_you_are), 24, TEXT, true).apply { setPadding(0, dp(10), 0, dp(16)) }
        card.addView(status)
        connectButton = button(getString(R.string.connect_phone), true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        card.addView(connectButton, matchButton())
        val connectionHint = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_hint)
            WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_hint_manual)
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
            else -> getString(R.string.hotspot_hint_p2p)
        }
        card.addView(label(connectionHint, 15, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        val startupProblem = hotspotStartupResult?.takeIf {
            it != CarHotspotTethering.Result.READY && it != CarHotspotTethering.Result.CANCELLED &&
                CarHotspotSettings.shouldEnable(this, true, AirPlayPersistence.loadWirelessHotspotMode(this)) &&
                com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) != true
        }
        if (startupProblem != null) {
            card.addView(label(hotspotResultText(startupProblem), 15, WARNING))
            card.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(10, 56))
        } else if (carHotspotOff()) {
            card.addView(label(getString(R.string.msg_car_hotspot_off, AirPlayPersistence.loadManualHotspotSsid(this)), 15, WARNING).apply { setPadding(0, dp(14), 0, 0) })
            card.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(10, 56))
        }
        card.addView(button(getString(R.string.choose_iphone), false) { choosePhone() }, matchButton(16, 56))
        disconnectButton = button(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        card.addView(disconnectButton, matchButton(10, 56))
        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        right.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton())
        right.addView(label(getString(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24)) })
        right.addView(button(getString(R.string.settings), false) { page = "settings"; render() }, matchButton())
        right.addView(label(getString(R.string.make_diplay_feel_right_for_your_car), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
        right.addView(label("${getString(R.string.home_public_preview)}${version()}", 12, MUTED).apply { letterSpacing = .08f })
        if (wide) {
            // Both rows share column widths. The USB button starts at the wireless
            // card's top edge, independently of hero wrapping or font scaling.
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(40), LinearLayout.LayoutParams(dp(40), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            body.addView(left)
            body.addView(space(26))
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        setupError?.let { body.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
        content.addView(body)
    }

    private fun renderLynkPanel() {
        updateStatus = null
        updateCheckButton = null
        updateDownloadButton = null
        updateNotesButton = null
        updateNotice = null
        val shell = column().apply { setBackgroundColor(BG) }
        val body = column().apply { setPadding(dp(28), dp(16), dp(28), dp(12)) }
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        if (page != "home") header.addView(button(getString(R.string.lynk_home), false) {
            pendingWireless = false; page = "home"; render()
        }, LinearLayout.LayoutParams(dp(96), dp(48)).apply { marginEnd = dp(20) })
        header.addView(label(getString(R.string.lynk_panel_brand), 20, TEXT, true).apply {
            letterSpacing = .12f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (CarPlayBackgroundSession.hasSession() &&
            (page == "home" || page == "settings" || resources.configuration.screenWidthDp >= 600)) {
            header.addView(button(getString(R.string.video_back_to_carplay), false) { openProjection() },
                LinearLayout.LayoutParams(dp(144), dp(48)).apply { marginEnd = dp(12) })
        }
        if (page != "settings") header.addView(button(getString(R.string.settings), false) {
            pendingWireless = false; page = "settings"; render()
        }, LinearLayout.LayoutParams(dp(96), dp(48)))
        body.addView(header)
        if (page == "settings") {
            val categories = row()
            listOf(LynkSettingsGroup.GENERAL to R.string.lynk_general,
                LynkSettingsGroup.DISPLAY to R.string.lynk_display,
                LynkSettingsGroup.AUDIO to R.string.lynk_audio,
                LynkSettingsGroup.SUPPORT to R.string.lynk_support).forEach { (group, title) ->
                categories.addView(button(getString(title), group == settingsGroup) {
                    settingsGroup = group; render()
                }.apply { isSelected = group == settingsGroup }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(8) })
            }
            body.addView(categories, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(4) })
        }
        val content = column().apply { setPadding(0, dp(16), 0, dp(8)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; addView(content) }
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (page) {
            "environment" -> EnvironmentCheckPanel.render(content, environmentReport, environmentRunning, environmentFailed,
                environmentGroup, { environmentGroup = it; render() }, ::checkEnvironment,
                { exportDiagnostics() }, ::resolveEnvironmentIssue)
            "wireless" -> wirelessPreparation(content)
            "connection" -> simpleConnectionSetup(content)
            "settings" -> settings(content)
            "about" -> about(content)
            else -> connectionHome(content)
        }
        if (page == "home" && lynkUpdatesEnabled) {
            updateNotice = label("", 15, ACCENT).apply {
                minHeight = dp(48)
                isFocusable = true
                setOnClickListener { page = "settings"; settingsGroup = LynkSettingsGroup.SUPPORT; render() }
            }
            content.addView(updateNotice)
            refreshAppUpdateViews()
        }
        shell.addView(body, LinearLayout.LayoutParams(-1, -1))
        setContentView(shell)
        refreshStatus()
    }

    private fun checkEnvironment() {
        if (environmentRunning) { render(); return }
        environmentRunning = true
        environmentFailed = false
        render()
        val app = applicationContext
        Thread({
            val result = runCatching { CarPlayEnvironmentCheck.capture(app) }
            runOnUiThread {
                environmentRunning = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.onSuccess { environmentReport = it }.onFailure {
                    environmentFailed = true
                    environmentReport = null
                    Log.w("DiPlayEnvironment", "Environment check unavailable: ${it.javaClass.simpleName}")
                }
                if (page == "environment" || page == "home") render()
            }
        }, "lynk-environment-check").start()
    }

    private fun resolveEnvironmentIssue(action: EnvironmentAction) {
        when (action) {
            EnvironmentAction.PERMISSIONS -> openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            EnvironmentAction.LOCATION -> openSystem(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            EnvironmentAction.BLUETOOTH -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            EnvironmentAction.CONNECTION -> { page = "connection"; render() }
            EnvironmentAction.DISPLAY -> { page = "settings"; settingsGroup = LynkSettingsGroup.DISPLAY; render() }
            EnvironmentAction.USB -> connect(false)
        }
    }

    private fun connectionHome(content: LinearLayout) {
        content.gravity = Gravity.CENTER_VERTICAL
        content.addView(label(getString(R.string.carplay), 32, TEXT, true).apply {
            setPadding(0, 0, 0, dp(20))
        })
        val wide = resources.configuration.screenWidthDp >= 600
        val methods = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        fun tile(title: Int, detail: Int, icon: Int, action: () -> Unit): Button {
            val name = getString(title)
            val copy = "$name\n${getString(detail)}"
            return button(copy, false, action).apply {
                contentDescription = name
                textSize = 25f
                text = android.text.SpannableString(copy).apply {
                    setSpan(android.text.style.RelativeSizeSpan(.56f), name.length + 1, length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(MUTED), name.length + 1, length, 0)
                }
                setLineSpacing(dp(12).toFloat(), 1f)
                setPadding(dp(24), dp(28), dp(24), dp(28))
                backgroundTintList = ColorStateList.valueOf(SURFACE)
                val glyph = getDrawable(icon)?.mutate()?.apply {
                    setTint(ACCENT); setBounds(0, 0, dp(42), dp(42))
                }
                setCompoundDrawables(null, glyph, null, null)
                compoundDrawablePadding = dp(22)
                minimumHeight = dp(200)
            }
        }
        methods.addView(tile(R.string.lynk_wireless,
            if (automaticHotspot) R.string.lynk_wireless_caption else R.string.lynk_manual_caption,
            R.drawable.ic_dp_connection) {
            startLynkConnection(true)
        }, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) }
            else LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        methods.addView(tile(R.string.lynk_wired, R.string.lynk_wired_caption, R.drawable.ic_lynk_usb) {
            startLynkConnection(false)
        }, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        content.addView(methods)
        status = label("", 14, MUTED).apply {
            setPadding(0, dp(16), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        content.addView(label(getString(R.string.lynk_upstream_credit), 12, MUTED).apply {
            setPadding(0, dp(24), 0, 0)
        })
        setupError?.let { content.addView(label(it, 16, WARNING).apply { setPadding(0, dp(12), 0, 0) }) }
    }

    /** A prerequisite page, shown only when a wireless start needs user input. */
    private fun wirelessPreparation(content: LinearLayout) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val allowed = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val enabled = allowed && adapter?.isEnabled == true
        val hasBonds = enabled && adapter?.bondedDevices?.isNotEmpty() == true
        val saved = DiPlayPreferences.phoneAddress(this)
        content.addView(label(getString(R.string.lynk_wireless), 28, TEXT, true))
        val body = card()
        val title = when {
            !allowed -> R.string.link_permission_needed
            adapter == null -> R.string.link_bluetooth_failed
            !enabled -> R.string.turn_on_bluetooth
            saved != null || hasBonds -> R.string.lynk_choose_paired
            else -> R.string.lynk_pair_once
        }
        val detail = when {
            !allowed -> R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired
            adapter == null -> R.string.link_bluetooth_fix
            !enabled -> R.string.lynk_enable_bluetooth_hint
            saved != null -> R.string.lynk_saved_phone_missing
            hasBonds -> R.string.lynk_pick_once
            else -> R.string.lynk_first_pair_hint
        }
        body.addView(label(getString(title), 23, TEXT, true))
        body.addView(label(getString(detail), 17, MUTED).apply { setPadding(0, dp(12), 0, dp(12)) })
        if (saved != null) body.addView(label(getString(R.string.link_saved_phone, DiPlayPreferences.phoneName(this)), 15, ACCENT))
        if (!allowed || (adapter != null && !enabled)) {
            body.addView(button(getString(if (allowed) R.string.turn_on_bluetooth else R.string.lynk_continue), true) {
                connect(true)
            }, matchButton(16, 56))
        } else if (enabled) {
            body.addView(button(getString(R.string.bluetooth_settings), true) {
                pendingWireless = true
                wirelessBluetoothSettings.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }, matchButton(16, 56))
            body.addView(button(getString(R.string.lynk_choose_paired), false) {
                pendingWireless = true; choosePhone()
            }, matchButton(12, 56))
        } else {
            body.addView(button(getString(R.string.connect_with_usb), true) { connect(false) }, matchButton(16, 56))
        }
        content.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        content.addView(label(getString(if (automaticHotspot) R.string.lynk_wireless_next else R.string.link_pair_hint), 15, MUTED).apply { setPadding(0, dp(18), 0, 0) })
    }

    private fun rememberedBondedPhone(): BluetoothDevice? {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        if (!adapter.isEnabled) return null // Off is not an unpair event; keep the saved selection.
        return DiPlayBluetooth.preferredPhone(adapter.bondedDevices.orEmpty(), DiPlayPreferences.phoneAddress(this))
    }

    private fun resumeWirelessPreparation() {
        if (!simpleConnectionFlow || !pendingWireless || page != "wireless" || phonePickerShowing ||
            isFinishing || isDestroyed || bluetoothRecoveryInProgress) return
        if (rememberedBondedPhone() != null) connect(true) else render()
    }

    private fun prepareLynkWireless(): Boolean {
        pendingWireless = true
        val phone = rememberedBondedPhone()
        if (phone != null) {
            DiPlayPreferences.savePhone(this, phone.address, phone.name ?: DiPlayPreferences.phoneName(this))
            pendingWireless = false
            return true
        }
        page = "wireless"
        render()
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter != null && !adapter.isEnabled) {
                runCatching { enableWirelessBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    .onFailure { toast(getString(R.string.lynk_enable_bluetooth_hint)) }
            } else if (adapter?.bondedDevices?.isNotEmpty() == true && !phonePickerShowing) choosePhone()
        }
        return false
    }

    private fun settings(content: LinearLayout) {
        if (simpleConnectionFlow && settingsGroup == LynkSettingsGroup.GENERAL) {
            val utilities = row()
            listOf(R.string.link_setup to "connection", R.string.env_title to "environment").forEach { (title, destination) ->
                utilities.addView(button(getString(title), false) {
                    page = destination
                    if (destination == "environment") checkEnvironment() else render()
                }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(10) })
            }
            utilities.addView(button(getString(R.string.lynk_export_short), false) { exportDiagnostics() },
                LinearLayout.LayoutParams(0, dp(56), 1f))
            content.addView(utilities, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
            if (CarPlayBackgroundSession.hasSession()) content.addView(button(getString(R.string.disconnect), false) {
                CarPlayBackgroundSession.stop { runOnUiThread { render() } }
            }, matchButton(0, 56))
        }
        content.addView(label(getString(if (simpleConnectionFlow) R.string.settings else R.string.your_drive_your_way), if (simpleConnectionFlow) 28 else 34, TEXT, true))
        content.addView(label(getString(if (simpleConnectionFlow && settingsGroup != LynkSettingsGroup.DISPLAY) R.string.lynk_settings_copy else R.string.apply_reconnects_carplay_for_size_resolution_music_buffer), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.carplay_controls), R.drawable.ic_dp_display) { card ->
            val gestureFingers = listOf(2, 3, 4)
            choice(card, getString(R.string.settings_gesture_fingers_label),
                gestureFingers.map { getString(R.string.settings_gesture_fingers_option, it) },
                gestureFingers.indexOf(AirPlayPersistence.loadSettingsGestureFingers(this)).coerceAtLeast(0),
                reconnects = false) {
                AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingers[it])
            }
            card.addView(label(getString(if (simpleConnectionFlow) R.string.link_settings_hint else R.string.settings_gesture_fingers_hint), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        section(content, getString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
            card.addView(label(getString(R.string.choose_how_to_connect_follow_the_setup_steps_and_save_your), 16, MUTED))
            card.addView(button(getString(R.string.open_connection_setup), false) { page = "connection"; render() }, matchButton(12, 60))
        }
        section(content, getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics, LynkSettingsGroup.SUPPORT) { card ->
            exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                exportDiagnostics()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            card.addView(button(getString(R.string.choose_save_location), false) { chooseReportDestination() }, matchButton(10, 60))
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getString(R.string.reports_save_to_downloads_diplay) else getString(R.string.link_export_hint)
            card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
        section(content, getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
            toggle(card, getString(R.string.connect_when_diplay_opens), getString(R.string.use_your_last_connection_type_and_selected_iphone), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            adbToggle(card, R.string.open_after_the_car_starts,
                R.string.availability_depends_on_your_head_unit_s_startup_settings,
                read = { AirPlayPersistence.loadAutoStartOnBoot(this) },
                needsAdb = { CarHotspotSettings.enabled(this) &&
                    AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL },
                permissions = { listOf(CarHotspotSetup.Permission.BOOT_LAUNCH) }) {
                AirPlayPersistence.saveAutoStartOnBoot(this, it)
            }
            toggle(
                card,
                getString(R.string.usb_auto_confirm_title),
                getString(R.string.usb_auto_confirm_subtitle),
                UsbAutoConfirmService.isEnabled(this),
            ) {
                UsbAutoConfirmService.openSettings(this)
            }
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
        }
        bydAdbSettings(content)
        section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display, LynkSettingsGroup.DISPLAY) { card ->
            if (simpleConnectionFlow) {
                toggle(card, getString(R.string.lynk_dock_title), getString(R.string.lynk_dock_description),
                    AirPlayPersistence.loadLynkDockEnabled(this)) { enabled ->
                    AirPlayPersistence.saveLynkDockEnabled(this, enabled)
                    render()
                    if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
                }
            }
            val nightModes = CarPlayNightMode.entries
            choice(
                card,
                getString(R.string.carplay_night_mode),
                listOf(
                    getString(R.string.carplay_night_system),
                    getString(R.string.carplay_night_ambient),
                    getString(R.string.carplay_night_day),
                    getString(R.string.carplay_night_night),
                ),
                nightModes.indexOf(AirPlayPersistence.loadCarPlayNightMode(this)),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveCarPlayNightMode(this, nightModes[index])
            }
            card.addView(label(getString(R.string.carplay_night_hint), 14, MUTED))
            card.addView(label(getString(R.string.carplay_night_time_note), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientLightThresholdControl(card)
            nightDelaySettingControl(card, R.string.ambient_delay_title, R.string.ambient_delay_hint,
                0..60, 2, R.string.ambient_delay_summary, { AirPlayPersistence.loadAmbientDelaySeconds(this) },
                save = { AirPlayPersistence.saveAmbientDelaySeconds(this, it) })
            card.addView(button(getString(R.string.picture_adjustments), false) {
                startActivity(Intent(this, CarPlayHostActivity::class.java)
                    .putExtra("picture_controls", true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, matchButton(0, 56).apply { bottomMargin = dp(24) })
            carPlaySizeControl(card)
            if (LynkDockLayout.enabled(this)) card.addView(label(getString(R.string.lynk_dock_active), 14, MUTED))
            else resolutionSettingControl(
                card, R.string.resolution, R.string.custom_resolution_hint,
                CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT, 100,
                R.string.custom_resolution_summary,
                { AirPlayPersistence.loadDisplayScalePercent(this) }, reconnects = true,
                save = { AirPlayPersistence.saveDisplayScalePercent(this, it) },
            )
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            toggle(card, getString(R.string.efficient_video), getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility), AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
            toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            addSystemBarControls(
                hideTopBar = AirPlayPersistence.loadHideTopBar(this),
                hideBottomBar = AirPlayPersistence.loadHideBottomBar(this),
                onHideTopBarChanged = { AirPlayPersistence.saveHideTopBar(this, it) },
                onHideBottomBarChanged = { AirPlayPersistence.saveHideBottomBar(this, it) },
            ) { label, checked, onChanged ->
                toggle(card, getString(label), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), checked, save = onChanged)
            }
            toggle(card, getString(R.string.adapt_pip_resolution), getString(R.string.adapt_pip_resolution_description), AirPlayPersistence.loadAdaptPipResolution(this)) {
                AirPlayPersistence.saveAdaptPipResolution(this, it)
            }
        }
        section(content, getString(R.string.audio_routing), group = LynkSettingsGroup.AUDIO) { card ->
            toggle(card, getString(R.string.contrib_audio_home_toggle_audio_focus), getString(R.string.contrib_audio_home_toggle_audio_focus_desc), AirPlayPersistence.loadAudioFocusEnabled(this)) { AirPlayPersistence.saveAudioFocusEnabled(this, it) }
            if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                toggle(card, getString(R.string.advanced_audio_channel_mapping),
                    getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                }
            }
            mediaChannelControl(card)
            navigationChannelControl(card)
        }
        section(content, getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.report_location_to_iphone),
                getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                AirPlayPersistence.loadLocationReportingEnabled(this), save = ::onLocationReportingChanged)
            card.addView(label(getString(R.string.location_reporting_reconnects), 14, MUTED))
            card.addView(button(getString(if (bydVehicleAdvancedExpanded)
                R.string.hide_advanced_vehicle_data else R.string.advanced_vehicle_data), false) {
                bydVehicleAdvancedExpanded = !bydVehicleAdvancedExpanded
                render()
            }, matchButton(12, 56))
            if (bydVehicleAdvancedExpanded) {
                advancedVehicleData(card)
                // Dashboard song needs ADB, not the navigation receiver; show it here when that card is hidden.
                if (!BydOutputSettings.available(this)) clusterSongSwitch(card)
            }
        }
        // Cluster video does not require a BYD navigation broadcast receiver.
        section(content, getString(R.string.carplay_map_on_instrument_cluster_experimental), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.adb_cluster_activity_mode),
                getString(R.string.adb_cluster_activity_description), AirPlayPersistence.loadAdbClusterEnabled(this)) {
                AirPlayPersistence.saveAdbClusterEnabled(this, it)
                ClusterActivityOutput.stopForSettings()
                render()
                reconnectForClusterMap()
            }
            val adbCluster = AdbClusterRouter.enabled(this)
            if (adbCluster) {
                card.addView(button(getString(R.string.adb_cluster_authorize), false) { authorizeClusterRouting() }, matchButton(10, 56))
                card.addView(button(getString(R.string.adb_cluster_open), false) { ClusterActivityOutput.retry() }, matchButton(10, 56))
            }
            if (adbCluster && com.shilapi.xcertplay.hud.BydOemClusterNavi.applicable(this)) {
                val holds = com.shilapi.xcertplay.hud.BydOemClusterHold.entries
                card.addView(label(getString(R.string.oem_cluster_map_description), 14, MUTED))
                choice(card, getString(R.string.oem_cluster_map), holds.map { it.localizedLabel(this) },
                    holds.indexOf(BydOutputSettings.oemClusterHold(this))) { index ->
                    BydOutputSettings.setOemClusterHold(this, holds[index])
                    ClusterActivityOutput.stopForSettings()
                    reconnectForClusterMap()
                }
            }
            val clusterDisplay = ClusterMapPresentation.findDisplay(this)
            val clusterSize = clusterDisplay?.let { ClusterMapPresentation.sizeOf(it) }
            val diLink4 = adbCluster || (clusterDisplay != null && clusterSize != null &&
                DiLink4ClusterDisplay.matches(clusterDisplay.name, clusterSize.x, clusterSize.y))
            val clusterMapEnabled = AirPlayPersistence.loadClusterMapEnabled(this)
            toggle(card, getString(R.string.carplay_map_on_instrument_cluster_experimental),
                if (clusterDisplay != null || adbCluster) getString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c)
                else getString(R.string.shows_the_iphone_s_cluster_map_virtual_stream_description),
                clusterMapEnabled) {
                AirPlayPersistence.saveClusterMapEnabled(this, it)
                render()
                reconnectForClusterMap()
            }
            if (clusterMapEnabled) {
                toggle(card, getString(R.string.center_map_card),
                    if (clusterDisplay != null || adbCluster) getString(R.string.center_map_card_description)
                    else getString(R.string.center_map_card_virtual_description),
                    AirPlayPersistence.loadCenterMapOverlay(this)) {
                    AirPlayPersistence.saveCenterMapOverlay(this, it)
                    if (it && !CenterMapOverlay.permitted(this)) openOverlayPermission()
                    render()
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    toggle(card, getString(R.string.center_map_follows_dashboard), getString(R.string.center_map_follows_dashboard_description),
                        AirPlayPersistence.loadCenterMapFollowsDashboard(this)) {
                        AirPlayPersistence.saveCenterMapFollowsDashboard(this, it)
                    }
                    toggle(card, getString(R.string.center_map_auto_hide), getString(R.string.center_map_auto_hide_description),
                        AirPlayPersistence.loadCenterMapAutoHide(this)) {
                        AirPlayPersistence.saveCenterMapAutoHide(this, it)
                    }
                }
                toggle(card, getString(R.string.launcher_map_sharing), getString(R.string.launcher_map_sharing_description),
                    AirPlayPersistence.loadLauncherMapSharing(this)) {
                    AirPlayPersistence.saveLauncherMapSharing(this, it)
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    val overlay = CenterMapOverlay.permitted(this)
                    card.addView(label(if (overlay) getString(R.string.center_map_overlay_allowed)
                        else getString(R.string.center_map_overlay_missing, packageName), 14, if (overlay) MUTED else WARNING))
                    val usage = HomeScreenMonitor.hasAccess(this)
                    card.addView(label(if (usage) getString(R.string.center_map_auto_hide_active)
                        else getString(R.string.center_map_auto_hide_needed), 14, if (usage) MUTED else WARNING))
                }
                if (clusterDisplay != null || adbCluster) {
                    if (DiLink51ClusterLayout.supported() && !adbCluster) {
                        val automatic = DiLink51ClusterLayout.automatic(this)
                        toggle(card, getString(R.string.follow_instrument_theme_and_map_card),
                            getString(R.string.show_the_side_map_only_when_its_card_is_open_and_switch_to), automatic) {
                            DiLink51ClusterLayout.saveAutomatic(this, it)
                            render()
                            reconnectForClusterMap()
                        }
                        val allowed = DiLink51ClusterMonitor.hasAccess(this)
                        card.addView(label(if (allowed) getString(R.string.usage_access_enabled)
                            else getString(R.string.usage_access_setup_needed_for_automatic_mode), 14, if (allowed) MUTED else WARNING))
                        card.addView(button(getString(R.string.automatic_map_setup_adb), false) { showClusterAccessSetup() }, matchButton(10, 56))
                        if (!automatic) {
                            val themes = DiLink51ClusterLayout.Theme.entries
                            choice(card, getString(R.string.instrument_theme), themes.map { it.localizedLabel(this) }, themes.indexOf(DiLink51ClusterLayout.theme(this))) {
                                DiLink51ClusterLayout.saveTheme(this, themes[it])
                                reconnectForClusterMap()
                            }
                            card.addView(label(getString(R.string.manual_mode_match_the_cluster_theme_here_the_map_cannot_fo), 14, MUTED))
                        }
                        val contrasts = DiLink51ClusterLayout.Contrast.entries
                        choice(card, getString(R.string.instrument_contrast), contrasts.map { it.localizedLabel(this) }, contrasts.indexOf(DiLink51ClusterLayout.contrast(this))) {
                            DiLink51ClusterLayout.saveContrast(this, contrasts[it])
                            reconnectForClusterMap()
                        }
                    } else {
                        if (diLink4) clusterSafeAreaControls(card)
                        val sizes = CarPlayClusterDisplay.scalePresets
                        val contents = CarPlayClusterDisplay.Content.entries
                        val content = AirPlayPersistence.loadClusterContent(this)
                        val customCard = CarPlayClusterDisplay.usesCustomTurnCard(content)
                        val officialCardOnly = content == CarPlayClusterDisplay.Content.TURN_CARD
                        choice(card, getString(R.string.dashboard_shows), listOf(
                            getString(R.string.dashboard_content_map),
                            getString(R.string.dashboard_content_turn_card),
                            getString(R.string.dashboard_content_map_with_turn_card),
                            getString(R.string.dashboard_content_map_with_custom_turn_card),
                        ), contents.indexOf(content).coerceAtLeast(0), reconnects = false) {
                            val next = contents[it]
                            val request = ++clusterContentRequestVersion
                            AirPlayPersistence.saveClusterContent(this, next)
                            render()
                            if (content.url != next.url) {
                                // The iPhone's own contents switch live. DiPlay's card over the map, and the
                                // DiLink 5.1 layout (always the map), are set up at connection, so they reconnect.
                                val controller = CarPlayBackgroundSession.snapshot()?.controller
                                if (customCard || CarPlayClusterDisplay.usesCustomTurnCard(next) ||
                                    DiLink51ClusterLayout.supported() || controller == null) {
                                    reconnectForClusterMap()
                                } else controller.showDashboardContent(next.url) { applied ->
                                    runOnUiThread {
                                        if (!applied && request == clusterContentRequestVersion &&
                                            !isFinishing && !isDestroyed &&
                                            AirPlayPersistence.loadClusterContent(this) == next &&
                                            CarPlayBackgroundSession.snapshot()?.controller === controller) {
                                            reconnectForClusterMap()
                                        }
                                    }
                                }
                            }
                        }
                        if (customCard) {
                            card.addView(overlaySliderRow(
                                getString(R.string.turn_card_overlay_size),
                                ClusterTurnCardOverlay.sizePercents,
                                AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(this),
                            ) { it -> getString(R.string.turn_card_overlay_size_option, it) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlaySizePercent(this, v) } })
                            card.addView(overlaySliderRow(
                                getString(R.string.turn_card_overlay_opacity),
                                ClusterTurnCardOverlay.opacityPercents,
                                AirPlayPersistence.loadClusterTurnCardOpacityPercent(this),
                            ) { it -> getString(R.string.turn_card_overlay_opacity_option, it) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOpacityPercent(this, v) } })
                            card.addView(overlaySliderRow(
                                getString(R.string.turn_card_overlay_horizontal),
                                ClusterTurnCardOverlay.xPercents,
                                AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this),
                            ) { it -> overlayOffsetLabel(it, getString(R.string.marker_left), getString(R.string.marker_right), 50) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlayXPercent(this, v) } })
                            card.addView(overlaySliderRow(
                                getString(R.string.turn_card_overlay_vertical),
                                ClusterTurnCardOverlay.yPercents,
                                AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this),
                            ) { it -> overlayOffsetLabel(it, getString(R.string.marker_up), getString(R.string.marker_down), 40) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlayYPercent(this, v) } })
                            card.addView(button(getString(R.string.reset_turn_card_overlay), false) {
                                AirPlayPersistence.saveClusterTurnCardOverlayXPercent(this, ClusterTurnCardOverlay.DEFAULT_X_PERCENT)
                                AirPlayPersistence.saveClusterTurnCardOverlayYPercent(this, ClusterTurnCardOverlay.DEFAULT_Y_PERCENT)
                                render()
                            }, matchButton(10, 56))
                            card.addView(label(getString(R.string.turn_card_overlay_note), 14, MUTED))
                        }
                        val turnCard = officialCardOnly
                        if (!diLink4) {
                            choice(card, getString(if (turnCard) R.string.turn_card_size else R.string.cluster_map_size),
                                listOf(getString(R.string.cluster_size_standard), getString(R.string.cluster_size_larger), getString(R.string.cluster_size_largest)),
                                sizes.indexOf(AirPlayPersistence.loadClusterMapScalePercent(this)).coerceAtLeast(0)) {
                                AirPlayPersistence.saveClusterMapScalePercent(this, sizes[it])
                            }
                        }
                        if (!diLink4 || AirPlayPersistence.loadClusterSafeAreaRect(this) == null) {
                            val across = CarPlayClusterDisplay.horizontalSteps.toList()
                            choice(card, getString(if (turnCard) R.string.turn_card_horizontal else R.string.car_marker_horizontal), across.map { markerStepLabel(it, getString(R.string.marker_left), getString(R.string.marker_right)) },
                                across.indexOf(AirPlayPersistence.loadClusterMarkerHorizontalStep(this)).coerceAtLeast(0)) {
                                AirPlayPersistence.saveClusterMarkerHorizontalStep(this, across[it])
                            }
                            val upDown = CarPlayClusterDisplay.verticalSteps.toList()
                            choice(card, getString(if (turnCard) R.string.turn_card_vertical else R.string.car_marker_vertical), upDown.map { markerStepLabel(it, getString(R.string.marker_up), getString(R.string.marker_down)) },
                                upDown.indexOf(AirPlayPersistence.loadClusterMarkerVerticalStep(this)).coerceAtLeast(0)) {
                                AirPlayPersistence.saveClusterMarkerVerticalStep(this, upDown[it])
                            }
                            card.addView(button(getString(if (turnCard) R.string.reset_turn_card_to_centre else R.string.reset_car_marker_to_centre), false) {
                                AirPlayPersistence.saveClusterMarkerHorizontalStep(this, 0)
                                AirPlayPersistence.saveClusterMarkerVerticalStep(this, 0)
                                render()
                                reconnectForClusterMap()
                            }, matchButton(10, 56))
                        }
                        if (!diLink4) {
                            toggle(card, getString(R.string.dashboard_map_only_in_small_and_full_navi),
                                getString(R.string.dashboard_map_only_in_small_and_full_navi_description),
                                BydOutputSettings.clusterStreamPause(this)) {
                                BydOutputSettings.setClusterStreamPause(this, it)
                                if (it) checkAdbState(mayAsk = true)
                            }
                        }
                        wheelKeyControls(card)
                    }
                }
            }
        }
        if (BydOutputSettings.available(this)) section(content, getString(R.string.byd_navigation), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.navigation_on_hud_and_instrument_cluster),
                getString(R.string.show_phone_navigation_arrows_distance_and_street_names_on),
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
            if (BydOutputSettings.standaloneHudAvailable(this)) {
                toggle(card, getString(R.string.song_on_hud), getString(R.string.song_on_hud_description),
                    BydOutputSettings.hudSong(this)) { BydOutputSettings.setHudSong(this, it) }
            }
            clusterSongSwitch(card)
        }
        section(content, getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions, LynkSettingsGroup.SUPPORT) { card ->
            card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button(getString(R.string.bluetooth_settings), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
            card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
        section(content, getString(R.string.about), R.drawable.ic_dp_about, LynkSettingsGroup.SUPPORT) { card ->
            card.addView(button(getString(R.string.about_diplay), false) { page = "about"; render() }, matchButton(0, 60))
        }
        if (lynkUpdatesEnabled) appUpdateSection(content)
        languageSettings(content)
    }

    private fun about(content: LinearLayout) {
        content.addView(label(getString(R.string.diplay), 40, TEXT, true))
        content.addView(label(getString(R.string.carplay_at_home_in_your_car), 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        if (lynkUpdatesEnabled) appUpdateSection(content)
        if (simpleConnectionFlow) section(content, getString(R.string.lynk_upstream_credit)) { card ->
            card.addView(label(getString(R.string.lynk_upstream_description), 16, MUTED))
            card.addView(button(getString(R.string.lynk_upstream_project), false) {
                openProjectPage("https://github.com/shihabal3amri/DiPlay")
            }, matchButton(16, 56))
            card.addView(button(getString(R.string.lynk_upstream_notices), false) {
                openProjectPage("https://github.com/shihabal3amri/DiPlay/blob/main/docs/THIRD_PARTY_NOTICES.md")
            }, matchButton(10, 56))
        }
        section(content, "${getString(R.string.about_public_preview_prefix)}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }
    }

    private fun openProjectPage(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast(getString(R.string.lynk_upstream_no_browser, url)) }
    }

    private fun appUpdateSection(content: LinearLayout) {
        section(content, getString(R.string.lynk_update_title), group = LynkSettingsGroup.SUPPORT) { card ->
            card.addView(label(getString(R.string.lynk_update_installed, version()), 16, MUTED))
            updateStatus = label("", 16, TEXT).apply { setPadding(0, dp(12), 0, 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            card.addView(updateStatus)
            updateCheckButton = button(getString(R.string.lynk_update_check), false) { checkAppUpdates() }
            card.addView(updateCheckButton, matchButton(16, 56))
            updateDownloadButton = button(getString(R.string.lynk_update_download), true) {
                (updateResult as? LynkAppUpdates.Result.Available)?.let { openProjectPage(it.release.downloadUrl) }
            }
            card.addView(updateDownloadButton, matchButton(10, 56))
            updateNotesButton = button(getString(R.string.lynk_update_notes), false) {
                val release = when (val result = updateResult) {
                    is LynkAppUpdates.Result.Available -> result.release
                    is LynkAppUpdates.Result.Incompatible -> result.release
                    else -> null
                }
                release?.let { openProjectPage(it.releaseUrl) }
            }
            card.addView(updateNotesButton, matchButton(10, 56))
            toggle(card, getString(R.string.lynk_update_auto), getString(R.string.lynk_update_auto_hint), updatePreferences.automatic) {
                updatePreferences.automatic = it
                if (!it) cancelAppUpdateCheck()
            }
            card.addView(button(getString(R.string.lynk_update_website), false) { openProjectPage(LynkAppUpdates.WEBSITE) }, matchButton(10, 56))
            card.addView(button(getString(R.string.lynk_update_source), false) { openProjectPage(LynkAppUpdates.REPOSITORY) }, matchButton(10, 56))
            refreshAppUpdateViews()
        }
    }

    private fun parseUpdateManifest(json: String): LynkAppUpdates.Result = LynkAppUpdates.parse(
        json, packageName, packageManager.getPackageInfo(packageName, 0).longVersionCode, Build.VERSION.SDK_INT,
    )

    private fun checkAppUpdates() {
        if (!lynkUpdatesEnabled || updateRequest != null) return
        val request = LynkUpdateRequest()
        updateRequest = request
        updatePreferences.attempted(System.currentTimeMillis())
        refreshAppUpdateViews()
        updateThread = Thread({
            var manifest: String? = null
            val result = try {
                manifest = request.fetch()
                parseUpdateManifest(manifest)
            } catch (_: java.util.concurrent.CancellationException) {
                return@Thread
            } catch (_: Exception) {
                LynkAppUpdates.Result.Failed
            }
            handler.post {
                if (isDestroyed || isFinishing || updateRequest !== request) return@post
                updateRequest = null
                updateThread = null
                updateResult = result
                if (result != LynkAppUpdates.Result.Failed) manifest?.let { updatePreferences.save(it) }
                refreshAppUpdateViews()
            }
        }, "LynkUpdateCheck").apply { start() }
    }

    private fun cancelAppUpdateCheck() {
        updateRequest?.close()
        updateRequest = null
        updateThread?.interrupt()
        updateThread = null
        refreshAppUpdateViews()
    }

    private fun refreshAppUpdateViews() {
        val checking = updateRequest != null
        val result = updateResult
        updateCheckButton?.isEnabled = !checking
        updateCheckButton?.text = getString(if (checking) R.string.lynk_update_checking else R.string.lynk_update_check)
        updateStatus?.text = when (result) {
            null -> getString(R.string.lynk_update_idle)
            LynkAppUpdates.Result.NoRelease -> getString(R.string.lynk_update_unpublished)
            LynkAppUpdates.Result.Current -> getString(R.string.lynk_update_current)
            LynkAppUpdates.Result.Failed -> getString(R.string.lynk_update_failed)
            is LynkAppUpdates.Result.Incompatible -> getString(R.string.lynk_update_incompatible, result.release.versionName, result.release.minSdk)
            is LynkAppUpdates.Result.Available -> getString(R.string.lynk_update_available, result.release.versionName, result.release.notes)
        }
        updateDownloadButton?.visibility = if (result is LynkAppUpdates.Result.Available) View.VISIBLE else View.GONE
        updateNotesButton?.visibility = if (result is LynkAppUpdates.Result.Available || result is LynkAppUpdates.Result.Incompatible) View.VISIBLE else View.GONE
        updateNotice?.apply {
            visibility = if (result is LynkAppUpdates.Result.Available) View.VISIBLE else View.GONE
            if (result is LynkAppUpdates.Result.Available) text = getString(R.string.lynk_update_notice, result.release.versionName)
        }
    }

    // An opted-in connection prepares the hotspot in the controller instead of stopping at this reminder.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false &&
            !(CarHotspotSettings.enabled(this) && CarHotspotTethering.permitted(this))

    private fun bydAdbSettings(parent: LinearLayout) {
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) return
        if (!CarHotspotSetup.isBydHeadUnit(this)) {
            Log.i("DiPlay-Hotspot", "settings hidden: BYD head unit not detected")
            return
        }
        val controls = column().apply { visibility = View.GONE }
        bydAdbControls = controls
        parent.addView(controls)
        Thread({
            val access = runCatching { CarHotspotSetup.check(applicationContext) }
                .onFailure { Log.w("DiPlay-Hotspot", "settings ADB check failed", it) }
                .getOrDefault(LocalAdb.Access.UNREACHABLE)
            Log.i("DiPlay-Hotspot", "settings eligibility: byd=true adb=$access visible=${CarHotspotSettings.visible(true, access)}")
            runOnUiThread {
                if (bydAdbControls !== controls || isFinishing || isDestroyed) return@runOnUiThread
                if (CarHotspotSettings.visible(true, access)) {
                    controls.visibility = View.VISIBLE
                    renderBydAdbControls(controls, access)
                }
            }
        }, "diplay-hotspot-adb-check").start()
    }

    private fun renderBydAdbControls(controls: LinearLayout, access: LocalAdb.Access) {
        controls.removeAllViews()
        adbSwitches.keys.retainAll(setOf(R.string.open_after_the_car_starts))
        adbStatus = null
        controls.visibility = if (CarHotspotSettings.visible(true, access)) View.VISIBLE else View.GONE
        if (controls.visibility == View.GONE) return
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) {
            controls.visibility = View.GONE
            return
        }
        section(controls, getString(R.string.byd_adb_features), R.drawable.ic_dp_permissions) { card ->
            if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL) {
                adbToggle(card, R.string.auto_car_hotspot_title, R.string.auto_car_hotspot_description,
                    read = { CarHotspotSettings.enabled(this) },
                    permissions = {
                        buildList {
                            add(CarHotspotSetup.Permission.HOTSPOT)
                            if (AirPlayPersistence.loadAutoStartOnBoot(this@DiPlayActivity)) {
                                add(CarHotspotSetup.Permission.BOOT_LAUNCH)
                            }
                        }
                    }) {
                    CarHotspotSettings.setEnabled(this, it)
                    if (!it) startupHotspotCancelled = true
                }
            }
            adbStatus = label(getString(if (access == LocalAdb.Access.READY)
                R.string.adb_access_ready else R.string.adb_not_approved), 14, MUTED).also(card::addView)
        }
    }

    private fun adbToggle(parent: LinearLayout, title: Int, description: Int, read: () -> Boolean,
        needsAdb: () -> Boolean = { true },
        permissions: () -> List<CarHotspotSetup.Permission> = { emptyList() }, save: (Boolean) -> Unit) {
        val control = toggle(parent, getString(title), getString(description), read(),
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()) { enabled ->
            if (updatingAdbSwitches || adbSwitchChangePending) return@toggle
            if (enabled && needsAdb()) requestAdbSwitchChange(permissions()) { save(true) }
            else save(enabled)
        }
        adbSwitches[title] = control to read
    }

    private fun requestAdbSwitchChange(permissions: List<CarHotspotSetup.Permission>, save: () -> Unit) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            updateAdbSwitches()
            return
        }
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val app = applicationContext
        adbSwitchChangePending = true
        updateAdbSwitches()
        adbStatus?.setText(R.string.adb_checking_may_ask)
        if (page == "settings" && bydVehicleAdvancedExpanded) render()
        Thread({
            var access = LocalAdb.Access.UNREACHABLE
            val ready = runCatching {
                access = CarHotspotSetup.grant(app, permissions)
                access == LocalAdb.Access.READY && permissions.all { it.granted(app) }
            }.getOrDefault(false)
            runOnUiThread {
                adbSwitchChangePending = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = if (ready) {
                    if (permissions.isEmpty()) R.string.adb_access_ready else R.string.hotspot_permission_granted
                } else when (access) {
                    LocalAdb.Access.NOT_APPROVED -> R.string.adb_not_approved
                    LocalAdb.Access.UNREACHABLE -> R.string.adb_off
                    LocalAdb.Access.UNSUPPORTED -> R.string.adb_pairing_only
                    else -> R.string.hotspot_permission_failed
                }
                adbStatus?.setText(if (ready) R.string.adb_access_ready else message)
                if (ready) save()
                updateAdbSwitches()
                toast(getString(message))
                runPendingAutomaticVehicleValidation()
                // Re-enable the vehicle controls disabled while this authorization was outstanding.
                if (page == "settings" && bydVehicleAdvancedExpanded) render()
            }
        }, "diplay-adb-switch").start()
    }

    private fun updateAdbSwitches() {
        updatingAdbSwitches = true
        for ((control, read) in adbSwitches.values) {
            control.isChecked = read()
            control.isEnabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()
        }
        updatingAdbSwitches = false
    }

    private fun vehicleAdbWorkInProgress(): Boolean =
        adbCheckInProgress || vehicleProbeAuthorizationInProgress || vehicleProbeInProgress

    private fun startCarHotspotOnLaunch() {
        if (!startupHotspotEligible()) return
        val app = applicationContext
        Thread({
            val result = CarHotspotTethering.enable(app,
                isCancelled = { startupHotspotCancelled || !startupHotspotEligible() },
                log = { Log.i("DiPlay-Hotspot", it) },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || result == CarHotspotTethering.Result.CANCELLED) return@runOnUiThread
                hotspotStartupResult = result
                if (page == "home") render()
            }
        }, "diplay-hotspot-startup").start()
    }

    private fun startupHotspotEligible(): Boolean =
        CarHotspotSetup.shouldStartOnLaunch(applicationContext, CarPlayBackgroundSession.hasSession())

    private fun hotspotResultText(result: CarHotspotTethering.Result): String = getString(when (result) {
        CarHotspotTethering.Result.READY -> R.string.hotspot_control_on
        CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.hotspot_control_missing
        CarHotspotTethering.Result.UNSUPPORTED -> R.string.hotspot_control_unsupported
        CarHotspotTethering.Result.TIMED_OUT -> R.string.hotspot_control_timeout
        else -> R.string.hotspot_control_failed
    })

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (packageManager.resolveActivity(wifi, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        openSystem(wifi)
    }

    private fun connectionSetup(content: LinearLayout) {
        if (simpleConnectionFlow) { simpleConnectionSetup(content); return }
        content.addView(label(getString(R.string.connection_setup), 34, TEXT, true))
        content.addView(label(getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.s_1_choose_your_connection)) { card -> wirelessLinkControls(card) }
        section(content, getString(R.string.s_2_pair_your_iphone)) { card ->
            card.addView(label(getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car), 16, MUTED))
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
            card.addView(button(getString(R.string.review_app_permissions), false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12, 60))
        }
        section(content, getString(R.string.s_3_connect)) { card ->
            card.addView(label(getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the), 16, MUTED))
            card.addView(button(getString(R.string.connect_phone), true) { connect(true) }, matchButton(12, 60))
        }
        section(content, getString(R.string.prefer_a_cable)) { card ->
            card.addView(label(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 16, MUTED))
            card.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(12, 60))
        }
    }

    private fun simpleConnectionSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.link_setup), 28, TEXT, true))
        content.addView(label(getString(R.string.lynk_hotspot_intro), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(20)) })
        val wide = resources.configuration.screenWidthDp >= 840
        val panels = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val hotspot = card()
        hotspot.addView(label(getString(R.string.lynk_hotspot_step), 14, ACCENT, true))
        val supportsAutomatic = com.shilapi.xcertplay.network.LynkLocalHotspot.supported(this)
        if (supportsAutomatic) {
            hotspot.addView(label(getString(R.string.auto_hotspot_intro), 17, TEXT).apply { setPadding(0, dp(16), 0, dp(12)) })
            hotspot.addView(button(getString(R.string.auto_hotspot_start), automaticHotspot) {
                pendingCarHotspotSetup = false
                applyWirelessLink(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)
            }, matchButton(8, 60))
            hotspot.addView(label(getString(R.string.auto_hotspot_notice), 15, MUTED).apply { setPadding(0, dp(12), 0, dp(8)) })
            hotspot.addView(button(getString(R.string.auto_hotspot_manual), !automaticHotspot) {
                pendingCarHotspotSetup = false
                applyWirelessLink(WirelessHotspotMode.MANUAL)
            }, matchButton(8, 56))
        }
        if (!automaticHotspot) {
            hotspot.addView(label(getString(R.string.link_setup_hint), 17, TEXT).apply { setPadding(0, dp(16), 0, dp(12)) })
            hotspot.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(8, 60))
            hotspot.addView(button(getString(R.string.link_save_connect), true) { saveHotspotAndConnect() }, matchButton(12, 64))
            if (storedSsid().isNotEmpty()) hotspot.addView(label(getString(R.string.link_saved_hotspot, storedSsid()), 15, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
        val phone = card()
        phone.addView(label(getString(R.string.lynk_phone_step), 14, ACCENT, true))
        phone.addView(label(if (DiPlayPreferences.phoneAddress(this) == null) getString(R.string.link_pair_hint)
            else getString(R.string.lynk_remembered_phone, DiPlayPreferences.phoneName(this)), 17, TEXT).apply { setPadding(0, dp(16), 0, dp(12)) })
        phone.addView(button(getString(R.string.bluetooth_settings), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(8, 60))
        phone.addView(button(getString(R.string.link_local_bt_title), false) { editHeadUnitBluetoothAddress() }, matchButton(12, 60))
        panels.addView(hotspot, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(18) } else LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        panels.addView(phone, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        content.addView(panels)
        if (!supportsAutomatic) content.addView(label(getString(R.string.link_auto_hotspot_limit), 14, MUTED).apply { setPadding(0, dp(20), 0, 0) })
    }

    private fun saveHotspotAndConnect() {
        askHotspotCredentials { ssid, password ->
            saveHotspotCredentials(ssid, password)
            AirPlayPersistence.saveWirelessHotspotMode(this, WirelessHotspotMode.MANUAL)
            pendingCarHotspotSetup = false
            page = "home"; render(); connect(true)
        }
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI)
        val titles = listOf(getString(R.string.built_in_car_hotspot), getString(R.string.wifi_direct), getString(R.string.existing_wifi_title))
        val descriptions = listOf(
            getString(R.string.hotspot_mode_manual_desc),
            getString(R.string.hotspot_mode_p2p_desc),
            getString(R.string.existing_wifi_description)
        )
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEachIndexed { index, candidate ->
            val option = column()
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
            option.addView(button("${if (mode == candidate) "✓  " else ""}${titles[index]}", mode == candidate) {
                if (candidate == WirelessHotspotMode.MANUAL) {
                    pendingCarHotspotSetup = true
                    render()
                } else if (candidate == WirelessHotspotMode.EXISTING_WIFI) {
                    askHotspotCredentials(existingWifi = true) { ssid, password ->
                        AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                        pendingCarHotspotSetup = false
                        applyWirelessLink(candidate)
                    }
                } else {
                    pendingCarHotspotSetup = false
                    applyWirelessLink(candidate)
                }
            }, matchButton(12, 60))
            option.addView(label(descriptions[index], 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        if (mode == WirelessHotspotMode.MANUAL) {
            parent.addView(label(getString(R.string.hotspot_setup), 22, TEXT, true))
            parent.addView(label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            parent.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            parent.addView(button(if (pendingCarHotspotSetup) getString(R.string.save_hotspot_details_and_use_this_mode) else "${getString(R.string.edit_saved_hotspot_prefix)}${storedSsid()}", false) {
                askHotspotCredentials { ssid, password ->
                    saveHotspotCredentials(ssid, password)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                }
            }, matchButton(12, 60))
            parent.addView(label(if (pendingCarHotspotSetup) getString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode) else if (carHotspotOff()) getString(R.string.hotspot_details_off) else getString(R.string.hotspot_details_saved), 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
        } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
            parent.addView(label(getString(R.string.existing_wifi_instructions), 16, MUTED))
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
            parent.addView(button(getString(R.string.existing_wifi_details), false) {
                askHotspotCredentials(existingWifi = true) { ssid, password ->
                    AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                    toast(getString(R.string.saved_for_your_next_connection))
                }
            }, matchButton(12, 60))
        } else {
            parent.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            wifiDirectChannelControl(parent)
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    private fun wifiDirectChannelLabel(channel: Int): String = if (channel == WifiP2pChannels.AUTO) {
        getString(R.string.auto)
    } else {
        getString(R.string.wifi_direct_channel_choice, channel,
            getString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))
    }

    private fun wifiDirectChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.wifi_direct_channel_summary, wifiDirectChannelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadWifiP2pPreferredChannel(this)), false) {}
        control.setOnClickListener {
            val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.channels
            val current = AirPlayPersistence.loadWifiP2pPreferredChannel(this)
            var selection = current
            AlertDialog.Builder(this).setTitle(R.string.wifi_direct_channel_title)
                .setSingleChoiceItems(choices.map(::wifiDirectChannelLabel).toTypedArray(),
                    choices.indexOf(current)) { _, which -> selection = choices[which] }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (selection != current) {
                        AirPlayPersistence.saveWifiP2pPreferredChannel(this, selection)
                        control.text = summary(selection)
                        toast(getString(R.string.saved_for_your_next_connection))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parent.addView(control, matchButton(12, 60))
        parent.addView(label(getString(R.string.wifi_direct_channel_description), 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
    }

    private fun mediaChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_media_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadMediaAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_media_channel_label),
                current = current,
                navigation = false,
                onApply = { value -> applyMediaChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(0, 60))
    }

    private fun navigationChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_nav_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadNavigationAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_nav_channel_label),
                current = current,
                navigation = true,
                onApply = { value -> applyNavigationChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(label(getString(R.string.contrib_audio_home_nav_channel_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    private fun showChannelDialog(title: String, current: Int, navigation: Boolean, onApply: (Int) -> Unit) {
        val preview = AudioChannelPreview { channel ->
            toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = current.coerceIn(channels.first, channels.last)
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_and_reconnect) else getString(R.string.save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun channelLabel(value: Int): String = value.toString()

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(existingWifi: Boolean = false, done: (String, String) -> Unit) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(if (existingWifi) R.string.existing_wifi_instructions else R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_ssid else R.string.hotspot_name)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiSsid(this@DiPlayActivity) else storedSsid())
            setSingleLine()
        }
        val password = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_password else R.string.hotspot_password)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiPassphrase(this@DiPlayActivity) else storedPassword())
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(ssid); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(getString(if (existingWifi) R.string.existing_wifi_details else R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = if (existingWifi) ssid.text.toString() else ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun markerStepLabel(step: Int, negative: String, positive: String): String = when {
        step == 0 -> getString(R.string.marker_centre_default)
        step < 0 -> "$negative ${-step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
        else -> "$positive ${step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
    }

    /** A 2%-step slider row for overlay placement; every step saves, so the card moves live. */
    private fun overlaySliderRow(title: String, values: List<Int>, current: Int, describe: (Int) -> String): OverlaySliderRow =
        OverlaySliderRow(this, title, values, current, describe)

    private inner class OverlaySliderRow(
        context: android.content.Context,
        title: String,
        private val steps: List<Int>,
        current: Int,
        private val describe: (Int) -> String,
    ) : LinearLayout(context) {
        var onSave: (Int) -> Unit = {}
        val slider: SeekBar

        init {
            orientation = VERTICAL
            val valueView = label(describe(current), 16, ACCENT, true)
            val head = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
            head.addView(label(title, 16, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(valueView)
            addView(head)
            slider = SeekBar(context).apply {
                max = steps.lastIndex
                progress = steps.indexOf(current).coerceIn(steps.indices)
                minHeight = dp(44)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = steps[progress.coerceIn(steps.indices)]
                        valueView.text = describe(value)
                        if (fromUser) onSave(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            addView(slider, LinearLayout.LayoutParams(-1, dp(44)))
        }
    }

    private fun overlayOffsetLabel(percent: Int, negative: String, positive: String, centre: Int): String {
        val delta = percent - centre
        return when {
            delta == 0 -> getString(R.string.marker_centre_default)
            delta < 0 -> "$negative ${-delta} %"
            else -> "$positive $delta %"
        }
    }

    private fun showClusterAccessSetup() {
        val command = "adb shell appops set $packageName GET_USAGE_STATS allow"
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.one_time_setup_on_this_car), 20, TEXT, true))
        body.addView(label(getString(R.string.usage_access_lets_diplay_follow_the_instrument_theme_and_m), 15, MUTED))
        body.addView(label(getString(R.string.s_1_connect_a_computer_with_adb_installed_to_the_car_using), 16, TEXT))
        body.addView(label(command, 16, TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(16))
        })
        body.addView(button(getString(R.string.copy_command), false) {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.clipboard_usage_access), command))
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(0, 56))
        body.addView(label(getString(R.string.cluster_adb_multi_device, packageName), 14, MUTED))
        body.addView(label(getString(R.string.s_3_tap_check_and_enable_below_this_enables_the_cluster_ma), 16, TEXT))
        val status = label(if (DiLink51ClusterMonitor.hasAccess(this)) getString(R.string.permission_enabled_ready) else getString(R.string.permission_not_enabled), 16, TEXT)
        body.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.automatic_cluster_map_setup))
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton(getString(R.string.close), null)
            .setPositiveButton(getString(R.string.check_and_enable), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (DiLink51ClusterMonitor.hasAccess(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, true)
                    DiLink51ClusterLayout.saveAutomatic(this, true)
                    dialog.dismiss()
                    render()
                    toast(getString(R.string.automatic_map_enabled_open_the_cluster_map_card_or_select))
                    reconnectForClusterMap()
                } else {
                    status.text = getString(R.string.still_waiting_for_usage_access_check_that_the_command_ran)
                }
            }
        }
        dialog.show()
    }

    /** Steering-wheel keys for the dashboard map zoom and the CarPlay joystick: the switches, the key service and the keys. */
    private fun wheelKeyControls(card: LinearLayout) {
        toggle(card, getString(R.string.wheel_map_zoom), getString(R.string.wheel_map_zoom_description),
            WheelZoomSettings.enabled(this)) {
            WheelZoomSettings.setEnabled(this, it)
            render()
        }
        toggle(card, getString(R.string.wheel_joystick), getString(R.string.wheel_joystick_description),
            WheelZoomSettings.joystick(this)) {
            WheelZoomSettings.setJoystick(this, it)
            render()
        }
        val zoom = WheelZoomSettings.enabled(this)
        val joystick = WheelZoomSettings.joystick(this)
        if (!zoom && !joystick) return
        val connected = WheelKeyService.connected()
        card.addView(label(getString(when {
            connected -> R.string.wheel_keys_service_on
            WheelKeyService.enabledInSettings(this) -> R.string.wheel_keys_service_starting
            else -> R.string.wheel_keys_service_off
        }), 14, if (connected) MUTED else WARNING))
        if (!connected) {
            card.addView(button(getString(R.string.wheel_keys_enable_adb), false) {
                Thread({
                    val access = WheelKeyService.enableOverAdb(this)
                    runOnUiThread {
                        if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                            toast(getString(R.string.wheel_keys_adb_failed, access.name))
                        }
                        render()
                    }
                }, "diplay-wheel-keys-enable").start()
            }, matchButton(10, 56))
            card.addView(button(getString(R.string.wheel_keys_open_settings), false) {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure { toast(getString(R.string.wheel_keys_no_settings)) }
            }, matchButton(10, 56))
        }
        if (zoom) {
            val behaviours = WheelZoomSettings.Behaviour.entries
            choice(card, getString(R.string.wheel_zoom_behaviour),
                listOf(getString(R.string.wheel_zoom_behaviour_toggle), getString(R.string.wheel_zoom_behaviour_timed)),
                behaviours.indexOf(WheelZoomSettings.behaviour(this)), reconnects = false) {
                WheelZoomSettings.setBehaviour(this, behaviours[it])
            }
        }
        if (joystick) {
            toggle(card, getString(R.string.wheel_joystick_auto_off), getString(R.string.wheel_joystick_auto_off_description),
                WheelZoomSettings.joystickAutoOff(this)) { WheelZoomSettings.setJoystickAutoOff(this, it) }
        }
        for (role in WheelZoomSettings.Role.entries) {
            // A key can serve both: the mode key goes back in the joystick, the zoom keys move it.
            val zoomName = when (role) {
                WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_mode
                WheelZoomSettings.Role.ZOOM_IN -> R.string.wheel_key_role_zoom_in
                WheelZoomSettings.Role.ZOOM_OUT -> R.string.wheel_key_role_zoom_out
                else -> null
            }.takeIf { zoom }
            val joystickName = when (role) {
                WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_back
                WheelZoomSettings.Role.ZOOM_IN, WheelZoomSettings.Role.PREVIOUS -> R.string.wheel_key_role_previous
                WheelZoomSettings.Role.ZOOM_OUT, WheelZoomSettings.Role.NEXT -> R.string.wheel_key_role_next
                WheelZoomSettings.Role.JOYSTICK -> R.string.wheel_key_role_joystick
                WheelZoomSettings.Role.SELECT -> R.string.wheel_key_role_select
            }.takeIf { joystick }
            val names = listOfNotNull(zoomName, joystickName)
            if (names.isEmpty()) continue
            val name = names.joinToString(" · ") { getString(it) }
            lateinit var assign: android.widget.Button
            assign = button(getString(R.string.wheel_key_assign, name, WheelZoomSettings.key(this, role).toString()), false) {
                val started = WheelKeyService.learn(role, cancelled = {
                    runOnUiThread { assign.text = getString(R.string.wheel_key_assign, name, WheelZoomSettings.key(this, role).toString()) }
                }) { _, key ->
                    runOnUiThread { assign.text = getString(R.string.wheel_key_assign, name, key.toString()) }
                }
                if (started) assign.text = getString(R.string.wheel_key_press, name)
                else toast(getString(R.string.wheel_keys_service_off))
            }
            card.addView(assign, matchButton(10, 56))
        }
    }

    /** The 0.2.9 Dashboard song setting, shown once: in the BYD navigation card, or under Advanced vehicle data. */
    private fun clusterSongSwitch(card: LinearLayout) {
        toggle(card, getString(R.string.cluster_song),
            getString(R.string.cluster_song_description),
            BydOutputSettings.clusterSong(this), enabled = !adbSwitchChangePending) {
            BydOutputSettings.setClusterSong(this, it)
            if (it) checkAdbState(mayAsk = true)
            BydNavigationOutputs.clusterSongChanged(it)
        }
        toggle(card, getString(R.string.cluster_song_on_change), getString(R.string.cluster_song_on_change_description),
            BydOutputSettings.clusterSongOnChange(this), enabled = !adbSwitchChangePending) {
            BydOutputSettings.setClusterSongOnChange(this, it)
            BydNavigationOutputs.clusterSongOnChangeChanged()
        }
    }

    private fun advancedVehicleData(card: LinearLayout) {
        val legacyMode = BydOutputSettings.legacyVehicleProbe(this)
        card.addView(label(getString(R.string.advanced_vehicle_data_description), 14, MUTED)
            .apply { setPadding(0, dp(12), 0, 0) })
        choice(
            card,
            getString(R.string.vehicle_data_mode),
            listOf(
                getString(R.string.vehicle_data_mode_default),
                getString(R.string.vehicle_data_mode_legacy),
            ),
            if (legacyMode) 1 else 0,
            reconnects = false,
            announcesReconnect = vehicleDataSwitchesOn(),
            // A switch during ADB work would be dropped by the running step.
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress(),
        ) { selectVehicleDataMode(it == 1) }
        if (!legacyMode) {
            defaultVehicleData(card)
            return
        }
        val capabilities = displayedVehicleCapabilities()
        val adbText = when {
            vehicleProbeAuthorizationInProgress -> getString(R.string.adb_checking_may_ask)
            adbCheckInProgress -> getString(if (adbCheckMayAsk)
                R.string.adb_checking_may_ask else R.string.adb_checking)
            vehicleProbeInProgress || automaticVehicleValidationInProgress ->
                getString(R.string.probing_vehicle_data)
            adbCheckFailed -> getString(R.string.adb_check_failed)
            adbAccessState == null && capabilities != null -> getString(R.string.vehicle_probe_saved_automatic)
            adbAccessState == null -> getString(R.string.adb_not_checked)
            else -> adbLinkStatusText(requireNotNull(adbAccessState))
        }
        val healthy = capabilities != null && !adbCheckFailed && vehicleProbeOutcome?.error == null &&
            pendingVehicleLostFields.isEmpty() &&
            (adbAccessState == null || adbAccessState == BydAdbAccess.State.READY)
        card.addView(label(adbText, 14, if (healthy) MUTED else WARNING)
            .apply { setPadding(0, dp(12), 0, 0) })
        if (capabilities != null) {
            showVehicleProbeResults(card, capabilities)
            showVehicleDataSettings(card, capabilities)
        }
        if (vehicleProbeAuthorizationInProgress || adbCheckInProgress ||
            vehicleProbeInProgress || automaticVehicleValidationInProgress) return
        vehicleProbeOutcome?.error?.let {
            card.addView(label(getString(R.string.vehicle_probe_failed, it), 14, WARNING)
                .apply { setPadding(0, dp(10), 0, 0) })
        }
        if (pendingVehicleLostFields.isNotEmpty()) {
            card.addView(label(
                getString(
                    R.string.vehicle_probe_lost_saved_fields,
                    localizedVehicleFields(pendingVehicleLostFields),
                ),
                14,
                WARNING,
            ).apply { setPadding(0, dp(10), 0, 0) })
            card.addView(button(getString(R.string.replace_saved_vehicle_data_anyway), false) {
                replaceSavedVehicleDataAnyway()
            }, matchButton(10, 56))
        }
        val needsUserAction = capabilities == null || adbCheckFailed || vehicleProbeOutcome?.error != null ||
            pendingVehicleLostFields.isNotEmpty() ||
            adbAccessState in setOf(BydAdbAccess.State.NOT_APPROVED, BydAdbAccess.State.ADB_OFF, BydAdbAccess.State.PAIRING_ONLY)
        if (needsUserAction) {
            val title = if (adbAccessState == BydAdbAccess.State.NOT_APPROVED) {
                R.string.request_adb_authorization
            } else if (capabilities == null) {
                R.string.probe_adb_and_vehicle_data
            } else {
                R.string.probe_vehicle_data_again
            }
            card.addView(button(getString(title), false) {
                probeVehicleData(mayAsk = true)
            }.apply { isEnabled = !adbSwitchChangePending && !adbCheckInProgress }, matchButton(10, 56))
        }
    }

    private fun defaultVehicleData(card: LinearLayout) {
        val adbText = when {
            vehicleProbeAuthorizationInProgress -> getString(R.string.adb_checking_may_ask)
            adbCheckInProgress -> getString(if (adbCheckMayAsk)
                R.string.adb_checking_may_ask else R.string.adb_checking)
            vehicleProbeInProgress -> getString(R.string.probing_vehicle_data)
            adbCheckFailed -> getString(R.string.adb_check_failed)
            adbAccessState == null -> getString(R.string.adb_not_checked)
            else -> adbLinkStatusText(requireNotNull(adbAccessState))
        }
        val healthy = !adbCheckFailed && vehicleProbeOutcome?.error == null &&
            (adbAccessState == null || adbAccessState == BydAdbAccess.State.READY)
        card.addView(label(adbText, 14, if (healthy) MUTED else WARNING)
            .apply { setPadding(0, dp(12), 0, 0) })
        val busy = vehicleProbeAuthorizationInProgress || adbCheckInProgress || vehicleProbeInProgress
        if (!busy && adbAccessState == BydAdbAccess.State.READY) {
            defaultVehicleStatus?.let { showDefaultVehicleReadings(card, it) }
        }
        vehicleProbeOutcome?.error?.let {
            card.addView(label(getString(R.string.vehicle_probe_failed, it), 14, WARNING)
                .apply { setPadding(0, dp(10), 0, 0) })
        }
        showVehicleDataSettings(card, capabilities = null)
        if (!busy) {
            card.addView(button(getString(R.string.check_adb_access), false) {
                checkAdbState(mayAsk = true)
            }.apply { isEnabled = !adbSwitchChangePending }, matchButton(10, 56))
        }
    }

    /** The last default-mode reads; a value an enabled switch needs is a warning when missing. */
    private fun showDefaultVehicleReadings(card: LinearLayout, status: BydAdbAccess.Status) {
        fun reading(value: String?, unreadable: Int, needed: Boolean) {
            val text = value ?: getString(unreadable).takeIf { needed } ?: return
            card.addView(label(text, 14, if (value != null) MUTED else WARNING))
        }
        val battery = status.batteryPercent?.let { percent ->
            status.rangeKm?.let { getString(R.string.adb_battery_reading, percent.roundToInt(), it) }
        }
        reading(battery, R.string.adb_battery_unreadable, BydOutputSettings.batteryToIphone(this))
        reading(status.speedKmh?.let { getString(R.string.adb_vehicle_speed_reading, it.roundToInt()) },
            R.string.adb_vehicle_speed_unreadable, BydOutputSettings.wheelSpeedToIphone(this))
        reading(status.gear?.let { getString(R.string.adb_vehicle_gear_reading, it.letter.toString()) },
            R.string.adb_vehicle_gear_unreadable,
            BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this))
    }

    private fun selectVehicleDataMode(legacyMode: Boolean) {
        if (adbSwitchChangePending) return
        // A stale mode dialog must also invalidate a probe when the Boolean stays the same.
        synchronized(vehicleOperationLock) { vehicleProbeGeneration++ }
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        if (!legacyMode) {
            cancelAutomaticVehicleValidationForUserOperation(resumeAfter = false)
            synchronized(vehicleOperationLock) {
                BydOutputSettings.setLegacyVehicleProbe(this, false)
            }
            vehicleProbeOutcome = null
            pendingVehicleReplacement = null
            pendingVehicleLostFields = emptySet()
            defaultVehicleStatus = null
            render()
            // The session was built from the legacy probe; apply once the default reads succeed.
            vehicleDataReconnectPending = vehicleDataSwitchesOn()
            if (vehicleDataReconnectPending) checkAdbState(mayAsk = true)
            return
        }
        if (BydVehicleFieldStore.load(this) == null) {
            probeVehicleData(mayAsk = true, activateLegacyModeOnSuccess = true)
            return
        }
        synchronized(vehicleOperationLock) {
            BydOutputSettings.setLegacyVehicleProbe(this, true)
        }
        vehicleProbeOutcome = null
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        scheduleAutomaticVehicleValidation()
        render()
        // With every vehicle-data switch off, the mode changes nothing CarPlay was told.
        if (vehicleDataSwitchesOn()) reconnectForVehicleSetting()
    }

    // Probe results are saved before they are shown, so the saved snapshot is the newest without
    // trusting the clock.
    private fun displayedVehicleCapabilities(): BydVehicleCapabilities? =
        BydVehicleFieldStore.load(this) ?: vehicleProbeOutcome?.capabilities

    private fun localizedVehicleFields(fields: Set<BydVehicleField>): String = fields.map { field ->
        getString(when (field) {
            BydVehicleField.SPEED -> R.string.vehicle_field_speed
            BydVehicleField.GEAR -> R.string.vehicle_field_gear
            BydVehicleField.SOC, BydVehicleField.RANGE, BydVehicleField.REMAINING_KWH ->
                R.string.vehicle_field_battery
            BydVehicleField.BMS_STATE -> R.string.vehicle_field_charging
        })
    }.distinct().joinToString()

    private fun replaceSavedVehicleDataAnyway() {
        val candidate = pendingVehicleReplacement ?: return
        // Never over a snapshot saved since the comparison; a failed write is shown, not thrown.
        val error = runCatching {
            BydVehicleFieldStore.replaceAnyway(applicationContext, pendingVehicleReplacementExpected, candidate)
        }.fold(
            onSuccess = { replaced -> if (replaced) null else getString(R.string.vehicle_probe_snapshot_changed) },
            onFailure = { it.message ?: it.javaClass.simpleName },
        )
        Log.i(BYD_VEHICLE_TAG, "user replaced saved vehicle data error=${error ?: "none"}")
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        vehicleProbeOutcome = error?.let { BydVehicleProbeOutcome(BydAdbAccess.State.READY, error = it) }
        render()
    }

    private fun showVehicleProbeResults(card: LinearLayout, capabilities: BydVehicleCapabilities) {
        val catalogFields = capabilities.fields.values.count { it.address?.source == BydFieldSource.FIRMWARE }
        card.addView(label(getString(if (capabilities.catalogAvailable)
            R.string.vehicle_probe_catalog_ready else R.string.vehicle_probe_catalog_fallback, catalogFields),
            14, if (capabilities.catalogAvailable) MUTED else WARNING).apply { setPadding(0, dp(14), 0, 0) })

        val speed = capabilities.result(BydVehicleField.SPEED)
        val speedValue = speed.value
        val speedText = when {
            speedValue != null -> getString(R.string.adb_vehicle_speed_reading, speedValue.roundToInt())
            speed.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_speed))
            else -> getString(R.string.adb_vehicle_speed_unreadable)
        }
        card.addView(label(speedText, 14, if (speed.supported) MUTED else WARNING))
        val gear = capabilities.result(BydVehicleField.GEAR)
        val gearValue = gear.value
        val gearText = when {
            gearValue != null -> getString(R.string.adb_vehicle_gear_reading, gearLetter(gearValue.toInt()))
            gear.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_gear))
            else -> getString(R.string.adb_vehicle_gear_unreadable)
        }
        card.addView(label(gearText, 14, if (gear.supported) MUTED else WARNING))

        if (capabilities.batterySupported) {
            val percentValue = capabilities.result(BydVehicleField.SOC).value
            val rangeValue = capabilities.result(BydVehicleField.RANGE).value
            val energy = capabilities.result(BydVehicleField.REMAINING_KWH).value
            card.addView(label(if (percentValue == null || rangeValue == null) {
                getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_battery))
            } else if (energy != null) {
                getString(R.string.vehicle_probe_battery_reading,
                    percentValue.roundToInt(), rangeValue.roundToInt(), energy)
            } else {
                getString(R.string.vehicle_probe_battery_without_energy,
                    percentValue.roundToInt(), rangeValue.roundToInt())
            }, 14, if (energy != null || percentValue == null) MUTED else WARNING))
        } else {
            card.addView(label(getString(R.string.adb_battery_unreadable), 14, WARNING))
        }
        val charging = capabilities.result(BydVehicleField.BMS_STATE)
        val chargingValue = charging.value
        val chargingText = when {
            chargingValue != null -> getString(R.string.vehicle_probe_charging_state, chargingValue.toInt())
            charging.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_charging))
            else -> getString(R.string.vehicle_probe_charging_unreadable)
        }
        card.addView(label(chargingText, 14, if (charging.supported) MUTED else WARNING))
    }

    private fun showVehicleDataSettings(card: LinearLayout, capabilities: BydVehicleCapabilities?) {
        if (capabilities == null || capabilities.batterySupported) {
            toggle(card, getString(R.string.car_battery_for_the_iphone),
                getString(R.string.car_battery_for_the_iphone_description),
                BydOutputSettings.batteryToIphone(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setBatteryToIphone(this, it)
                onVehicleDataSettingChanged(it)
            }
            val connectors = EvChargingConnectors.entries
            choice(card, getString(R.string.charging_connectors), connectors.map { it.localizedLabel(this) },
                connectors.indexOf(BydOutputSettings.chargingConnectors(this))) {
                BydOutputSettings.setChargingConnectors(this, connectors[it])
            }
            val lowCharge = BydOutputSettings.lowChargePresets
            choice(card, getString(R.string.low_charge_warning), lowCharge.map {
                    getString(if (it == BydOutputSettings.DEFAULT_LOW_CHARGE_PERCENT)
                        R.string.percent_default else R.string.percent_value, it)
                }, lowCharge.indexOf(BydOutputSettings.lowChargePercent(this)).coerceAtLeast(0), reconnects = false) {
                BydOutputSettings.setLowChargePercent(this, lowCharge[it])
            }
        }
        if (capabilities == null || capabilities.motionSupported) {
            toggle(card, getString(R.string.wheel_speed_for_tunnels),
                getString(R.string.wheel_speed_for_tunnels_description),
                BydOutputSettings.wheelSpeedToIphone(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setWheelSpeedToIphone(this, it)
                onVehicleDataSettingChanged(it)
            }
        }
        if (capabilities == null || capabilities.gearSupported) {
            toggle(card, getString(R.string.video_while_parked),
                getString(R.string.video_while_parked_description),
                BydOutputSettings.videoWhileParked(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setVideoWhileParked(this, it)
                onVehicleDataSettingChanged(it)
            }
        }
    }

    private fun onVehicleDataSettingChanged(enabled: Boolean) {
        if (!enabled) {
            reconnectForVehicleSetting()
        } else if (BydOutputSettings.legacyVehicleProbe(this)) {
            scheduleAutomaticVehicleValidation()
            reconnectForVehicleSetting()
        } else {
            // Default mode applies the switch once a check reads what the enabled switches need.
            vehicleDataReconnectPending = true
            checkAdbState(mayAsk = true)
        }
    }

    // The approval dialog can open only after an explicit user action.
    private fun checkAdbState(mayAsk: Boolean) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) return
        val legacy = BydOutputSettings.legacyVehicleProbe(this)
        // Only a validation this check interrupts runs again; a Dashboard switch needs no vehicle check.
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val generation = ++adbCheckGeneration
        adbCheckInProgress = true
        adbCheckMayAsk = mayAsk
        adbCheckFailed = false
        render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-adb-state") {
            val result = runCatching {
                if (legacy) {
                    BydAdbAccess.Status(backend.checkState(applicationContext, mayAsk))
                } else {
                    // The default battery path must publish its first sample before CarPlay reconnects.
                    backend.check(applicationContext, mayAsk)
                }
            }
            runOnUiThread {
                val current = generation == adbCheckGeneration
                if (current) {
                    adbCheckInProgress = false
                    adbCheckMayAsk = false
                }
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                adbCheckFailed = result.isFailure
                val status = result.getOrNull()
                adbAccessState = status?.state
                if (!legacy) defaultVehicleStatus = status
                if (!legacy && adbAccessState == BydAdbAccess.State.READY) {
                    vehicleProbeOutcome = null
                }
                render()
                // Unreadable data keeps the current connection; the page shows what is missing.
                if (!legacy && vehicleDataReconnectPending && status?.state == BydAdbAccess.State.READY &&
                    enabledVehicleDataReadable(null, status)) {
                    vehicleDataReconnectPending = false
                    reconnectForVehicleSetting()
                }
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    /** First probe or user retry: one click handles ADB authorization, probing and persistence. */
    private fun probeVehicleData(mayAsk: Boolean) =
        probeVehicleData(mayAsk, activateLegacyModeOnSuccess = false)

    private fun probeVehicleData(mayAsk: Boolean, activateLegacyModeOnSuccess: Boolean) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) return
        val expectedLegacyMode = BydOutputSettings.legacyVehicleProbe(this)
        val expectedSnapshot = cancelAutomaticVehicleValidationForUserOperation(resumeAfter = false)
        val generation = ++vehicleProbeGeneration
        vehicleProbeAuthorizationInProgress = mayAsk
        vehicleProbeInProgress = !mayAsk
        adbCheckFailed = false
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        Log.i(BYD_VEHICLE_TAG, "user vehicle probe starting mayAsk=$mayAsk")
        render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-byd13-probe") {
            val app = applicationContext
            val attempt = runCatching {
                val access = backend.checkState(app, mayAsk)
                if (access != BydAdbAccess.State.READY) {
                    VehicleProbeAttempt(BydVehicleProbeOutcome(access))
                } else {
                    runOnUiThread {
                        if (generation == vehicleProbeGeneration && !isFinishing && !isDestroyed) {
                            vehicleProbeAuthorizationInProgress = false
                            vehicleProbeInProgress = true
                            render()
                        }
                    }
                    var candidate = backend.probe(app, persist = false)
                    if (candidate.access == BydAdbAccess.State.NOT_APPROVED) {
                        // adbd confirms "Always allow" before it saves the key, so this new connection can be early.
                        Log.i(BYD_VEHICLE_TAG, "probe connection not approved yet; retrying once")
                        Thread.sleep(ADB_KEY_SAVE_WAIT_MILLIS)
                        candidate = backend.probe(app, persist = false)
                    }
                    val candidateCapabilities = candidate.capabilities
                    when {
                        candidate.access == BydAdbAccess.State.NOT_APPROVED -> VehicleProbeAttempt(
                            outcome = candidate,
                            allowedOnlyOnce = true,
                        )
                        candidateCapabilities == null -> VehicleProbeAttempt(candidate)
                        else -> {
                            // Invalidation must guard persistence/publication, not only the UI callback.
                            val replacement = synchronized(vehicleOperationLock) {
                                if (generation != vehicleProbeGeneration ||
                                    BydOutputSettings.legacyVehicleProbe(app) != expectedLegacyMode) null
                                else BydVehicleFieldStore.replaceAutomatically(
                                    app,
                                    expectedSnapshot,
                                    candidateCapabilities,
                                )
                            } ?: return@runCatching VehicleProbeAttempt(
                                outcome = BydVehicleProbeOutcome(candidate.access),
                                snapshotChanged = true,
                            )
                            when {
                                replacement.saved -> VehicleProbeAttempt(candidate)
                                replacement.snapshotChanged -> VehicleProbeAttempt(
                                    outcome = candidate,
                                    snapshotChanged = true,
                                )
                                else -> VehicleProbeAttempt(
                                    outcome = BydVehicleProbeOutcome(candidate.access),
                                    heldCandidate = candidateCapabilities,
                                    lostFields = replacement.lostFields,
                                )
                            }
                        }
                    }
                }
            }.getOrElse { error ->
                VehicleProbeAttempt(
                    BydVehicleProbeOutcome(
                        BydAdbAccess.State.READY,
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
            runOnUiThread {
                val current = generation == vehicleProbeGeneration
                if (current) {
                    vehicleProbeAuthorizationInProgress = false
                    vehicleProbeInProgress = false
                }
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                pendingVehicleReplacement = attempt.heldCandidate
                pendingVehicleReplacementExpected = expectedSnapshot
                pendingVehicleLostFields = attempt.lostFields
                vehicleProbeOutcome = when {
                    attempt.allowedOnlyOnce -> attempt.outcome.copy(
                        error = getString(R.string.vehicle_probe_allowed_once),
                    )
                    attempt.snapshotChanged -> attempt.outcome.copy(
                        capabilities = null,
                        error = getString(R.string.vehicle_probe_snapshot_changed),
                    )
                    else -> attempt.outcome
                }
                adbAccessState = attempt.outcome.access
                val activatedLegacyMode = activateLegacyModeOnSuccess &&
                    vehicleProbeOutcome?.capabilities != null
                if (activatedLegacyMode) {
                    BydOutputSettings.setLegacyVehicleProbe(this, true)
                }
                if (vehicleProbeOutcome?.capabilities != null) automaticVehicleValidationStarted = true
                Log.i(
                    BYD_VEHICLE_TAG,
                    "user vehicle probe access=${attempt.outcome.access} " +
                        "saved=${vehicleProbeOutcome?.capabilities != null} " +
                        "lost=${attempt.lostFields.joinToString()} " +
                        "error=${vehicleProbeOutcome?.error ?: "none"}",
                )
                render()
                if (activatedLegacyMode && vehicleDataSwitchesOn()) reconnectForVehicleSetting()
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    /**
     * After the first saved probe, validation is automatic and never asks for authorization. ADB
     * transport failure keeps the saved snapshot; only two complete READY-but-unreadable checks
     * trigger one automatic field re-probe.
     */
    private fun validateSavedVehicleConfigurationAutomatically() {
        if (!BydOutputSettings.legacyVehicleProbe(this)) return
        val saved = BydVehicleFieldStore.load(applicationContext) ?: return
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            automaticVehicleValidationPending = true
            return
        }
        if (automaticVehicleValidationInProgress) {
            // A request during a run, such as a switch just turned on, runs once this one ends.
            automaticVehicleValidationPending = true
            return
        }
        if (automaticVehicleValidationStarted) return
        automaticVehicleValidationStarted = true
        automaticVehicleValidationInProgress = true
        val generation = ++vehicleValidationGeneration
        Log.i(BYD_VEHICLE_TAG, "automatic vehicle validation starting savedFirmware=${saved.firmwareKey}")
        if (page == "settings" && bydVehicleAdvancedExpanded) render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-byd13-auto-validate") {
            val app = applicationContext
            val validation = runCatching {
                var status = backend.check(app, mayAsk = false)
                var outcome: BydVehicleProbeOutcome? = null
                var heldCandidate: BydVehicleCapabilities? = null
                var lostFields: Set<BydVehicleField> = emptySet()
                var snapshotChanged = false
                var readable = status.state == BydAdbAccess.State.READY && enabledVehicleDataReadable(saved, status)
                if (status.state == BydAdbAccess.State.READY && !enabledVehicleDataReadable(saved, status)) {
                    Log.w(BYD_VEHICLE_TAG, "saved vehicle fields unreadable; validating once more")
                    try {
                        Thread.sleep(VEHICLE_VALIDATION_RETRY_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    // A cancelled validation reads nothing more alongside the operation that replaced it.
                    if (generation != vehicleValidationGeneration) {
                        return@runCatching VehicleValidationAttempt(
                            status = status,
                            snapshotChanged = true,
                        )
                    }
                    status = backend.check(app, mayAsk = false)
                    readable = status.state == BydAdbAccess.State.READY && enabledVehicleDataReadable(saved, status)
                    if (status.state == BydAdbAccess.State.READY && !readable) {
                        if (generation != vehicleValidationGeneration) {
                            return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                        }
                        Log.w(BYD_VEHICLE_TAG, "saved vehicle fields still unreadable; automatic re-probe starting")
                        val candidate = backend.probe(app, persist = false)
                        if (generation != vehicleValidationGeneration) {
                            return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                        }
                        outcome = candidate
                        candidate.capabilities?.let { next ->
                            val replacement = synchronized(vehicleOperationLock) {
                                if (generation != vehicleValidationGeneration) null
                                else BydVehicleFieldStore.replaceAutomatically(app, saved, next)
                            } ?: return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                            when {
                                replacement.saved -> Unit
                                replacement.snapshotChanged -> {
                                    snapshotChanged = true
                                    outcome = null
                                }
                                else -> {
                                    heldCandidate = next
                                    lostFields = replacement.lostFields
                                    outcome = BydVehicleProbeOutcome(candidate.access)
                                }
                            }
                        }
                    }
                }
                VehicleValidationAttempt(
                    status = status,
                    outcome = outcome,
                    heldCandidate = heldCandidate,
                    lostFields = lostFields,
                    snapshotChanged = snapshotChanged,
                    savedFieldsReadable = readable,
                )
            }
            runOnUiThread {
                val current = generation == vehicleValidationGeneration
                if (current) automaticVehicleValidationInProgress = false
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                adbCheckFailed = validation.isFailure
                validation.exceptionOrNull()?.let { Log.w(BYD_VEHICLE_TAG, "automatic vehicle validation failed", it) }
                validation.getOrNull()?.let { result ->
                    adbAccessState = result.outcome?.access ?: result.status.state
                    when {
                        result.snapshotChanged -> Unit
                        result.savedFieldsReadable && result.outcome == null -> {
                            // An open offer to replace stays: this check may not have read its lost fields.
                            vehicleProbeOutcome = null
                        }
                        result.lostFields.isNotEmpty() -> {
                            vehicleProbeOutcome = result.outcome
                            pendingVehicleReplacement = result.heldCandidate
                            pendingVehicleReplacementExpected = saved
                            pendingVehicleLostFields = result.lostFields
                        }
                        result.outcome != null -> {
                            vehicleProbeOutcome = result.outcome
                            pendingVehicleReplacement = null
                            pendingVehicleLostFields = emptySet()
                        }
                    }
                    Log.i(
                        BYD_VEHICLE_TAG,
                        "automatic vehicle validation access=${adbAccessState} " +
                            "reprobed=${result.outcome != null} " +
                            "saved=${result.outcome?.capabilities != null && result.lostFields.isEmpty()} " +
                            "lost=${result.lostFields.joinToString()} " +
                            "snapshotChanged=${result.snapshotChanged} " +
                            "error=${result.outcome?.error ?: "none"}",
                    )
                }
                if (page == "settings" && bydVehicleAdvancedExpanded) {
                    render()
                }
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    private fun scheduleAutomaticVehicleValidation() {
        if (!BydOutputSettings.legacyVehicleProbe(this)) {
            automaticVehicleValidationPending = false
            handler.removeCallbacks(automaticVehicleValidation)
            return
        }
        automaticVehicleValidationStarted = false
        automaticVehicleValidationPending = true
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    private fun cancelAutomaticVehicleValidationForUserOperation(
        resumeAfter: Boolean,
    ): BydVehicleCapabilities? = synchronized(vehicleOperationLock) {
        handler.removeCallbacks(automaticVehicleValidation)
        automaticVehicleValidationPending = resumeAfter
        if (resumeAfter) automaticVehicleValidationStarted = false
        vehicleValidationGeneration++
        automaticVehicleValidationInProgress = false
        BydVehicleFieldStore.load(applicationContext)
    }

    private fun runPendingAutomaticVehicleValidation() {
        if (!automaticVehicleValidationPending || adbSwitchChangePending ||
            vehicleAdbWorkInProgress() || automaticVehicleValidationInProgress) return
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    /** Whether [status] has every reading an enabled switch needs; null [capabilities] is default mode. */
    private fun enabledVehicleDataReadable(
        capabilities: BydVehicleCapabilities?,
        status: BydAdbAccess.Status,
    ): Boolean {
        if (BydOutputSettings.batteryToIphone(this) && capabilities?.batterySupported != false &&
            (status.batteryPercent == null || status.rangeKm == null)) return false
        if (BydOutputSettings.wheelSpeedToIphone(this) && capabilities?.motionSupported != false &&
            (status.speedKmh == null || status.gear == null)) return false
        if (BydOutputSettings.videoWhileParked(this) && capabilities?.gearSupported != false && status.gear == null) return false
        return true
    }

    private fun adbLinkStatusText(state: BydAdbAccess.State): String = when (state) {
        BydAdbAccess.State.READY -> getString(R.string.adb_access_ready)
        BydAdbAccess.State.NOT_APPROVED -> getString(R.string.adb_enabled_not_approved)
        BydAdbAccess.State.ADB_OFF -> getString(R.string.adb_off)
        BydAdbAccess.State.PAIRING_ONLY -> getString(R.string.adb_pairing_only)
    }

    private fun gearLetter(value: Int): String = when (value) {
        1 -> "P"
        2 -> "R"
        3 -> "N"
        else -> "D"
    }

    private fun reconnectForVehicleSetting() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun vehicleDataSwitchesOn() = BydOutputSettings.batteryToIphone(this) ||
        BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this)

    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: getString(R.string.apply_and_reconnect) already does it.
    private fun clusterSafeAreaControls(card: LinearLayout) {
        val rect = AirPlayPersistence.loadClusterSafeAreaRect(this)
            ?: DiLink4ClusterDisplay.defaultSafeAreaRect(
                AirPlayPersistence.loadClusterMarkerHorizontalStep(this),
                AirPlayPersistence.loadClusterMarkerVerticalStep(this))
        card.addView(label(getString(R.string.safe_area_mapping_summary,
            rect.width, rect.height, rect.left, rect.top, 1920, 720), 14, MUTED))
        card.addView(button(getString(R.string.cluster_safe_area_edit), false) {
            openClusterSafeAreaEditor()
        }, matchButton(10, 56))
        card.addView(button(getString(R.string.cluster_safe_area_reset), false) {
            AirPlayPersistence.clearClusterSafeAreaRect(this)
            render()
            reconnectForClusterMap()
        }, matchButton(10, 56))
        card.addView(label(getString(R.string.cluster_safe_area_hint), 14, MUTED))
    }

    private fun openClusterSafeAreaEditor() {
        clusterSafeAreaDialog?.dismiss()
        val previewOwner = Any()
        val initial = AirPlayPersistence.loadClusterSafeAreaRect(this)
            ?: DiLink4ClusterDisplay.defaultSafeAreaRect(
                AirPlayPersistence.loadClusterMarkerHorizontalStep(this),
                AirPlayPersistence.loadClusterMarkerVerticalStep(this))
        val editor = SafeAreaEditorView(this).apply {
            setBackgroundColor(Color.rgb(35, 39, 45))
            setRect(initial, 1920, 720)
        }
        // A standalone dialog gives the weighted preview an exact available height.
        // AlertDialog's wrap-content custom panel can collapse it to zero.
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val panel = column().apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.rgb(35, 39, 45))
        }
        panel.addView(label(getString(R.string.cluster_safe_area_edit), 18, Color.WHITE, true))
        panel.addView(label(getString(R.string.cluster_safe_area_live_hint), 14, MUTED))
        panel.addView(ClusterSafeAreaPreviewFrame(this, editor), LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = row()
        actions.addView(button(getString(R.string.cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(56), 1f))
        actions.addView(button(getString(if (CarPlayBackgroundSession.hasSession())
            R.string.apply_and_reconnect else R.string.save), true) {
            editor.currentRectForSource()?.let { AirPlayPersistence.saveClusterSafeAreaRect(this, it) }
            dialog.dismiss()
            render()
            reconnectForClusterMap()
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        panel.addView(actions)
        dialog.setContentView(panel, ViewGroup.LayoutParams(-1, -1))
        editor.onRectChanged = { ClusterActivityOutput.updateSafeAreaPreview(previewOwner, it) }
        clusterSafeAreaDialog = dialog
        dialog.setOnDismissListener {
            editor.onRectChanged = null
            ClusterActivityOutput.endSafeAreaPreview(previewOwner)
            if (clusterSafeAreaDialog === dialog) clusterSafeAreaDialog = null
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (resources.displayMetrics.heightPixels * 0.85f).toInt())
        ClusterActivityOutput.beginSafeAreaPreview(previewOwner, initial)
    }

    private fun reconnectForClusterMap() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun onLocationReportingChanged(enabled: Boolean) {
        if (enabled && !hasPreciseLocation()) {
            // Keep the switch off until precise location is actually granted.
            render()
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        applyLocationReporting(enabled)
    }

    private fun applyLocationReporting(enabled: Boolean) {
        if (AirPlayPersistence.loadLocationReportingEnabled(this) == enabled) return
        AirPlayPersistence.saveLocationReportingEnabled(this, enabled)
        render()
        // Location support is advertised during iAP2 identification, so both enabling and
        // disabling it require a new session. The host also refreshes its location service type.
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
        else toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        startupHotspotCancelled = true
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resolutionSettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.resolution_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.resolution_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showResolutionSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showResolutionSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun nightDelaySettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.custom_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun ambientLightThresholdControl(parent: LinearLayout) {
        val title = getString(R.string.ambient_light_threshold_title)
        fun summary(): String = getString(
            R.string.contrib_audio_home_choice_summary,
            title,
            getString(R.string.ambient_light_threshold_summary, AirPlayPersistence.loadAmbientLightThreshold(this).lux),
        )

        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            fields.addView(label(getString(R.string.ambient_light_threshold_value), 16, MUTED))
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(AirPlayPersistence.loadAmbientLightThreshold(this@DiPlayActivity).lux.toString())
            }
            fields.addView(input)
            fields.addView(label(getString(R.string.ambient_light_threshold_hint), 14, MUTED))
            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setView(fields)
                .setPositiveButton(getString(R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener saveThreshold@{
                    val lux = input.text.toString().trim().toIntOrNull()
                    if (lux == null || !AmbientLightThreshold.isValid(lux)) {
                        input.error = getString(R.string.ambient_light_threshold_error)
                        return@saveThreshold
                    }
                    AirPlayPersistence.saveAmbientLightThreshold(this, AmbientLightThreshold(lux))
                    control.text = summary()
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(AmbientLightThreshold.DEFAULT_LUX.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showNightModeSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun recoverBluetoothIfNeeded(after: () -> Unit = {}): Boolean {
        if (bluetoothRecoveryInProgress) { toast(getString(R.string.auto_bluetooth_restoring)); return true }
        if (CarPlayBackgroundSession.hasSession() || !com.shilapi.xcertplay.network.LocalHotspotBluetooth.needsRecovery(this)) return false
        bluetoothRecoveryInProgress = true
        refreshStatus()
        val app = applicationContext
        Thread({
            val failure = runCatching { com.shilapi.xcertplay.network.LocalHotspotBluetooth.recover(app) }.exceptionOrNull()
            SessionLogFile(File(app.filesDir, "logs/requests/diplay.log")).use { log ->
                log.file.parentFile?.mkdirs()
                log.append("Bluetooth recovery completed success=${failure == null} failureClass=${failure?.javaClass?.simpleName ?: "none"}")
            }
            runOnUiThread {
                bluetoothRecoveryInProgress = false
                bluetoothRecoveryError = failure != null
                if (!isDestroyed) {
                    refreshStatus()
                    if (failure == null) after() else toast(getString(R.string.auto_bluetooth_restore_failed))
                }
            }
        }, "lynk-bluetooth-recovery").start()
        return true
    }

    private fun startLynkConnection(wireless: Boolean) {
        // Returning from home is different from applying settings, which must reconnect.
        if (CarPlayBackgroundSession.hasSession() && AirPlayPersistence.loadWirelessEnabled(this) == wireless) {
            pendingWireless = false
            openProjection()
        } else connect(wireless)
    }

    private fun connect(wireless: Boolean) {
        if (setupError != null) { toast(setupError!!); return }
        if (simpleConnectionFlow) {
            pendingWireless = false
            if (CarPlayBackgroundSession.hasSession()) {
                // A 2.4GHz session may own a Bluetooth-off lease. Finish its close barrier
                // and restore the radio before examining bonds for the next attempt.
                CarPlayBackgroundSession.stop { runOnUiThread {
                    if (!isFinishing && !isDestroyed) connect(wireless)
                } }
                return
            }
        }
        SessionLogFile(File(filesDir, "logs/requests/diplay.log")).use { log ->
            log.file.parentFile?.mkdirs()
            log.append("Connection requested at=${java.time.Instant.now()} transport=${if (wireless) "wireless" else "usb"} localAuthReady=${setupError == null}")
        }

        startupHotspotCancelled = true
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (recoverBluetoothIfNeeded { connect(wireless) }) return
        if (automaticHotspot) pendingCarHotspotSetup = false
        if (simpleConnectionFlow && wireless && !automaticHotspot &&
            (pendingCarHotspotSetup || hotspotError(storedSsid(), storedPassword()) != null)) {
            saveHotspotAndConnect(); return
        }
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "connection"
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (wireless && carHotspotOff()) {
            if (simpleConnectionFlow) AlertDialog.Builder(this).setTitle(R.string.car_hotspot_is_off)
                .setMessage(R.string.link_setup_hint)
                .setPositiveButton(R.string.open_car_hotspot_settings) { _, _ -> openCarWifiSettings() }
                .setNegativeButton(R.string.cancel, null).show()
            else carHotspotOffDialog()
            return
        }
        if (simpleConnectionFlow && wireless) {
            if (!prepareLynkWireless()) return
        } else if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun editHeadUnitBluetoothAddress() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        fields.addView(label(getString(R.string.link_local_bt_hint), 17, TEXT))
        val input = EditText(this).apply {
            hint = "AA:BB:CC:DD:EE:FF"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(AirPlayPersistence.loadHeadUnitBluetoothAddress(this@DiPlayActivity).orEmpty())
        }
        fields.addView(input, LinearLayout.LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(this).setTitle(R.string.link_local_bt_title)
            .setView(fields).setPositiveButton(R.string.save, null)
            .setNeutralButton(R.string.link_local_bt_settings) { _, _ -> openSystem(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
            .setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                val address = com.shilapi.xcertplay.transport.HeadUnitBluetoothAddress.normalize(value)
                if (value.isNotBlank() && (address == null || address.equals(DiPlayPreferences.phoneAddress(this), true))) {
                    input.error = getString(R.string.link_local_bt_invalid)
                    return@setOnClickListener
                }
                AirPlayPersistence.saveHeadUnitBluetoothAddress(this, value)
                dialog.dismiss()
                render()
            }
        }
        dialog.show()
    }

    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (simpleConnectionFlow && adapter == null) {
            AlertDialog.Builder(this).setTitle(R.string.link_bluetooth_failed)
                .setMessage(R.string.link_bluetooth_fix)
                .setPositiveButton(R.string.connect_with_usb) { _, _ -> pendingWireless = false; connect(false) }
                .setNegativeButton(R.string.cancel) { _, _ -> pendingWireless = false }.show()
            return
        }
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        if (phonePickerShowing) return
        phonePickerShowing = true
        AlertDialog.Builder(this).setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                phonePickerShowing = false
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ ->
                if (simpleConnectionFlow && pendingWireless) wirelessBluetoothSettings.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                else openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }.setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }
            .setOnCancelListener { pendingWireless = false }
            .setOnDismissListener { phonePickerShowing = false }.show()
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            bluetoothRecoveryInProgress -> getString(R.string.auto_bluetooth_restoring)
            bluetoothRecoveryError -> getString(R.string.auto_bluetooth_restore_failed)
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            simpleConnectionFlow && running && CarPlayBackgroundSession.progress != null -> CarPlayBackgroundSession.progress
            running -> getString(R.string.connecting_to_your_iphone)
            simpleConnectionFlow && automaticHotspot -> getString(R.string.auto_hotspot_ready)
            simpleConnectionFlow -> getString(if (hotspotError(storedSsid(), storedPassword()) != null)
                R.string.link_setup_needed else R.string.link_not_started)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        if (simpleConnectionFlow && page == "home" && !running && !bluetoothRecoveryInProgress && !bluetoothRecoveryError) {
            status?.text = if (DiPlayPreferences.phoneAddress(this) != null)
                getString(R.string.lynk_remembered_phone, DiPlayPreferences.phoneName(this)) else ""
            status?.visibility = if (status?.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        } else status?.visibility = View.VISIBLE
        if (lastRunning != running) {
            connectButton?.text = if (simpleConnectionFlow) {
                if (running) getString(R.string.link_progress) else getString(R.string.lynk_wireless)
            } else if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone)
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
    }
    private fun authorizeClusterRouting() {
        val app = applicationContext
        Thread({
            val result = runCatching {
                com.shilapi.xcertplay.adb.LocalAdb(com.shilapi.xcertplay.adb.AdbKeys.load(app)).use {
                    it.connect(mayAsk = true)
                }
            }.getOrNull()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    toast(if (result == com.shilapi.xcertplay.adb.LocalAdb.Access.READY)
                        getString(R.string.adb_access_ready) else getString(R.string.adb_not_approved))
                    if (result == com.shilapi.xcertplay.adb.LocalAdb.Access.READY) ClusterActivityOutput.retry()
                }
            }
        }, "adb-cluster-authorize").start()
    }

    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        runCatching { export.launch(reportFileName()) }.onFailure { exportDiagnostics() }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = ConnectionDiagnosticReport.build(appContext, version(), setupError == null)
                val savedReport = if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report)
                savedReport to report
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val (savedReport, report) = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(when {
                            savedReport.savedInApp -> getString(R.string.diagnostic_report_saved_in_app)
                            savedReport.savedPath != null -> getString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath)
                            uri == null -> "Downloads/DiPlay/$fileName"
                            else -> getString(R.string.your_report_was_saved_to_the_selected_location)
                        })
                        .setPositiveButton(getString(R.string.view_diagnostic_report)) { _, _ -> showDiagnosticReport(report) }
                        .setNegativeButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedReport.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { showDiagnosticReport(report) }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, "diplay-export").start()
    }

    private fun showDiagnosticReport(report: String) {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.diagnostic_report_copy_hint), 14, MUTED))
        body.addView(label(report, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        AlertDialog.Builder(this).setTitle(getString(R.string.view_diagnostic_report))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null).show()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            val pcm = assets.open("navigation_test.pcm").use { it.readBytes() }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("DiPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(getString(R.string.audio_stream_unavailable, streamType, state))
            return
        }
        Log.i("DiPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        handler.postDelayed(stop, 4500)
    }

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        target.setTextColor(if (selected) BG else TEXT)
        target.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x336F9FD9),
            rounded(if (selected) ACCENT else SURFACE, if (selected) ACCENT else BORDER),
            null
        )
    }

    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 3
            setPadding(0, dp(8), 0, dp(8))
        }
        for (i in 0..20) {
            val btn = Button(this).apply {
                text = i.toString()
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                stateListAnimator = null
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return grid
    }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun languageSettings(content: LinearLayout) {
        section(content, getString(R.string.language_section_title)) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, group: LynkSettingsGroup = LynkSettingsGroup.GENERAL, build: (LinearLayout) -> Unit) {
        if (simpleConnectionFlow && page == "settings" && group != settingsGroup) return
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, enabled: Boolean = true, save: (Boolean) -> Unit): Switch {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val control = Switch(this).apply {
            contentDescription = title; isChecked = value; isEnabled = enabled; minHeight = dp(56)
            if (simpleConnectionFlow) {
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(ACCENT, MUTED))
                trackTintList = ColorStateList(states, intArrayOf((ACCENT and 0x00FFFFFF) or 0x66000000, BORDER))
            } else buttonTintList = ColorStateList.valueOf(ACCENT)
            setOnCheckedChangeListener { _, checked -> save(checked) }
        }
        line.addView(control)
        parent.addView(line)
        return control
    }
    // [announcesReconnect] labels a choice whose [save] reconnects by itself.
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true,
        announcesReconnect: Boolean = reconnects, enabled: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}.apply { isEnabled = enabled }
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (announcesReconnect && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    private fun card() = column().apply {
        background = if (simpleConnectionFlow) LynkPanelStyle.shape(this@DiPlayActivity) else rounded(SURFACE, BORDER)
        val padding = dp(if (simpleConnectionFlow) 20 else 24)
        setPadding(padding, padding, padding, padding)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 18f; setTextColor(if (primary) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER), null)
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56); stateListAnimator = null
        if (simpleConnectionFlow) LynkPanelStyle.styleButton(this, primary)
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(if (simpleConnectionFlow) 16 else 20).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val BG get() = if (simpleConnectionFlow) LynkPanelStyle.background else Color.rgb(12, 17, 27)
    private val SURFACE get() = if (simpleConnectionFlow) LynkPanelStyle.surface else Color.rgb(21, 30, 44)
    private val BORDER get() = if (simpleConnectionFlow) LynkPanelStyle.border else Color.rgb(42, 56, 75)
    private val ACCENT get() = if (simpleConnectionFlow) LynkPanelStyle.accent else Color.rgb(166, 200, 255)
    private val TEXT get() = if (simpleConnectionFlow) LynkPanelStyle.text else Color.rgb(241, 245, 252)
    private val MUTED get() = if (simpleConnectionFlow) LynkPanelStyle.muted else Color.rgb(168, 182, 202)
    private val WARNING = Color.rgb(255, 196, 128)
    companion object {
        private const val BYD_VEHICLE_TAG = "DiPlay-BYD13"
        private const val VEHICLE_VALIDATION_RETRY_MILLIS = 500L
        private const val ADB_KEY_SAVE_WAIT_MILLIS = 500L
    }
}
