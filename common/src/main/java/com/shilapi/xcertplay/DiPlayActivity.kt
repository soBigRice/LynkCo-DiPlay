// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.EvChargingConnectors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var settingsGroup = LynkSettingsGroup.GENERAL
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private val simpleConnectionFlow get() = resources.getBoolean(R.bool.config_simple_connection_flow)
    private var bluetoothRecoveryInProgress = false
    private var bluetoothRecoveryError = false
    private val automaticHotspot get() = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var environmentReport: EnvironmentReport? = null
    private var environmentRunning = false
    private var environmentFailed = false
    private var environmentGroup = EnvironmentGroup.APP
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var adbStatus: TextView? = null
    private var adbCheckGeneration = 0
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) return@registerForActivityResult reconnectForLocation()
        AirPlayPersistence.saveLocationReportingEnabled(this, false)
        render()
        permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
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
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        settingsGroup = savedInstanceState?.getString("lynk_settings_group")?.let { name ->
            LynkSettingsGroup.entries.find { it.name == name }
        } ?: LynkSettingsGroup.GENERAL
        render()
        if (page == "environment") checkEnvironment()
        handleWirelessRecovery()
        handleDiagnosticExport()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { page = "home"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        if (page == "environment") checkEnvironment()
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

    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); outState.putString("lynk_settings_group", settingsGroup.name); outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup); super.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    private fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            android.widget.Toast.makeText(this, R.string.center_map_no_permission_screen, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        CenterMapOverlay.onDiPlayScreenShown()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        recoverBluetoothIfNeeded()
        handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && (page == "home" || page == "settings" || page == "connection")) render()
        if (!initialLaunch && page == "environment") checkEnvironment()
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun render() {
        status = null; connectButton = null; disconnectButton = null; exportButton = null; lastRunning = null
        if (simpleConnectionFlow) { renderLynkPanel(); return }
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        val content = column().apply { setPadding(dp(32), dp(24), dp(32), dp(32)) }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_carplay); contentDescription = getString(R.string.carplay) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(label(getString(R.string.diplay), 26, TEXT, true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        val returnToProjection = simpleConnectionFlow && page == "settings" && CarPlayBackgroundSession.hasSession()
        val backLabel = if (returnToProjection) R.string.video_back_to_carplay else if (page == "home") R.string.car_home else R.string.back
        header.addView(button(getString(backLabel), false) {
            if (returnToProjection && CarPlayBackgroundSession.hasSession()) openProjection()
            else if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            else { page = "home"; render() }
        }, LinearLayout.LayoutParams(dp(130), dp(56)))
        content.addView(header)
        content.addView(space(24))
        when (page) {
            "connection" -> connectionSetup(content)
            "settings" -> settings(content)
            "about" -> about(content)
            else -> home(content)
        }
        setContentView(scroll)
        refreshStatus()
    }

    private fun home(content: LinearLayout) {
        if (simpleConnectionFlow) { connectionHome(content); return }
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
            WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_hint_manual)
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
            else -> getString(R.string.hotspot_hint_p2p)
        }
        card.addView(label(connectionHint, 15, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        if (carHotspotOff()) {
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
        val wide = resources.configuration.screenWidthDp >= 720
        val shell = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val navigation = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            setBackgroundColor(SURFACE)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val destinations = listOf(
            Triple("home", R.string.lynk_home, R.drawable.ic_lynk_home),
            Triple("connection", R.string.lynk_connection, R.drawable.ic_dp_connection),
            Triple("environment", R.string.env_nav, R.drawable.ic_dp_permissions),
            Triple("settings", R.string.settings, R.drawable.ic_lynk_settings),
        )
        if (wide) navigation.addView(label("03", 26, TEXT, true).apply {
            gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, dp(64)))
        destinations.forEach { (destination, title, icon) ->
            val selected = page == destination || (page == "about" && destination == "settings")
            val item = button(getString(title), false) {
                page = destination
                if (destination == "environment") checkEnvironment() else render()
            }.apply {
                textSize = if (wide) 14f else 13f; isSelected = selected
                setTextColor(if (selected) ACCENT else MUTED)
                background = LynkPanelStyle.shape(this@DiPlayActivity,
                    if (selected) LynkPanelStyle.raised else SURFACE,
                    if (selected) LynkPanelStyle.raised else SURFACE, 10)
                val glyph = getDrawable(icon)?.mutate()?.apply {
                    setTint(if (selected) ACCENT else MUTED); setBounds(0, 0, dp(24), dp(24))
                }
                if (wide) setCompoundDrawables(null, glyph, null, null)
                compoundDrawablePadding = dp(8)
            }
            navigation.addView(item, if (wide) LinearLayout.LayoutParams(-1, dp(76)).apply { bottomMargin = dp(6) }
                else LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(6) })
        }
        shell.addView(navigation, if (wide) LinearLayout.LayoutParams(dp(106), -1) else LinearLayout.LayoutParams(-1, -2))
        val body = column().apply { setPadding(dp(28), dp(16), dp(28), dp(12)) }
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = column().apply {
            addView(label(getString(R.string.lynk_panel_brand), 20, TEXT, true).apply { letterSpacing = .12f })
            addView(label(getString(R.string.lynk_panel_subtitle), 12, MUTED))
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val hasSession = CarPlayBackgroundSession.hasSession()
        header.addView(button(getString(if (hasSession) R.string.video_back_to_carplay else R.string.car_home), false) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }, LinearLayout.LayoutParams(dp(144), dp(48)))
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
            "connection" -> simpleConnectionSetup(content)
            "settings" -> settings(content)
            "about" -> about(content)
            else -> connectionHome(content)
        }
        shell.addView(body, if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
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
        val wide = resources.configuration.screenWidthDp >= 840
        val panels = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val summary = card()
        summary.addView(label(getString(R.string.lynk_home_kicker), 11, ACCENT, true).apply { letterSpacing = .12f })
        summary.addView(label(getString(R.string.carplay), 38, TEXT, true).apply { setPadding(0, dp(8), 0, 0) })
        summary.addView(label(getString(R.string.lynk_home_subline), 16, MUTED).apply { setPadding(0, dp(2), 0, dp(22)) })
        summary.addView(label(getString(R.string.lynk_status), 12, ACCENT, true))
        status = label("", 19, TEXT, true).apply {
            setPadding(0, dp(6), 0, dp(14)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        summary.addView(status)
        val phone = if (DiPlayPreferences.phoneAddress(this) == null) getString(R.string.link_phone_unselected)
            else getString(R.string.link_saved_phone, DiPlayPreferences.phoneName(this))
        summary.addView(label(phone, 14, MUTED))
        if (automaticHotspot) summary.addView(label(getString(R.string.auto_hotspot_title), 13, MUTED))
        else if (storedSsid().isNotEmpty()) summary.addView(label(getString(R.string.link_saved_hotspot, storedSsid()), 15, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        val controls = card()
        controls.addView(label(getString(R.string.lynk_connection_methods), 21, TEXT, true))
        controls.addView(label(getString(R.string.lynk_connect_hint_short), 14, MUTED).apply { setPadding(0, dp(6), 0, dp(16)) })
        connectButton = button(getString(R.string.lynk_wireless), true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection() else connect(true)
        }
        controls.addView(connectButton, matchButton(0, 54))
        controls.addView(button(getString(R.string.lynk_wired), false) { connect(false) }, matchButton(10, 54))
        val actions = row()
        actions.addView(button(getString(R.string.link_setup), false) { page = "connection"; render() }.apply { textSize = 14f }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(space(10), LinearLayout.LayoutParams(dp(10), 1))
        actions.addView(button(getString(R.string.link_change_phone), false) { pendingWireless = false; choosePhone() }.apply { textSize = 14f }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        disconnectButton = button(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        controls.addView(disconnectButton, matchButton(12, 56))
        panels.addView(summary, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(18) } else LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        panels.addView(controls, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        content.addView(panels)
        setupError?.let { content.addView(label(it, 16, WARNING).apply { setPadding(0, dp(12), 0, 0) }) }
        val health = row().apply {
            gravity = Gravity.CENTER_VERTICAL; background = LynkPanelStyle.shape(this@DiPlayActivity)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val healthCopy = column().apply {
            addView(label(getString(R.string.env_home_copy), 17, TEXT, true))
            val report = environmentReport
            addView(label(if (report == null) getString(R.string.env_home_idle) else
                getString(R.string.env_summary, report.items.count { it.state == EnvironmentState.ACTION },
                    report.items.count { it.state == EnvironmentState.VERIFY }), 13, MUTED))
        }
        health.addView(healthCopy, LinearLayout.LayoutParams(0, -2, 1f))
        health.addView(button(getString(R.string.env_title), false) { page = "environment"; checkEnvironment() },
            LinearLayout.LayoutParams(dp(146), dp(50)).apply { marginStart = dp(16) })
        content.addView(health, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val footer = row().apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(button(getString(R.string.lynk_export_short), false) { exportDiagnostics() }.apply {
            contentDescription = getString(R.string.link_export)
        }.apply { textSize = 14f }, LinearLayout.LayoutParams(dp(130), dp(48)))
        footer.addView(column().apply {
            setPadding(dp(16), 0, dp(12), 0)
            addView(label(getString(R.string.lynk_upstream_credit), 13, TEXT))
            addView(label(getString(R.string.lynk_export_copy), 12, MUTED))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(label("DiPlay · " + version().substringAfterLast("-"), 12, MUTED))
        content.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun settings(content: LinearLayout) {
        content.addView(label(getString(if (simpleConnectionFlow) R.string.settings else R.string.your_drive_your_way), if (simpleConnectionFlow) 28 else 34, TEXT, true))
        content.addView(label(getString(if (simpleConnectionFlow && settingsGroup != LynkSettingsGroup.DISPLAY) R.string.lynk_settings_copy else R.string.apply_reconnects_carplay_for_size_resolution_music_buffer), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
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
            toggle(card, getString(R.string.open_after_the_car_starts), getString(R.string.availability_depends_on_your_head_unit_s_startup_settings), AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
        }
        section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display, LynkSettingsGroup.DISPLAY) { card ->
            carPlaySizeControl(card)
            choice(card, getString(R.string.resolution), listOf(getString(R.string.resolution_native), getString(R.string.s_80_lighter_load), getString(R.string.s_60_lightest_load)), listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            toggle(card, getString(R.string.efficient_video), getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility), AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
            toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            toggle(card, getString(R.string.full_screen), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
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
                AirPlayPersistence.loadLocationReportingEnabled(this)) {
                AirPlayPersistence.saveLocationReportingEnabled(this, it)
                if (it && !hasPreciseLocation()) {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    reconnectForLocation()
                }
            }
        }
        if (com.shilapi.xcertplay.hud.BydOutputSettings.available(this)) section(content, getString(R.string.byd_navigation), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.navigation_on_hud_and_instrument_cluster),
                getString(R.string.show_phone_navigation_arrows_distance_and_street_names_on),
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
            if (ClusterMapPresentation.findDisplay(this) != null) {
                toggle(card, getString(R.string.carplay_map_on_instrument_cluster_experimental),
                    getString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c),
                    AirPlayPersistence.loadClusterMapEnabled(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, it)
                    reconnectForClusterMap()
                }
                toggle(card, getString(R.string.center_map_card), getString(R.string.center_map_card_description),
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
                    card.addView(label(if (usage) getString(R.string.center_map_usage_allowed)
                        else getString(R.string.center_map_usage_missing, packageName), 14, if (usage) MUTED else WARNING))
                }
                if (DiLink51ClusterLayout.supported()) {
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
                    val sizes = CarPlayClusterDisplay.scalePresets
                    val contents = CarPlayClusterDisplay.Content.entries
                    val content = AirPlayPersistence.loadClusterContent(this)
                    val turnCard = content == CarPlayClusterDisplay.Content.TURN_CARD
                    choice(card, getString(R.string.dashboard_shows), listOf(
                        getString(R.string.dashboard_content_map),
                        getString(R.string.dashboard_content_turn_card),
                        getString(R.string.dashboard_content_map_with_turn_card),
                    ), contents.indexOf(content)) {
                        AirPlayPersistence.saveClusterContent(this, contents[it])
                        render()
                    }
                    choice(card, getString(if (turnCard) R.string.turn_card_size else R.string.cluster_map_size),
                        listOf(getString(R.string.cluster_size_standard), getString(R.string.cluster_size_larger), getString(R.string.cluster_size_largest)),
                        sizes.indexOf(AirPlayPersistence.loadClusterMapScalePercent(this)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveClusterMapScalePercent(this, sizes[it])
                    }
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
                    toggle(card, getString(R.string.dashboard_map_only_in_small_and_full_navi),
                        getString(R.string.dashboard_map_only_in_small_and_full_navi_description),
                        BydOutputSettings.clusterStreamPause(this)) {
                        BydOutputSettings.setClusterStreamPause(this, it)
                        if (it) checkAdbAccess(mayAsk = true)
                    }
                }
            }
            toggle(card, getString(R.string.car_battery_for_the_iphone),
                getString(R.string.car_battery_for_the_iphone_description),
                BydOutputSettings.batteryToIphone(this)) {
                BydOutputSettings.setBatteryToIphone(this, it)
                if (it) {
                    checkAdbAccess(mayAsk = true, reconnectWhenReady = CarPlayBackgroundSession.hasSession())
                } else if (CarPlayBackgroundSession.hasSession()) {
                    connect(AirPlayPersistence.loadWirelessEnabled(this))
                }
            }
            val connectors = EvChargingConnectors.entries
            choice(card, getString(R.string.charging_connectors), connectors.map { it.localizedLabel(this) },
                connectors.indexOf(BydOutputSettings.chargingConnectors(this))) {
                BydOutputSettings.setChargingConnectors(this, connectors[it])
            }
            val lowCharge = BydOutputSettings.lowChargePresets
            choice(card, getString(R.string.low_charge_warning), lowCharge.map {
                    getString(if (it == BydOutputSettings.DEFAULT_LOW_CHARGE_PERCENT) R.string.percent_default else R.string.percent_value, it)
                },
                lowCharge.indexOf(BydOutputSettings.lowChargePercent(this)).coerceAtLeast(0), reconnects = false) {
                BydOutputSettings.setLowChargePercent(this, lowCharge[it])
            }
            toggle(card, getString(R.string.wheel_speed_for_tunnels),
                getString(R.string.wheel_speed_for_tunnels_description),
                BydOutputSettings.wheelSpeedToIphone(this)) {
                BydOutputSettings.setWheelSpeedToIphone(this, it)
                if (it) checkAdbAccess(mayAsk = true)
                if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
            }
            toggle(card, getString(R.string.video_while_parked),
                getString(R.string.video_while_parked_description),
                BydOutputSettings.videoWhileParked(this)) {
                BydOutputSettings.setVideoWhileParked(this, it)
                if (it) checkAdbAccess(mayAsk = true)
                if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
            }
            toggle(card, getString(R.string.cluster_song),
                getString(R.string.cluster_song_description),
                BydOutputSettings.clusterSong(this)) {
                BydOutputSettings.setClusterSong(this, it)
                if (it) checkAdbAccess(mayAsk = true)
                BydNavigationOutputs.clusterSongChanged(it)
            }
            adbStatus = label("", 14, MUTED).also { status ->
                card.addView(status)
            }
            if (BydOutputSettings.clusterStreamPause(this) || BydOutputSettings.batteryToIphone(this) ||
                BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this) ||
                BydOutputSettings.clusterSong(this))
                checkAdbAccess(mayAsk = false)
            card.addView(button(getString(R.string.check_adb_access), false) { checkAdbAccess(mayAsk = true) }, matchButton(10, 56))
            card.addView(button(getString(R.string.apply_and_reconnect), false) {
                if (BydOutputSettings.batteryToIphone(this)) {
                    checkAdbAccess(mayAsk = true, reconnectWhenReady = true)
                } else {
                    connect(AirPlayPersistence.loadWirelessEnabled(this))
                }
            }, matchButton(10, 56))
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
        languageSettings(content)
    }

    private fun about(content: LinearLayout) {
        content.addView(label(getString(R.string.diplay), 40, TEXT, true))
        content.addView(label(getString(R.string.carplay_at_home_in_your_car), 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
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

    // The car hotspot link needs the hotspot on; DiPlay only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

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
        phone.addView(label(getString(R.string.link_pair_hint), 17, TEXT).apply { setPadding(0, dp(16), 0, dp(12)) })
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
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P)
        val titles = listOf(getString(R.string.built_in_car_hotspot), getString(R.string.wifi_direct))
        val descriptions = listOf(
            getString(R.string.hotspot_mode_manual_desc),
            getString(R.string.hotspot_mode_p2p_desc)
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
        } else {
            parent.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
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

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(this).apply { hint = getString(R.string.hotspot_name); setText(storedSsid()); setSingleLine() }
        val password = EditText(this).apply {
            hint = getString(R.string.hotspot_password); setText(storedPassword()); setSingleLine()
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
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString().trim()
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

    // The car's approval dialog for DiPlay's ADB key opens only from here, never while driving.
    private fun checkAdbAccess(mayAsk: Boolean, reconnectWhenReady: Boolean = false) {
        val status = adbStatus ?: return
        val generation = ++adbCheckGeneration
        status.setTextColor(MUTED)
        status.text = getString(if (mayAsk) R.string.adb_checking_may_ask else R.string.adb_checking)
        Thread({
            val result = runCatching { BydAdbAccess.check(applicationContext, mayAsk) }.getOrNull()
            runOnUiThread {
                if (adbStatus !== status || generation != adbCheckGeneration || isFinishing || isDestroyed) return@runOnUiThread
                status.setTextColor(if (result?.state == BydAdbAccess.State.READY) MUTED else WARNING)
                status.text = adbStatusText(result)
                if (reconnectWhenReady && BydOutputSettings.batteryToIphone(this) &&
                    result?.state == BydAdbAccess.State.READY && result.batteryPercent != null) {
                    connect(AirPlayPersistence.loadWirelessEnabled(this))
                }
            }
        }, "diplay-adb-check").start()
    }

    private fun adbStatusText(result: BydAdbAccess.Status?): String = when (result?.state) {
        null -> getString(R.string.adb_check_failed)
        BydAdbAccess.State.READY -> listOfNotNull(
            getString(R.string.adb_access_ready),
            result.dashboardMode?.let {
                getString(if (result.dashboardShowsMap) R.string.adb_dashboard_sends_map else R.string.adb_dashboard_does_not_send_map,
                    it.localizedLabel(this))
            },
            result.batteryPercent?.let { getString(R.string.adb_battery_reading, it.roundToInt(), result.rangeKm ?: 0) }
                ?: getString(R.string.adb_battery_unreadable).takeIf { BydOutputSettings.batteryToIphone(this) },
            getString(R.string.adb_battery_reconnect).takeIf {
                BydOutputSettings.batteryToIphone(this) && result.batteryPercent != null
            },
        ).joinToString(" ")
        BydAdbAccess.State.NOT_APPROVED -> getString(R.string.adb_not_approved)
        BydAdbAccess.State.ADB_OFF -> getString(R.string.adb_off)
        BydAdbAccess.State.PAIRING_ONLY -> getString(R.string.adb_pairing_only)
    }

    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The location component is part of the iAP2 identification, so a running session reconnects.
    private fun reconnectForLocation() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: getString(R.string.apply_and_reconnect) already does it.
    private fun reconnectForClusterMap() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
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

    private fun connect(wireless: Boolean) {
        SessionLogFile(File(filesDir, "logs/requests/diplay.log")).use { log ->
            log.file.parentFile?.mkdirs()
            log.append("Connection requested at=${java.time.Instant.now()} transport=${if (wireless) "wireless" else "usb"} localAuthReady=${setupError == null}")
        }

        if (setupError != null) { toast(setupError!!); return }
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
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                pendingWireless = true; choosePhone(); return
            }
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            val selected = DiPlayPreferences.phoneAddress(this)
            if (adapter == null || !adapter.isEnabled || adapter.bondedDevices.none { it.address == selected }) {
                pendingWireless = true; choosePhone(); return
            }
        }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
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
        // Only auto-select an unambiguous iPhone, not an arbitrary paired accessory.
        if (simpleConnectionFlow && pendingWireless) {
            devices.filter { it.name?.contains("iPhone", ignoreCase = true) == true }.singleOrNull()?.let { device ->
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                pendingWireless = false
                render(); connect(true); return
            }
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
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
    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getString(R.string.this_head_unit_could_not_open_a_save_location_please_try_s)
                else getString(R.string.this_head_unit_has_no_available_file_picker_to_save_the_re))
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        var savedPath: String? = null
        Thread({
            val result = runCatching {
                val report = ConnectionDiagnosticReport.build(appContext, version(), setupError == null)
                if (uri != null) { DiagnosticExportStore.write(appContext.contentResolver, uri, report); uri }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else {
                    val saved = DiagnosticExportStore.saveLocally(appContext, fileName, report)
                    savedPath = saved.file.absolutePath
                    saved.uri
                }
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val savedUri = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(if (uri == null) savedPath ?: "Downloads/DiPlay/$fileName" else getString(R.string.your_report_was_saved_to_the_selected_location))
                        .setPositiveButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedUri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { toast(getString(R.string.report_saved_open_it_from_your_file_manager_to_share_it)) }
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
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(Switch(this).apply {
            contentDescription = title; isChecked = value; minHeight = dp(56)
            if (simpleConnectionFlow) {
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(ACCENT, MUTED))
                trackTintList = ColorStateList(states, intArrayOf((ACCENT and 0x00FFFFFF) or 0x66000000, BORDER))
            } else buttonTintList = ColorStateList.valueOf(ACCENT)
            setOnCheckedChangeListener { _, checked -> save(checked) }
        })
        parent.addView(line)
    }
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
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
}
