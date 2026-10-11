// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.carhop.CarBluetoothHop
import com.shilapi.xcertplay.carhop.CarBluetoothHops
import com.shilapi.xcertplay.carhop.CarBluetoothPhone
import com.shilapi.xcertplay.host.BuildConfig
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.NavigationAudioStream
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.AdapterBringUp
import com.shilapi.xcertplay.transport.AdapterBringUpAssessment
import com.shilapi.xcertplay.transport.AdapterObservation
import com.shilapi.xcertplay.transport.ExternalBluetoothRoute
import com.shilapi.xcertplay.transport.WirelessBluetoothHop
import com.shilapi.xcertplay.transport.WirelessGap
import com.shilapi.xcertplay.transport.WirelessReadiness
import com.shilapi.xcertplay.transport.WirelessReadinessCheck
import com.shilapi.xcertplay.transport.WirelessReadinessInput
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var pendingCarHotspotSetup = false

    /**
     * The manual-hotspot pair, read once per redraw for the same reason [cachedReport] is: laying the
     * page out asks for it several times over, and each read is a call into the Wi-Fi service.
     */
    private var cachedHotspot: Pair<String, String>? = null
    private var setupError: String? = null

    /** The exception behind [setupError]; the header has to name the reason, not just the symptom. */
    private var setupErrorDetail: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var adapterWatch: AdapterWatch? = null
    private var adapterSeen: AdapterObservation? = null

    /** The line the adapter's step is written into, kept so a step change repaints it instead of the page. */
    private var adapterStatus: TextView? = null

    /** The adapter's presence as of the last look, so plugging it in redraws the page exactly once. */
    private var adapterPluggedSeen: Boolean? = null

    /** Why the last wireless connect attempt did not start, shown on the connection page instead of in a dialog. */
    private var blockedWireless: WirelessReadiness? = null

    /** The head unit probe, held for the length of one redraw so laying the page out reads it once. */
    private var cachedReport: ConnectionSupportReport? = null
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var uploadInProgress = false
    private var navigationStreamType = NavigationAudioStream.deviceDefault
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var uploadButton: Button? = null

    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM

    /**
     * Settings that had no control anywhere once the CarPlay settings screen stopped being opened.
     * Location reporting needs its own permission.
     */
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var debugLogsEnabled = false

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            locationPermissionAvailable =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            if (!locationPermissionAvailable) {
                locationReportingEnabled = false
                AirPlayPersistence.saveLocationReportingEnabled(this, false)
            } else if (grants.isNotEmpty()) {
                AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
            }
            render()
        }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }

    /**
     * Plugging or unplugging a USB device is the only thing that can change whether the adapter is on the
     * bus, so the bus is read then and not once a second. Probing it on every tick was detection running
     * whether or not the user asked for anything.
     */
    private val usbTopology = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onUsbTopologyChanged()
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) render() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
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
        DiagCrashHandler.install(this)
        languagePreferenceAtCreate = AppLocale.preference(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = BG
            window.navigationBarColor = BG
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            setupErrorDetail = "${it.javaClass.simpleName}: ${it.message}"
            DiagLog.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        // The selector must open on what is actually stored, or the user cannot tell what is in effect.
        navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this)
        loadConnectionSettings()
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val parent = parentOf(page)
                if (parent != null) { page = parent; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup); super.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        registerUsbTopology()
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        // Opening the app never starts a connection. Wireless or USB is chosen on the connect button.
        if (initialLaunch) initialLaunch = false
        else if (page != "wireless-recovery") render()
    }
    override fun onPause() {
        unregisterUsbTopology()
        handler.removeCallbacks(tick)
        super.onPause()
    }
    override fun onDestroy() {
        releaseAdapterWatch()
        super.onDestroy()
    }

    private fun render() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null; adapterStatus = null
        // One probe per redraw: laying the page out asks what the head unit supports several times over.
        cachedReport = null
        cachedHotspot = null
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        val content = column().apply { setPadding(dp(32), dp(24), dp(32), dp(32)) }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_carplay); contentDescription = getString(R.string.carplay) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(label(getString(R.string.diplay), 26, TEXT, true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(button(if (parentOf(page) == null) getString(R.string.car_home) else getString(R.string.back), false) {
            val parent = parentOf(page)
            if (parent == null) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            else { page = parent; render() }
        }, LinearLayout.LayoutParams(dp(130), dp(56)))
        content.addView(header)
        content.addView(space(24))
        when (page) {
            "wireless" -> wirelessSetup(content)
            "wired" -> wiredSetup(content)
            "display" -> displaySettings(content)
            "system" -> systemSettings(content)
            "settings" -> settings(content)
            "about" -> about(content)
            else -> home(content)
        }
        setContentView(scroll)
        refreshStatus()
    }

    /**
     * The page the back control returns to, or null where there is nothing behind it. The hub is a
     * page like any other, so back from it is the home page rather than the hub again.
     */
    private fun parentOf(from: String): String? = when (from) {
        "home" -> null
        "settings" -> "home"
        "about" -> "system"
        else -> "settings"
    }

    private fun home(content: LinearLayout) {
        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        left.addView(label(getString(R.string.your_phone_your_drive), 12, ACCENT, true).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing = .16f
        })
        left.addView(label(getString(R.string.a_familiar_drive), if (wide) 42 else 36, TEXT, true).apply { setPadding(0, dp(12), 0, dp(10)) })
        left.addView(label(getString(R.string.your_maps_music_and_conversations_carplay_right_here_on_yo), 19, MUTED))
        val wireless = deviceSupportsWireless()
        val wired = UsableConnection.USB in connectionReport().usable
        val card = card()
        status = label(getString(R.string.ready_when_you_are), 24, TEXT, true)
        card.addView(status)
        card.addView(supportedConnectionLabel())
        adapterStatusView()?.let { card.addView(it) }
        // Both ways in, on the page. They used to sit behind a dialog that had to be dismissed
        // before the choice could even be read — which is exactly the moment the driver is standing
        // at the car with a cable in one hand.
        val entries = if (wide) row().apply { gravity = Gravity.TOP } else column()
        var chosen = 0
        fun addEntry(view: View) {
            entries.addView(
                view,
                if (wide) {
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (chosen > 0) marginStart = dp(16) }
                } else {
                    LinearLayout.LayoutParams(-1, -2).apply { if (chosen > 0) topMargin = dp(16) }
                },
            )
            chosen++
        }
        addEntry(
            connectionEntry(
                title = getString(R.string.wireless_carplay),
                hint = if (wireless) {
                    wirelessEntryHint()
                } else {
                    getString(R.string.connection_entry_wireless_unavailable)
                },
                note = if (wireless && carHotspotOff()) getString(R.string.msg_car_hotspot_off, hotspotSsid()) else null,
                action = getString(R.string.connect_phone),
                onClick = { connect(true) },
            ),
        )
        addEntry(
            connectionEntry(
                title = getString(R.string.connect_with_usb),
                hint = if (wired) {
                    getString(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y)
                } else {
                    getString(R.string.connection_entry_wired_unavailable)
                },
                note = null,
                action = getString(R.string.connect_phone),
                onClick = { connect(false) },
            ),
        )
        if (chosen > 0) {
            card.addView(entries, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        }
        connectButton = button(getString(R.string.open_carplay), true) { openProjection() }
            .apply { visibility = View.GONE }
        card.addView(connectButton, matchButton(18, 68))
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
        right.addView(button(getString(R.string.settings), false) { page = "settings"; render() }, matchButton())
        right.addView(label(getString(R.string.make_diplay_feel_right_for_your_car), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
        right.addView(label("${getString(R.string.home_public_preview)}${version()}", 12, MUTED).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing = .08f
        })
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

    private fun loadConnectionSettings() {
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
    }

    /**
     * Ask for location when the driver turns reporting on. Off until the permission is actually
     * granted, so the switch can never show a state the app cannot honour.
     */
    private fun setLocationReporting(enabled: Boolean) {
        if (enabled) {
            locationReportingEnabled = true
            if (locationPermissionAvailable) {
                AirPlayPersistence.saveLocationReportingEnabled(this, true)
            } else {
                locationPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
                return
            }
        } else {
            locationReportingEnabled = false
            AirPlayPersistence.saveLocationReportingEnabled(this, false)
        }
        render()
    }

    /**
     * The hub. Each bucket is a page of its own: the two connection methods ask different things of
     * the driver and the rest of the settings have nothing to do with either, so they were only ever
     * sharing a scroll.
     */
    private fun settings(content: LinearLayout) {
        content.addView(label(getString(R.string.your_drive_your_way), 34, TEXT, true))
        content.addView(label(getString(R.string.settings_hub_hint), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        settingsEntry(content, R.string.wireless_settings, R.string.wireless_settings_hint, R.string.open_wireless_settings, R.drawable.ic_dp_connection, "wireless")
        settingsEntry(content, R.string.wired_settings, R.string.wired_settings_hint, R.string.open_wired_settings, R.drawable.ic_dp_connection, "wired")
        settingsEntry(content, R.string.display_and_audio, R.string.display_and_audio_hint, R.string.open_display_settings, R.drawable.ic_dp_display, "display")
        settingsEntry(content, R.string.system_settings, R.string.system_settings_hint, R.string.open_system_settings, R.drawable.ic_dp_permissions, "system")
    }

    private fun settingsEntry(parent: LinearLayout, title: Int, hint: Int, action: Int, icon: Int, destination: String) {
        section(parent, getString(title), icon) { card ->
            card.addView(label(getString(hint), 16, MUTED))
            card.addView(button(getString(action), false) { page = destination; render() }, matchButton(12, 60))
        }
    }

    private fun systemSettings(content: LinearLayout) {
        content.addView(label(getString(R.string.system_settings), 34, TEXT, true))
        content.addView(label(getString(R.string.system_settings_hint), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        // The certificate source and the identity the iPhone is shown are deliberately not on this
        // page. Both decide whether the car authenticates as an Apple-licensed accessory, a wrong
        // value costs CarPlay outright, and no driver can diagnose that from the screen. They stay
        // at their built-in values.
        section(content, getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
            exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                else chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            card.addView(button(getString(R.string.choose_save_location), false) { chooseReportDestination() }, matchButton(10, 60))
            if (SupabaseLogUpload.enabled(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)) {
                uploadButton = button(
                    if (uploadInProgress) getString(R.string.uploading_report) else getString(R.string.generate_and_upload_report),
                    false,
                ) { uploadGeneratedReport() }.apply { isEnabled = !uploadInProgress }
                card.addView(uploadButton, matchButton(10, 60))
            }
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getString(R.string.reports_save_to_downloads_diplay) else getString(R.string.choose_where_to_save_your_report)
            card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
        section(content, getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
            toggle(card, getString(R.string.open_after_the_car_starts), getString(R.string.availability_depends_on_your_head_unit_s_startup_settings), AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
            toggle(card, getString(R.string.report_location_to_iphone), getString(R.string.report_android_location_to_the_iphone), locationReportingEnabled) { setLocationReporting(it) }
            toggle(card, getString(R.string.debug_logs), getString(R.string.show_on_screen_debug_logs), AirPlayPersistence.loadDebugLogsEnabled(this)) {
                AirPlayPersistence.saveDebugLogsEnabled(this, it)
            }
        }
        section(content, getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
            card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            if (deviceSupportsWireless()) card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
        section(content, getString(R.string.about), R.drawable.ic_dp_about) { card ->
            card.addView(button(getString(R.string.about_diplay), false) { page = "about"; render() }, matchButton(0, 60))
        }
        languageSettings(content)
    }

    private fun displaySettings(content: LinearLayout) {
        content.addView(label(getString(R.string.display_and_audio), 34, TEXT, true))
        content.addView(label(getString(R.string.display_and_audio_hint), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(8)) })
        content.addView(label(getString(R.string.apply_reconnects_carplay_for_size_resolution_music_buffer), 17, MUTED).apply { setPadding(0, 0, 0, dp(24)) })
        section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            carPlaySizeControl(card)
            choice(card, getString(R.string.resolution), listOf(getString(R.string.resolution_native), getString(R.string.s_80_lighter_load), getString(R.string.s_60_lightest_load)), listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            // A codec is a choice between two named things, not an on/off. "Efficient video" left the
            // driver to work out which way round it was; both options now say their own name, and the
            // default is stated on the option that is the default.
            choice(card, getString(R.string.video_codec),
                listOf(getString(R.string.video_codec_h264), getString(R.string.video_codec_h265)),
                if (AirPlayPersistence.loadHevcEnabled(this)) 1 else 0) {
                AirPlayPersistence.saveHevcEnabled(this, it == 1)
            }
            toggle(card, getString(R.string.direct_video_surface), getString(R.string.direct_video_surface_description), AirPlayPersistence.loadDirectSurfaceView(this)) { AirPlayPersistence.saveDirectSurfaceView(this, it) }
            toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            toggle(card, getString(R.string.full_screen), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
            }
            // What the iPhone is told the screen measures. These decide the physical size CarPlay
            // lays out against, which is why they sit here rather than with the identity.
            val bases = AirPlayPhysicalSizeBasis.entries
            choice(card, getString(R.string.physical_size_basis),
                listOf(getString(R.string.widest_width), getString(R.string.longest_height)),
                bases.indexOf(physicalSizeBasis).coerceAtLeast(0)) {
                physicalSizeBasis = bases[it]
                AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
            }
            val lengths = (AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM
                step AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP).toList()
            choice(card, getString(R.string.physical_length),
                lengths.map { "$it mm" },
                lengths.indexOf(widthPhysicalMm).coerceAtLeast(0)) {
                widthPhysicalMm = lengths[it]
                AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
            }
        }
        section(content, getString(R.string.audio_routing)) { card ->
            val channelTitle = label(getString(R.string.navigation_stream_type), 18, TEXT, true)
            val channelHint = label(getString(R.string.tap_a_number_to_test_14_driver_speaker_on_byd), 14, MUTED)
            val grid = channelSelector()
            val saveButton = button(getString(R.string.save), true) {
                AirPlayPersistence.saveNavigationStreamType(this, navigationStreamType)
                toast(getString(R.string.audio_saved_value, navigationStreamType))
            }
            fun applyChannelEnabled(enabled: Boolean) {
                val alpha = if (enabled) 1f else 0.4f
                listOf(channelTitle, channelHint, saveButton).forEach {
                    it.isEnabled = enabled
                    it.alpha = alpha
                }
                for (i in 0 until grid.childCount) {
                    grid.getChildAt(i).let { child ->
                        child.isEnabled = enabled
                        child.alpha = alpha
                    }
                }
            }
            val aaosSupported = resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
            if (aaosSupported) {
                toggle(card, getString(R.string.advanced_audio_channel_mapping),
                    getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                    applyChannelEnabled(!it)
                }
            }
            card.addView(channelTitle)
            card.addView(channelHint)
            card.addView(grid)
            card.addView(saveButton, matchButton(12, 56))
            if (aaosSupported) applyChannelEnabled(!AirPlayPersistence.loadAdvancedAudioChannelMapping(this))
        }
        // The cluster map keeps a gate of its own: it draws AirPlay's alt-screen stream on a public
        // presentation display, so what it needs is that display, not the car's HUD services. It used
        // to sit inside the BYD section and was hidden wherever those services were absent.
        if (ClusterMapPresentation.findDisplay(this) != null) section(content, getString(R.string.instrument_cluster_map), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.carplay_map_on_instrument_cluster_experimental),
                getString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c),
                AirPlayPersistence.loadClusterMapEnabled(this)) {
                AirPlayPersistence.saveClusterMapEnabled(this, it)
                reconnectForClusterMap()
            }
            if (DiLink51ClusterLayout.supported()) {
                val automatic = DiLink51ClusterLayout.automatic(this)
                toggle(card, getString(R.string.follow_instrument_theme_and_map_card),
                    getString(R.string.show_the_side_map_only_when_its_card_is_open_and_switch_to), automatic) {
                    DiLink51ClusterLayout.saveAutomatic(this, it)
                    render()
                    reconnectForClusterMap()
                }
                val allowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                    DiLink51ClusterMonitor.hasAccess(this)
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
                choice(card, getString(R.string.dashboard_shows), listOf(
                    getString(R.string.dashboard_content_map),
                    getString(R.string.dashboard_content_turn_card),
                    getString(R.string.dashboard_content_map_with_turn_card),
                ), contents.indexOf(content)) {
                    AirPlayPersistence.saveClusterContent(this, contents[it])
                    render()
                }
                // Both contents share the same safe area and position controls.
                val turnCard = content == CarPlayClusterDisplay.Content.TURN_CARD
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
            }
        }
    }

    private fun about(content: LinearLayout) {
        content.addView(label(getString(R.string.diplay), 40, TEXT, true))
        content.addView(label(getString(R.string.carplay_at_home_in_your_car), 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "${getString(R.string.about_public_preview_prefix)}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }
    }

    // The car hotspot link needs the hotspot on. Connecting now tries to switch it on itself, so
    // this only decides whether to warn first.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    // The AOSP tether action is not on every head unit; where nothing answers it, Wi-Fi settings is
    // the screen that does have the hotspot switch.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        if (packageManager.resolveActivity(hotspot, 0) == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        openSystem(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    /**
     * The wireless half of the old connection page. It keeps its three steps: a first-time setup is
     * read in order, and the steps are named as steps because that is what they are — but named for
     * the two legs they really are, the car's hotspot and the Bluetooth radio that pairs the phone,
     * rather than for a "connection" and a "pairing" that hid what was being chosen.
     *
     * Neither leg is gated on the probe. Turning the car's hotspot on in the car's own settings and
     * typing its name in is done by hand and needs nothing from us, so a head unit whose Bluetooth or
     * hotspot calls do not answer still gets the whole page; it is told what is missing, in the step
     * that is missing it, instead of the step being taken away.
     */
    private fun wirelessSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.wireless_settings), 34, TEXT, true))
        content.addView(label(getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        content.addView(supportedConnectionLabel().apply { setPadding(0, 0, 0, dp(16)) })
        blockedWireless?.let { gapsCard(content, it) }
        section(content, getString(R.string.s_1_car_hotspot)) { card -> hotspotControls(card) }
        section(content, getString(R.string.s_2_bluetooth_and_phone)) { card -> bluetoothControls(card) }
        section(content, getString(R.string.s_3_connect)) { card ->
            readinessLines(card)
            card.addView(label(getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the), 16, MUTED).apply { setPadding(0, dp(8), 0, 0) })
            card.addView(button(getString(R.string.connect_phone), true) { connect(true) }, matchButton(12, 60))
        }
    }

    /**
     * Wired CarPlay has nothing to configure: the cable is detected and the phone starts CarPlay on
     * its own. What is left is the instruction, the button, and the permission the phone needs — the
     * last of which lives on the system page rather than being said twice.
     */
    private fun wiredSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.wired_settings), 34, TEXT, true))
        content.addView(label(getString(R.string.wired_settings_hint), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        content.addView(supportedConnectionLabel().apply { setPadding(0, 0, 0, dp(16)) })
        if (UsableConnection.USB !in connectionReport().usable) return
        section(content, getString(R.string.connect_with_usb)) { card ->
            card.addView(label(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 16, MUTED))
            card.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(12, 60))
        }
    }

    /**
     * The Bluetooth radio, the adapter's startup step, and the iPhone — all on the card. Choosing any of
     * them used to open a dialog that had to be dismissed before the next step was readable, which is why
     * the step is a line here that keeps changing instead.
     *
     * The radios offered are the ones the probe says can carry the leg, not a fixed pair: a head unit whose
     * own stack cannot open the data socket is offered the adapter alone rather than a choice that cannot
     * work.
     */
    private fun bluetoothControls(card: LinearLayout) {
        val radios = connectionReport().bluetoothRadioOptions
        val hop = DiPlayPreferences.bluetoothHop(this)
        // The car's own radio is the unmarked case -- "Bluetooth" on its own means the car's -- so a lone
        // adapter, which is worth knowing you are on, is stated; a lone car radio is left unsaid.
        if (radios.size > 1 || radios.singleOrNull() == WirelessBluetoothHop.USB_ADAPTER) {
            if (radios.size > 1) {
                card.addView(label(getString(R.string.choose_bluetooth_before_phone), 16, MUTED).apply { setPadding(0, 0, 0, dp(12)) })
            }
            legChoices(card, radios.map { WirelessLegOption(getString(bluetoothRadioName(it)), null, it == hop) }) { index ->
                chooseBluetoothRadio(radios[index])
            }
        }
        blockerLines(card, bluetoothLeg = true)
        adapterStatusView()?.let { card.addView(it) }
        iphoneChoice(card)
    }

    private fun bluetoothRadioName(hop: WirelessBluetoothHop): Int = when (hop) {
        WirelessBluetoothHop.USB_ADAPTER -> R.string.bluetooth_external
        WirelessBluetoothHop.CAR -> R.string.bluetooth_builtin
    }

    private fun chooseBluetoothRadio(hop: WirelessBluetoothHop) {
        if (hop == DiPlayPreferences.bluetoothHop(this)) return
        DiPlayPreferences.saveBluetoothHop(this, hop)
        // The adapter stack may not keep running once the car's own radio is the choice.
        if (hop == WirelessBluetoothHop.CAR) releaseAdapterWatch()
        render()
    }

    private fun iphoneChoice(card: LinearLayout) {
        when (DiPlayPreferences.bluetoothHop(this)) {
            WirelessBluetoothHop.USB_ADAPTER -> externalPhoneChoice(card)
            WirelessBluetoothHop.CAR -> builtinPhoneChoice(card)
            null -> card.addView(label(getString(R.string.gap_radio), 15, MUTED).apply { setPadding(0, dp(8), 0, dp(8)) })
        }
    }

    /** The phone the adapter itself paired. It only exists once the adapter's flow has got that far. */
    private fun externalPhoneChoice(card: LinearLayout) {
        if (!UsbBluetoothRadios.present(this)) {
            // The one thing here that only the driver can fix, said before anything the phone could show:
            // without the adapter there is no radio to pair to, so a "pair the iPhone first" line would be
            // asking for something the driver cannot do yet.
            card.addView(label(getString(R.string.adapter_step_not_plugged), 15, WARNING).apply { setPadding(0, dp(8), 0, 0) })
            card.addView(button(getString(R.string.adapter_check_again), false) { render() }, matchButton(8, 60))
            return
        }
        val target = adapterWatch?.host()?.pairedTarget()
        if (target == null) {
            card.addView(label(getString(R.string.pair_the_iphone_to_the_external_radio_first), 15, MUTED).apply { setPadding(0, dp(8), 0, 0) })
            return
        }
        val chosen = DiPlayPreferences.phoneAddress(this)?.equals(target.address, ignoreCase = true) == true
        card.addView(button("${if (chosen) "✓  " else ""}${target.name ?: target.address}", chosen) {
            saveChosenPhone(target.address, target.name ?: "iPhone")
        }, matchButton(12, 60))
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun builtinPhoneChoice(card: LinearLayout) {
        vendorHop(this)?.let { hop ->
            vendorHopPhoneChoice(card, hop)
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            card.addView(button(getString(R.string.choose_iphone), false) { ensureBluetoothPermission() }, matchButton(12, 60))
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            card.addView(label(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first), 15, WARNING).apply { setPadding(0, dp(8), 0, 0) })
            card.addView(button(getString(R.string.open_bluetooth), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(8, 60))
            return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            card.addView(label(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c), 15, MUTED).apply { setPadding(0, dp(8), 0, 0) })
            card.addView(button(getString(R.string.open_bluetooth), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(8, 60))
            return
        }
        val chosenAddress = DiPlayPreferences.phoneAddress(this)
        devices.forEach { device ->
            val chosen = chosenAddress != null && chosenAddress.equals(device.address, ignoreCase = true)
            card.addView(button("${if (chosen) "✓  " else ""}${device.name ?: getString(R.string.paired_device)}", chosen) {
                saveChosenPhone(device.address, device.name ?: "iPhone")
            }, matchButton(8, 60))
        }
    }

    /**
     * The car hop on a head unit whose Bluetooth is not ours to read: the pairings belong to a service
     * this app does not own, so the list comes from the hop and so does the line describing it.
     */
    private fun vendorHopPhoneChoice(card: LinearLayout, hop: CarBluetoothHop) {
        hop.note(this)?.let { line ->
            card.addView(label(line, 15, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        }
        hop.status(this)?.let { line ->
            card.addView(label(line, 15, MUTED).apply { setPadding(0, dp(4), 0, dp(4)) })
        }
        refreshHopPhones(hop)
        // Nothing to list until the services behind the hop answer; the note above already says what it does.
        val devices = hopPhones ?: return
        if (devices.isEmpty()) {
            // No jump to the system Bluetooth page here: it lists nothing on this head unit, because
            // the car's pairings never reach the AOSP adapter.
            card.addView(label(getString(R.string.no_phone_on_car_bluetooth), 15, MUTED).apply { setPadding(0, dp(8), 0, 0) })
            return
        }
        val chosenAddress = DiPlayPreferences.phoneAddress(this)
        devices.forEach { device ->
            val chosen = chosenAddress != null && chosenAddress.equals(device.address, ignoreCase = true)
            card.addView(
                button("${if (chosen) "✓  " else ""}${device.name.ifBlank { getString(R.string.paired_device) }}", chosen) {
                    saveChosenPhone(device.address, device.name.ifBlank { "iPhone" })
                },
                matchButton(8, 60),
            )
        }
    }

    private var hopPhones: List<CarBluetoothPhone>? = null
    private var hopPhonesAt = 0L
    private var hopPhonesInFlight = false

    /**
     * Asks the hop which phones are connected, off the main thread.
     *
     * The services behind it post their `ServiceConnection` callbacks to the main looper, so waiting for
     * them from the main thread can never succeed: the page would freeze for the whole timeout and then
     * report no iPhone at all, with one connected. The answer is cached and reused for a few seconds
     * because the page redraws on every tap.
     */
    private fun refreshHopPhones(hop: CarBluetoothHop) {
        if (hopPhonesInFlight) return
        if (hopPhones != null && SystemClock.elapsedRealtime() - hopPhonesAt < HOP_DEVICES_TTL_MILLIS) return
        hopPhonesInFlight = true
        Thread({
            val devices = runCatching { hop.connectedPhones(this) }.getOrDefault(emptyList())
            runOnUiThread {
                hopPhonesInFlight = false
                if (isFinishing) return@runOnUiThread
                hopPhones = devices
                hopPhonesAt = SystemClock.elapsedRealtime()
                if (page == "wireless") render()
            }
        }, "car-hop-phones").apply { isDaemon = true }.start()
    }

    /** The hop this build ships, when it is for this head unit. */
    private fun vendorHop(context: Context): CarBluetoothHop? = CarBluetoothHops.active?.takeIf { hop ->
        runCatching { hop.applies(context) }.getOrDefault(false)
    }

    private fun gapsCard(content: LinearLayout, readiness: WirelessReadiness) {
        section(content, getString(R.string.wireless_not_ready)) { card -> readinessLines(card, readiness) }
    }

    /**
     * What is still missing before this route can connect, in one place. The connect step shows these lines
     * themselves, and a connect that was blocked shows the same lines above the steps so the reason is the
     * first thing on the page rather than the last.
     */
    private fun readinessLines(card: LinearLayout, readiness: WirelessReadiness = currentWirelessReadiness()) {
        val lines = readiness.gaps.map { gapText(it, readiness.adapter) }.toMutableList()
        val adapter = readiness.adapter
        if (adapter != null && WirelessGap.DONGLE_NOT_READY !in readiness.gaps && !adapter.flowEffective) {
            lines += adapterBringUpText(this, adapter)
        }
        lines.forEach { card.addView(label(it, 15, WARNING).apply { setPadding(0, dp(4), 0, dp(4)) }) }
    }

    /** One option of a leg of the wireless route: what it is called, what it means, whether it is the one in use. */
    private data class WirelessLegOption(val title: String, val description: String?, val chosen: Boolean)

    /**
     * One leg of the wireless route. With more than one option this is a picker; with one it is a statement,
     * because a picker that has already picked for you is a heading with a button under it — and Wi-Fi Direct
     * only appears from Android 10, so on this project's head unit the hotspot leg is always the one-option case.
     */
    private fun legChoices(parent: LinearLayout, options: List<WirelessLegOption>, onChoose: (Int) -> Unit) {
        if (options.isEmpty()) return
        if (options.size == 1) {
            val only = options.single()
            parent.addView(label(only.title, 22, TEXT, true))
            only.description?.let { parent.addView(label(it, 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) }) }
            return
        }
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        options.forEachIndexed { index, option ->
            val cell = column()
            choices.addView(
                cell,
                if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(16) }
                else LinearLayout.LayoutParams(-1, -2),
            )
            cell.addView(button("${if (option.chosen) "✓  " else ""}${option.title}", option.chosen) { onChoose(index) }, matchButton(12, 60))
            option.description?.let { cell.addView(label(it, 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) }) }
        }
    }

    /**
     * The car's hotspot: which mode it is on, what the driver has to do to it, and whether it is on now.
     *
     * The state line used to be a sentence under the edit button, inside a sub-heading set larger than the
     * step it sat in. It is this leg's own progress, so it is drawn at this leg's own level, under the two
     * buttons that can change it.
     */
    private fun hotspotControls(card: LinearLayout) {
        val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        val modes = connectionReport().wirelessModes
        if (modes.isEmpty()) {
            // No Wi-Fi service at all: there is no hotspot to point the phone at, so only the reason is left.
            blockerLines(card, bluetoothLeg = false)
            return
        }
        legChoices(card, modes.map { WirelessLegOption(wirelessModeTitle(it), wirelessModeDescription(it), it == mode) }) { index ->
            val candidate = modes[index]
            if (candidate == WirelessHotspotMode.MANUAL) {
                pendingCarHotspotSetup = true
                render()
            } else {
                pendingCarHotspotSetup = false
                applyWirelessLink(candidate)
            }
        }
        blockerLines(card, bluetoothLeg = false)
        if (mode == WirelessHotspotMode.MANUAL) {
            card.addView(label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            card.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            card.addView(button(if (pendingCarHotspotSetup) getString(R.string.save_hotspot_details_and_use_this_mode) else "${getString(R.string.edit_saved_hotspot_prefix)}${hotspotSsid()}", false) {
                editHotspotCredentials()
            }, matchButton(12, 60))
            card.addView(label(if (pendingCarHotspotSetup) getString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode) else if (carHotspotOff()) getString(R.string.hotspot_details_off) else getString(R.string.hotspot_details_saved), 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
        } else {
            card.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            card.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    private fun hotspotSsid() = hotspot().first
    private fun hotspotPassword() = hotspot().second
    private fun hotspot(): Pair<String, String> =
        cachedHotspot ?: savedOrCarHotspot(this).also { cachedHotspot = it }
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
        val ssid = EditText(this).apply { hint = getString(R.string.hotspot_name); setText(hotspotSsid()); setSingleLine() }
        val password = EditText(this).apply {
            hint = getString(R.string.hotspot_password); setText(hotspotPassword()); setSingleLine()
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
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.clipboard_usage_access), command))
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(0, 56))
        body.addView(label(getString(R.string.cluster_adb_multi_device, packageName), 14, MUTED))
        body.addView(label(getString(R.string.s_3_tap_check_and_enable_below_this_enables_the_cluster_ma), 16, TEXT))
        val status = label(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && DiLink51ClusterMonitor.hasAccess(this)) getString(R.string.permission_enabled_ready) else getString(R.string.permission_not_enabled), 16, TEXT)
        body.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.automatic_cluster_map_setup))
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton(getString(R.string.close), null)
            .setPositiveButton(getString(R.string.check_and_enable), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && DiLink51ClusterMonitor.hasAccess(this)) {
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

    private fun connect(wireless: Boolean) {
        if (wireless && !CarPlayBackgroundSession.hasSession()) {
            val readiness = currentWirelessReadiness()
            if (!readiness.ready) {
                // What is missing belongs next to the controls that fix it, not in a dialog over them.
                blockedWireless = readiness
                page = "wireless"
                render()
                return
            }
            blockedWireless = null
        }
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "wireless"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(hotspotSsid(), hotspotPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "wireless"
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        // The hotspot being off is one of the readiness gaps, so it is said on the page with the other
        // gaps rather than in a dialog of its own. That dialog could only ever have been reached with a
        // session already up, where the check above does not run.
        if (wireless && carHotspotOff()) { blockedWireless = currentWirelessReadiness(); page = "wireless"; render(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) { page = "wireless"; render(); return }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            val watch = adapterWatch.also { adapterWatch = null }
            val project = {
                if (!isFinishing && (Build.VERSION.SDK_INT < 17 || !isDestroyed)) openProjection()
            }
            if (watch == null) {
                project()
            } else {
                // The connection page claims this same stick. Wait for the release off the UI thread.
                Thread({
                    watch.closeAndWait()
                    runOnUiThread { project() }
                }, "adapter-release").start()
            }
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun connectionReport(): ConnectionSupportReport =
        cachedReport ?: DeviceConnectionSupport.inspect(this).also { cachedReport = it }

    private fun deviceSupportsWireless(): Boolean = connectionReport().wirelessCapable

    /** Which routes this head unit can run. What a route is missing is said by that route, not here. */
    private fun supportedConnectionLabel(): TextView {
        val report = connectionReport()
        val text = if (report.usable.isEmpty()) {
            getString(R.string.connection_methods_none)
        } else {
            getString(R.string.connection_methods_supported, report.usable.joinToString(" · ", transform = ::connectionMethodName))
        }
        return label(text, 15, if (report.usable.isEmpty()) WARNING else MUTED).apply { setPadding(0, 0, 0, dp(12)) }
    }

    /** Whether a blocker is about the Bluetooth leg rather than about the hotspot the phone joins. */
    private fun CarHotspotBlocker.isBluetoothLeg(): Boolean = when (this) {
        CarHotspotBlocker.NO_BLUETOOTH_ADAPTER,
        CarHotspotBlocker.BLUETOOTH_CALL_FAILED,
        CarHotspotBlocker.BLUETOOTH_PERMISSION,
        CarHotspotBlocker.BLUETOOTH_OFF,
        CarHotspotBlocker.NO_RFCOMM,
        -> true
        CarHotspotBlocker.NO_WIFI,
        CarHotspotBlocker.NO_HOTSPOT_API,
        -> false
    }

    /**
     * The reasons this route cannot be driven, under the leg each one is about. They used to be printed
     * together at the top of the page, furthest from either of the two things they were talking about.
     */
    private fun blockerLines(card: LinearLayout, bluetoothLeg: Boolean) {
        connectionReport().carHotspotNotes.filter { it.isBluetoothLeg() == bluetoothLeg }
            .forEach { card.addView(label(carHotspotNote(it), 15, WARNING).apply { setPadding(0, dp(4), 0, dp(4)) }) }
    }

    private fun carHotspotNote(blocker: CarHotspotBlocker): String = when (blocker) {
        CarHotspotBlocker.NO_WIFI -> getString(R.string.connection_block_no_wifi)
        CarHotspotBlocker.NO_HOTSPOT_API -> getString(R.string.connection_block_no_hotspot_api)
        CarHotspotBlocker.NO_BLUETOOTH_ADAPTER -> getString(R.string.connection_block_no_bluetooth)
        CarHotspotBlocker.BLUETOOTH_CALL_FAILED -> getString(R.string.connection_block_bluetooth_call)
        CarHotspotBlocker.BLUETOOTH_PERMISSION -> getString(R.string.connection_block_bluetooth_permission)
        CarHotspotBlocker.BLUETOOTH_OFF -> getString(R.string.connection_block_bluetooth_off)
        CarHotspotBlocker.NO_RFCOMM -> getString(R.string.connection_block_no_rfcomm)
    }

    private fun connectionMethodName(connection: UsableConnection): String = when (connection) {
        UsableConnection.USB -> getString(R.string.connect_with_usb)
        UsableConnection.CAR_HOTSPOT -> getString(R.string.built_in_car_hotspot)
        UsableConnection.WIFI_DIRECT -> getString(R.string.wifi_direct)
    }

    private fun wirelessModeTitle(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.MANUAL -> getString(R.string.built_in_car_hotspot)
        WirelessHotspotMode.WIFI_P2P -> getString(R.string.wifi_direct)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.localonlyhotspot)
    }

    private fun wirelessModeDescription(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_mode_manual_desc)
        WirelessHotspotMode.WIFI_P2P -> getString(R.string.hotspot_mode_p2p_desc)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
    }

    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /**
     * One way in, as a card on the home page. [note] is what is missing for this route right now,
     * said on the route itself rather than in a dialog over both of them.
     */
    private fun connectionEntry(
        title: String,
        hint: String,
        note: String?,
        action: String,
        onClick: () -> Unit,
    ): View = column().apply {
        background = rounded(SURFACE, if (note == null) BORDER else WARNING)
        setPadding(dp(22), dp(20), dp(22), dp(20))
        addView(label(title, 22, TEXT, true))
        addView(label(hint, 15, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        if (note != null) addView(label(note, 15, WARNING).apply { setPadding(0, dp(8), 0, 0) })
        val buttons = row().apply { gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(button(action, true) { onClick() }, LinearLayout.LayoutParams(0, dp(64), 1f))
        addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }

    /** The manual fallback for the hotspot pair, for when auto-fill is not what the driver wants. */
    private fun editHotspotCredentials() {
        askHotspotCredentials { ssid, password ->
            saveHotspotCredentials(ssid, password)
            pendingCarHotspotSetup = false
            applyWirelessLink(WirelessHotspotMode.MANUAL)
        }
    }

    /** What the wireless route asks of the driver, for the hotspot mode that is in effect. */
    private fun wirelessEntryHint(): String = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
        WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_hint_manual)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
        else -> getString(R.string.hotspot_hint_p2p)
    }


    /**
     * The car's own Bluetooth adapter is read straight from the system stack, and the permission that read needs
     * is the only thing that still opens a system prompt. The chooser itself is on the connection page.
     */
    private fun ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            render()
        }
    }

    private fun saveChosenPhone(address: String, name: String) {
        DiPlayPreferences.savePhone(this, address, name)
        render()
    }

    private fun externalBluetoothSelected() =
        com.shilapi.xcertplay.transport.AdapterDiscovery.runs(DiPlayPreferences.bluetoothHop(this))

    private fun adapterStatusView(): TextView? {
        if (!externalBluetoothSelected()) {
            releaseAdapterWatch()
            adapterStatus = null
            return null
        }
        if (!UsbBluetoothRadios.present(this)) {
            adapterStatus = null
            return null
        }
        val view = label("", 15, MUTED).apply { setPadding(0, dp(8), 0, dp(8)) }
        adapterStatus = view
        paintAdapterStatus(view)
        return view
    }

    /** The adapter's step goes into the line already on screen; starting up must not rebuild the page. */
    private fun paintAdapterStatus(view: TextView) {
        if (CarPlayBackgroundSession.hasSession()) {
            view.text = getString(R.string.adapter_status_while_connecting)
            view.setTextColor(MUTED)
            return
        }
        paintAdapterStep(view, observedAdapter())
    }

    private fun paintAdapterStep(view: TextView, observed: AdapterObservation) {
        val report = AdapterBringUpAssessment.assess(observed)
        view.text = adapterBringUpText(this, report)
        view.setTextColor(if (report.flowEffective) MUTED else WARNING)
    }

    private fun observedAdapter(): AdapterObservation {
        if (!externalBluetoothSelected()) {
            releaseAdapterWatch()
            return AdapterObservation(UsbBluetoothRadios.present(this), false, null, null, false).also { adapterSeen = it }
        }
        if (CarPlayBackgroundSession.hasSession()) {
            return adapterSeen ?: AdapterObservation(UsbBluetoothRadios.present(this), false, null, null, false)
        }
        if (!UsbBluetoothRadios.present(this)) {
            releaseAdapterWatch()
            return AdapterObservation(false, false, null, null, false).also { adapterSeen = it }
        }
        val watch = adapterWatch ?: AdapterWatch(this) { seen ->
            adapterSeen = seen
            adapterStatus?.let { paintAdapterStep(it, seen) }
        }.also { adapterWatch = it }
        val advertise = com.shilapi.xcertplay.transport.AdapterDiscovery.shouldAdvertise(
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this),
        )
        return watch.refresh(advertise).also { adapterSeen = it }
    }

    private fun releaseAdapterWatch() {
        adapterWatch?.close()
        adapterWatch = null
    }

    private fun registerUsbTopology() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching { registerReceiver(usbTopology, filter) }
    }

    private fun unregisterUsbTopology() = runCatching { unregisterReceiver(usbTopology) }

    /**
     * Plugging the adapter in puts its step line on the page; pulling it out takes the line away. Only a change
     * of presence redraws, so this cannot become the redraw loop the per-second bus read used to be.
     */
    private fun onUsbTopologyChanged() {
        if (!externalBluetoothSelected()) return
        val plugged = UsbBluetoothRadios.present(this)
        if (plugged == adapterPluggedSeen) return
        adapterPluggedSeen = plugged
        if (page == "home" || page == "wireless") render()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun carBluetoothOn(): Boolean {
        vendorHop(this)?.let { hop ->
            // The AOSP adapter is off on this head unit, so reading it would leave the connection page
            // stuck on CAR_BLUETOOTH_OFF for ever. What matters here is the leg the session will use.
            return runCatching { hop.ready(this) }.getOrDefault(false)
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
        return runCatching { adapter.isEnabled }.getOrDefault(false)
    }

    private fun currentWirelessReadiness(): WirelessReadiness {
        val manual = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL
        val hop = DiPlayPreferences.bluetoothHop(this)
        val plugged = UsbBluetoothRadios.present(this) && !CarPlayBackgroundSession.hasSession()
        val observed = if (plugged && com.shilapi.xcertplay.transport.AdapterDiscovery.runs(hop)) {
            observedAdapter()
        } else {
            AdapterObservation(false, false, null, null, false)
        }
        val bringUp = if (plugged || hop == WirelessBluetoothHop.USB_ADAPTER) AdapterBringUpAssessment.assess(observed) else null
        val pairedAddress = adapterWatch?.host()?.pairedTarget()?.address
        val saved = DiPlayPreferences.phoneAddress(this)
        val phoneChosen = when (hop) {
            WirelessBluetoothHop.USB_ADAPTER -> pairedAddress != null && saved?.equals(pairedAddress, ignoreCase = true) == true
            WirelessBluetoothHop.CAR -> saved != null
            null -> false
        }
        return WirelessReadinessCheck.assess(
            WirelessReadinessInput(
                credentialsSaved = !manual || hotspotError(hotspotSsid(), hotspotPassword()) == null,
                hotspotOn = !manual || !carHotspotOff(),
                hop = hop,
                phoneChosen = phoneChosen,
                carBluetoothOn = carBluetoothOn(),
                adapterPlugged = plugged,
                adapter = bringUp,
            ),
        )
    }

    private fun gapText(gap: WirelessGap, adapter: AdapterBringUp?): String = when (gap) {
        WirelessGap.HOTSPOT_CREDENTIALS -> getString(R.string.gap_hotspot_credentials)
        WirelessGap.HOTSPOT_OFF -> getString(R.string.gap_hotspot_off)
        WirelessGap.RADIO_NOT_CHOSEN -> getString(R.string.gap_radio)
        WirelessGap.CAR_BLUETOOTH_OFF -> getString(R.string.gap_car_bluetooth)
        WirelessGap.PHONE_NOT_CHOSEN -> getString(R.string.gap_phone)
        WirelessGap.DONGLE_NOT_READY -> adapter?.let { adapterBringUpText(this, it) } ?: getString(R.string.gap_adapter_missing)
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
        page = "wireless"; render()
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
        val manager = getSystemService(Context.WIFI_P2P_SERVICE) as? android.net.wifi.p2p.WifiP2pManager
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { closeP2pChannel(channel); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { closeP2pChannel(channel); if (!isFinishing && (Build.VERSION.SDK_INT < 17 || !isDestroyed)) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        closeP2pChannel(channel); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { closeP2pChannel(channel); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            closeP2pChannel(channel); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun closeP2pChannel(channel: android.net.wifi.p2p.WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT >= 27) channel.close()
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        if (lastRunning != running) {
            // The entries start a connection; this button only returns to one already running.
            connectButton?.visibility = if (running) View.VISIBLE else View.GONE
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
                val report = buildDiagnosticReport(appContext)
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
    /**
     * The part a reader needs to place the log: which build, which car, which settings. Kept separate
     * from the log text so a byte budget can never spend it — the upload path used to keep the tail of
     * the whole report, which dropped exactly this block and kept the log.
     */
    private fun buildDiagnosticHeader(appContext: android.content.Context): String = buildString {
        appendLine("DiPlay ${version()} · private beta diagnostic report")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
        appendLine("Authentication: local experimental beta identity; no remote fallback")
        appendLine("CarPlay setup: ${setupErrorDetail?.let { "authentication unavailable — $it" } ?: "ready"}")
        appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
        appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
        appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
        appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
        appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
        appendLine(DiagnosticCounters.summary())
        appendLine()
        appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
        appendLine(DisplayDiagnosticSnapshot.report(appContext))
    }

    private fun buildDiagnosticSections(appContext: android.content.Context): List<DiagnosticSection> =
        SessionLogFile.REPORT_NAMES.mapNotNull { name ->
            val file = File(appContext.filesDir, "logs/$name")
            if (!file.isFile) return@mapNotNull null
            DiagnosticSection(
                name = name,
                lines = file.useLines { lines -> lines.mapNotNull { DiagnosticRedactor.redact(it) }.toList() },
                newest = name == "diplay.log",
            )
        }

    private fun buildDiagnosticReport(appContext: android.content.Context): String =
        DiagnosticDigest.compose(
            buildDiagnosticHeader(appContext),
            buildDiagnosticSections(appContext),
        )

    /** Generates the same report as Save, then uploads that file. Only the button calls this. */
    private fun uploadGeneratedReport() {
        if (uploadInProgress) return
        uploadInProgress = true
        uploadButton?.apply { isEnabled = false; text = getString(R.string.uploading_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_KEY
        Thread({
            val result = runCatching {
                val report = buildDiagnosticReport(appContext)
                val body = report.toByteArray(Charsets.UTF_8)
                SupabaseLogUpload.post(url, key, fileName, body)
                body.size
            }
            runOnUiThread {
                uploadInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                uploadButton?.apply { isEnabled = true; text = getString(R.string.generate_and_upload_report) }
                if (result.isSuccess) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.report_uploaded))
                        .setMessage(getString(R.string.report_uploaded_detail, fileName, result.getOrThrow()))
                        .setPositiveButton(getString(R.string.done), null)
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.could_not_upload_the_report))
                        .setMessage(result.exceptionOrNull()?.message?.take(400).orEmpty())
                        .setPositiveButton(getString(R.string.close), null)
                        .show()
                }
            }
        }, "diplay-log-upload").start()
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
            toast(getString(R.string.audio_stream_unavailable, streamType, state.toString()))
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
        val content = rounded(if (selected) ACCENT else SURFACE, if (selected) ACCENT else BORDER)
        target.background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), content, null)
        } else content
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
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
                width = (resources.displayMetrics.widthPixels - dp(80)) / 7
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

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageDrawable(AppCompatResources.getDrawable(this@DiPlayActivity, icon)?.let {
                DrawableCompat.wrap(it.mutate()).apply { DrawableCompat.setTint(this, ACCENT) }
            })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    /**
     * A two-state row.
     *
     * The control is drawn here instead of using `SwitchCompat`, which under this app's theme is
     * invisible and unclickable: the theme is `android:style/Theme.Holo.NoActionBar`, not an
     * AppCompat theme, so the default switch style it resolves its thumb and track drawables from
     * does not exist. Both come back null and the switch measures to zero width — it is in the view
     * tree, it just has no size to draw or to be touched in. Every row using it read as plain text.
     */
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        var current = value
        val pill = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(88)
            minHeight = dp(48)
            isClickable = true
        }
        fun paint() {
            pill.text = getString(if (current) R.string.switch_on else R.string.switch_off)
            pill.setTextColor(if (current) BG else MUTED)
            pill.background = rounded(if (current) ACCENT else SURFACE, if (current) ACCENT else BORDER)
        }
        paint()
        pill.setOnClickListener {
            current = !current
            paint()
            save(current)
        }
        line.addView(pill)
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
    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(24), dp(24), dp(24), dp(24)) }
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
        val content = rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER)
        background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), content, null)
        } else content
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private const val HOP_DEVICES_TTL_MILLIS = 10_000L
        private val BG = Color.rgb(12, 17, 27)
        private val SURFACE = Color.rgb(21, 30, 44)
        private val BORDER = Color.rgb(42, 56, 75)
        private val ACCENT = Color.rgb(166, 200, 255)
        private val TEXT = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(168, 182, 202)
        private val WARNING = Color.rgb(255, 196, 128)
    }
}
