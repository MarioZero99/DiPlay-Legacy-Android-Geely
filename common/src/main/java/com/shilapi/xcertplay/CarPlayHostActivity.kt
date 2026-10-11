package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.media.MediaFormat
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.CompoundButtonCompat
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayFailureReason
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ConnectionProgress
import com.shilapi.xcertplay.orchestration.ConnectionStep
import com.shilapi.xcertplay.orchestration.ConnectionStepState
import com.shilapi.xcertplay.orchestration.connectionLadder
import com.shilapi.xcertplay.orchestration.connectionProgress
import com.shilapi.xcertplay.orchestration.connectionProgressInitial
import com.shilapi.xcertplay.orchestration.connectionStepOf
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.UsbDeviceId
import com.shilapi.xcertplay.transport.WiredLinkFinding
import com.shilapi.xcertplay.transport.WiredLinkReport
import com.shilapi.xcertplay.transport.hci.ActionsBluetooth
import com.shilapi.xcertplay.transport.hci.AdapterBluetoothState
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Full-screen CarPlay host. It renders decoded video through a TextureView or, when direct
 * drawing is on, a SurfaceView. It forwards touch to the active AirPlay session and drives
 * wired or wireless bring-up through [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private var connectionPanel: View? = null
    private var wifiRecoveryButton: View? = null
    private var statusLamp: View? = null
    private var stepListContainer: LinearLayout? = null
    private var stepGlyphs: List<TextView> = emptyList()
    private var stepLabels: List<TextView> = emptyList()
    private var stepDetails: List<TextView> = emptyList()
    private var renderedProgress: ConnectionProgress? = null
    private var headlineView: TextView? = null
    private var primaryAction: Button? = null
    private var handshakeBody: View? = null
    private var handshakeToggle: TextView? = null

    /**
     * The rung the last non-failed status named, and the rung a failure is reported on.
     *
     * [CarPlayStatus.Failed] carries a message and not a rung, so the ladder has to remember where
     * the attempt was when it stopped. Reading it off the previous status is exact: the controller
     * reports the failure from the step that raised it, before any later status can arrive.
     */
    private var lastStep: ConnectionStep? = null
    private var failedAt: ConnectionStep? = null

    /**
     * The wired-link fault the controller last reported, or null when it reported none. It drives the
     * primary button: the assessment already works out what is wrong, and a screen that only printed
     * that to the log left the user pressing "Reconnect" at a cable that was never plugged in.
     */
    private var wiredFinding: WiredLinkFinding? = null
    private var reconnectAttempts = 0
    private lateinit var airPlayIdentity: AirPlayIdentity
    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.LOCAL,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = "DiPlay",
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "DIPLAY-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", ""),
            firmwareVersion = "0.1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
        ),
        label = "DiPlay",
        hostName = "diplay-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", "").lowercase(),
        hostMac = DiPlayBootstrap.deviceId(airPlayIdentity).split(":").map { it.toInt(16).toByte() }.toByteArray(),
        wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(this),
        wirelessBluetoothHop = DiPlayPreferences.bluetoothHop(this) ?: com.shilapi.xcertplay.transport.WirelessBluetoothHop.CAR,
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        locationReportingEnabled = locationReportingEnabled,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setStatus(getString(R.string.vpn_consent_was_denied))
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                AirPlayPersistence.saveLocationReportingEnabled(this@CarPlayHostActivity, false)
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            requestStartupPrerequisites()
        }

    private var videoView: View? = null
    private var directSurfaceView = false
    /** TextureView surfaces are wrappers we allocate. SurfaceHolder surfaces belong to the framework. */
    private var videoSurfaceOwned = false
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var handshakeLogView: TextView? = null
    private var handshakeScroll: ScrollView? = null
    private var usbListView: TextView? = null
    private var lastUsbList = ""

    /** Owns the USB Bluetooth adapter for as long as this page is alive. */
    private var adapterSession: ActionsAdapterSession? = null

    /** The adapter's broadcast name and its pairing hint; both stay hidden until the name is written. */
    private var adapterNameView: TextView? = null
    private var adapterHintView: TextView? = null
    private var adapterNamed = false

    /** Where the adapter is in plain words. On the car there is often no adb, so the screen is the log. */
    private var adapterStatusView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var clusterPresentation: ClusterMapPresentation? = null
    private var clusterSurface: Surface? = null
    private var clusterMonitor: DiLink51ClusterMonitor? = null
    private var detectedCluster = ClusterActivityState.Snapshot(null, false)
    // Keep one surface per layer alive, including while its map card is hidden.
    private val clusterLayers = mutableMapOf<Boolean, ClusterMapPresentation>()
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var uiScalePercent = CarPlayUiScale.DEFAULT
    private var displayDiagnosticAttempt: String? = null
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var debugLogsEnabled = false
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    /**
     * The transport this page is for, fixed when the page is created. The choice is made on the
     * DiPlay home screen; nothing on this page may change it, or the ladder and the handshake could
     * be looking at two different transports.
     *
     * Lazy on purpose: Android builds an activity before it attaches a base context, so a property
     * initializer that reads a preference runs against a null one and takes the app down as the screen
     * opens. The first read is in [onCreate], by which point the context is attached.
     */
    private val wirelessEnabled by lazy { AirPlayPersistence.loadWirelessEnabled(this) }
    private var mfiTarget = MfiTarget.USB_CH341
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus = HotspotStatus(state = "off")
    private var latestStage = "Preparing CarPlay"
    private var darkMode = false
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var reconnectEpoch = 0
    private val handshakeLines = ArrayDeque<String>()
    private val handshakeTimestamp = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val refreshUsbList: Runnable = Runnable {
        if (shuttingDown.get()) return@Runnable
        try {
            publishUsbList()
        } catch (error: RuntimeException) {
            Log.w(TAG, "usb list refresh failed", error)
        }
        if (!shuttingDown.get()) {
            mainHandler.postDelayed(refreshUsbList, USB_LIST_INTERVAL_MILLIS)
        }
    }
    private var sessionLog: SessionLogFile? = null
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    /** The Bluetooth stack reports from its own threads; everything it says about the adapter goes through here. */
    private val adapterUi = MainThreadPoster(
        isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
        post = { block -> mainHandler.post(block) },
    )
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sessionLogQueue = ArrayBlockingQueue<Runnable>(SESSION_LOG_QUEUE_CAPACITY)
    private val sessionLogDropped = AtomicLong()
    /** Touched only on [sessionLogExecutor]. */
    private var reportedSessionLogDrops = 0L

    /**
     * One writer thread over a bounded queue, so the queue is the one part of this activity with a
     * ceiling. Transport threads feed it and must never wait on the disk, so a full queue discards
     * its oldest line rather than its newest: the newest carries the state that explains the
     * failure. The drop is counted and written into the log itself on the next line that lands.
     */
    private val sessionLogExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        sessionLogQueue,
        ThreadFactory { runnable -> Thread(runnable, "diplay-session-log").apply { isDaemon = true } },
        RejectedExecutionHandler { runnable, executor ->
            if (!executor.isShutdown && sessionLogQueue.poll() != null) {
                sessionLogDropped.incrementAndGet()
                DiagnosticCounters.noteLogQueueDrop()
                runCatching { executor.execute(runnable) }
            }
        },
    )
    /** Touched only on [sessionLogExecutor]. SimpleDateFormat is not thread-safe. */
    private val sessionLogTimestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logLines = ArrayDeque<LogEntry>()
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    existing?.release()
                    currentSurface = it
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            currentSurface?.let { surface ->
                sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
                sink?.clearSurface(SCREEN_TYPE_ALT, surface)
                surface.release()
            }
            currentSurface = null
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val surface = holder.surface
            if (!surface.isValid) return
            currentSurface = surface
            currentSurfaceTexture = null
            appendLog("SurfaceView surface created")
            attachSurface(surface)
            val frame = holder.surfaceFrame
            scheduleDisplaySize(frame.width(), frame.height())
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            scheduleDisplaySize(width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            val surface = currentSurface ?: return
            if (surface !== holder.surface) return
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            sink?.clearSurface(SCREEN_TYPE_ALT, surface)
            currentSurface = null
            appendLog("SurfaceView surface destroyed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languagePreferenceAtCreate = AppLocale.preference(this)
        if (runCatching { DiPlayBootstrap.ensure(this) }.isFailure) {
            startActivity(Intent(this, DiPlayActivity::class.java))
            finish(); return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DiagCrashHandler.install(this)
        initializeSessionLog()
        darkMode = isDarkMode(resources.configuration.uiMode)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // The settings overlay this used to unwind is gone — settings live on the DiPlay
                    // page now — so back either closes the safe-area editor or leaves the page.
                    if (safeAreaEditorActive) closeSafeAreaEditor() else showDiPlayHome()
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(this)
        // Size is now chosen only through CarPlaySize; ignore the canvas scale older builds stored.
        uiScalePercent = CarPlayUiScale.DEFAULT
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable = hasFineLocationPermission()
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        val storedHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        val (hotspotSsid, hotspotPassphrase) = manualHotspotCredentials(
            storedSsid = storedHotspotSsid,
            storedPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this),
            carHotspot = if (wirelessEnabled) CarHotspotStatus.accessPoint(this) else null,
        )
        manualHotspotSsid = hotspotSsid
        manualHotspotPassphrase = hotspotPassphrase
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        // Storage derives this mode from the passphrase it holds, which is not the one above when
        // the car supplied it: a WPA2 car key would run announced as an open network, and both the
        // settings page and the hotspot manager reject that mismatch outright.
        manualHotspotSecurity = manualHotspotSecurityFor(
            storedSsid = storedHotspotSsid,
            storedSecurity = AirPlayPersistence.loadManualHotspotSecurity(this),
            usedPassphrase = hotspotPassphrase,
        )
        if (storedHotspotSsid.isBlank() && hotspotSsid.isNotBlank()) {
            // Neither copy carries the key itself: one is rendered on the glass and the other goes
            // into a report the driver may hand over, and the key is the one value that stays out.
            // The mode is named because it is the one part of the pair the car does not supply, and
            // the only part a failed handover can disagree with silently.
            val taken = "STEP wifi/ap: manual hotspot credentials taken from the car's own hotspot" +
                " (security=$manualHotspotSecurity)"
            appendLog(taken)
            showHandshakeLine(taken)
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            awaitingVpnConsent = true
            vpnConsent.launch(consent)
        }
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val languagePreference = AppLocale.preference(this)
        if (Build.VERSION.SDK_INT < 33 && languagePreference != languagePreferenceAtCreate) {
            languagePreferenceAtCreate = languagePreference
            recreate()
            return
        }
        applyVideoOutputMode()
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            DiLink51ClusterLayout.automatic(this) && clusterMonitor == null) {
            clusterMonitor = DiLink51ClusterMonitor(this, ::onClusterActivityState).also { it.start() }
        } else if (!DiLink51ClusterLayout.automatic(this)) {
            clusterMonitor?.stop()
            clusterMonitor = null
        }
        ensureClusterPresentation()
        maybeStartCarPlay()
        applyFullscreenMode()
    }

    // Experimental: the CarPlay instrument-cluster stream on the BYD cluster projection display.
    private fun effectiveClusterTheme(): DiLink51ClusterLayout.Theme =
        if (DiLink51ClusterLayout.automatic(this)) detectedCluster.theme ?: DiLink51ClusterLayout.theme(this)
        else DiLink51ClusterLayout.theme(this)

    private fun onClusterActivityState(state: ClusterActivityState.Snapshot) {
        if (state != detectedCluster) appendLog("Cluster map: detected theme=${state.theme} mapVisible=${state.mapVisible}")
        detectedCluster = state
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) { dismissClusterPresentation(); return }
        ensureClusterPresentation()
    }

    private fun ensureClusterPresentation() {
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) {
            dismissClusterPresentation()
            return
        }
        val theme = effectiveClusterTheme()
        if (DiLink51ClusterLayout.supported()) {
            ensureDiLink51ClusterPresentation(theme)
            return
        }
        if (clusterPresentation != null) return
        val display = ClusterMapPresentation.findDisplay(this, theme) ?: run {
            appendLog("Cluster map: no cluster projection display among ${ClusterMapPresentation.describeDisplays(this)}")
            return
        }
        val presentation = ClusterMapPresentation(this, display, theme) { surface -> runOnUiThread { onClusterSurface(surface) } }
        // The system dismisses a presentation when its display goes away; allow a new one on resume.
        presentation.setOnDismissListener {
            if (clusterPresentation === presentation) {
                clusterPresentation = null
            }
        }
        try {
            presentation.show()
            clusterPresentation = presentation
            presentation.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
            Log.i(ClusterMapPresentation.TAG, "cluster presentation shown display=${display.displayId} name=${display.name}")
            appendLog("Cluster map: presentation shown display=${display.displayId}")
        } catch (error: RuntimeException) {
            Log.w(ClusterMapPresentation.TAG, "cluster presentation failed", error)
            appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}")
        }
    }

    private fun ensureDiLink51ClusterPresentation(theme: DiLink51ClusterLayout.Theme) {
        val fullMap = theme == DiLink51ClusterLayout.Theme.MAP
        val visible = !DiLink51ClusterLayout.automatic(this) || detectedCluster.mapVisible
        clusterLayers.filterKeys { it != fullMap }.values.forEach { it.setMapVisible(false) }
        var target = clusterLayers[fullMap]
        if (target == null) {
            val display = ClusterMapPresentation.findDisplay(this, theme) ?: return
            lateinit var presentation: ClusterMapPresentation
            presentation = ClusterMapPresentation(this, display, theme) { surface ->
                runOnUiThread {
                    if (clusterPresentation === presentation) onClusterSurface(surface)
                }
            }
            presentation.setOnDismissListener {
                if (clusterLayers[fullMap] === presentation) clusterLayers.remove(fullMap)
                if (clusterPresentation === presentation) clusterPresentation = null
            }
            try {
                presentation.setMapVisible(false)
                clusterPresentation = presentation
                clusterLayers[fullMap] = presentation
                presentation.show()
                appendLog("Cluster map: retained layer display=${display.displayId} fullMap=$fullMap")
                target = presentation
            } catch (error: RuntimeException) {
                clusterLayers.remove(fullMap)
                clusterPresentation = null
                appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}")
                return
            }
        }
        clusterPresentation = target
        target.outputSurface?.let(::onClusterSurface)
        target.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
        target.setMapVisible(visible)
    }

    private fun dismissClusterPresentation() {
        val presentations = (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()
        clusterLayers.clear()
        clusterPresentation = null
        clusterSurface?.let { sink?.clearSurface(SCREEN_TYPE_ALT, it) }
        clusterSurface = null
        presentations.forEach { runCatching { it.dismiss() } }
    }

    private fun onClusterSurface(surface: Surface?) {
        if (clusterSurface === surface) return
        // A direct handoff lets MediaCodec.setOutputSurface preserve its reference frames.
        // Clearing first would destroy the decoder and can leave stream 111 waiting for an IDR.
        if (!DiLink51ClusterLayout.supported() || surface == null) {
            clusterSurface?.let { old -> sink?.clearSurface(SCREEN_TYPE_ALT, old) }
        }
        clusterSurface = surface
        // Never fall back to the main surface: two decoders must not draw into one Surface.
        if (surface != null) sink?.setSurface(SCREEN_TYPE_ALT, surface)
    }

    private fun clusterDisplayConfig(): AirPlayDisplayConfig? {
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) return null
        val theme = effectiveClusterTheme()
        val display = ClusterMapPresentation.findDisplay(this, theme) ?: return null
        val size = ClusterMapPresentation.sizeOf(display)
        if (size.x <= 0 || size.y <= 0) return null
        if (DiLink51ClusterLayout.supported()) {
            val plan = DiLink51ClusterLayout.plan(size.x, size.y, theme) ?: return null
            return DiLink51ClusterLayout.streamConfig().also {
                appendLog("Cluster map: fixed 1920x720 stream; layout=$theme viewport=$plan")
            }
        }
        return CarPlayClusterDisplay.config(
            size.x,
            size.y,
            AirPlayPersistence.loadClusterMapScalePercent(this),
            AirPlayPersistence.loadClusterMarkerHorizontalStep(this),
            AirPlayPersistence.loadClusterMarkerVerticalStep(this),
            AirPlayPersistence.loadClusterContent(this),
        ).also {
            appendLog("Cluster map: requesting ${it.widthPixels}x${it.heightPixels} on ${size.x}x${size.y} safeArea=${it.safeArea} url=${it.initialUrl}")
        }
    }

    // The steering-wheel voice key reaches the focused window; while CarPlay is on screen it opens Siri.
    @Suppress("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!CarPlayMediaButton.opensSiri(event.keyCode)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            val sent = controller?.requestSiri() == true
            appendLog("Siri: voice key ${event.keyCode} sent=$sent")
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenMode()
    }

    override fun onStop() {
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nextDarkMode = isDarkMode(newConfig.uiMode)
        if (nextDarkMode != darkMode) {
            darkMode = nextDarkMode
            syncAirPlayDarkMode()
        }
        applyFullscreenMode()
        headlineView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        adapterSession?.dispose()
        adapterSession = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clusterMonitor?.stop()
        dismissClusterPresentation()
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(refreshUsbList)
        clearAttachedSurface()
        DiagSink.detach()
        val log = sessionLog
        sessionLog = null
        try {
            sessionLogExecutor.execute {
                try {
                    log?.append(formattedLogLine("Activity destroyed", System.currentTimeMillis()))
                } finally {
                    log?.close()
                }
            }
        } catch (_: RejectedExecutionException) {
            log?.close()
        }
        sessionLogExecutor.shutdown()
        try {
            // The writer is a daemon, so a drain that misses this deadline leaves it running.
            // SessionLogFile serialises the whole file path, so it cannot then corrupt the log
            // a later activity instance truncates and reopens.
            sessionLogExecutor.awaitTermination(SESSION_LOG_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(CONNECTION_BG) }
        val video = createVideoView()
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        // Built to fit 1920x720 without scrolling: status and the one action on the left, the bus and
        // handshake evidence on the right, every control in the top bar. The centred column this
        // replaces was roughly 750dp tall on a 720dp screen, which put its buttons below the fold.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CONNECTION_BG)
            isClickable = true
        }
        panel.addView(buildConnectionTopBar(), LinearLayout.LayoutParams(-1, dp(TOP_BAR_HEIGHT_DP)))
        panel.addView(buildConnectionBody(), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        // The safe area is a rectangle on the CarPlay canvas, and the canvas size is only known
        // here, so the editor lives on this page rather than with the rest of the settings. It was
        // built for a settings overlay nothing opens, which left the feature unreachable.
        root.addView(
            buildSafeAreaEditor().apply { visibility = View.GONE }.also { safeAreaEditor = it },
            FrameLayout.LayoutParams(-1, -1),
        )
        videoView = video
        connectionPanel = panel
        updateConnectionAction()
        updateDebugOverlays()
        mainHandler.post(refreshUsbList)
        return root
    }

    /** What this screen is, which transport it will use, and the way back to the rest of the app. */
    private fun buildConnectionTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), 0, dp(28), 0)
        }
        bar.addView(TextView(this).apply {
            text = getString(R.string.diplay)
            textSize = 24f
            setTextColor(CONNECTION_PRIMARY)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        bar.addView(buildTransportBadge(), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(28) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(barButton(getString(R.string.settings)) { openSettingsMenu() })
        bar.addView(
            barButton(getString(R.string.safe_area)) { openSafeAreaEditor() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) },
        )
        bar.addView(
            barButton(getString(R.string.back_to_diplay)) { showDiPlayHome() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) },
        )
        return bar
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(CONNECTION_PRIMARY)
        setPadding(dp(22), dp(11), dp(22), dp(11))
        background = GradientDrawable().apply {
            setColor(CONNECTION_CHIP)
            cornerRadius = dp(18).toFloat()
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    /**
     * The transport this page was opened for, stated so it can be read at a glance. It is not a
     * control: the choice is made on the DiPlay home screen, and this page is entered for one
     * transport or the other. Offering the other one here let a driver flip the ladder under a
     * handshake that had already started, so the choice is locked once the page is open.
     */
    private fun buildTransportBadge(): View = TextView(this).apply {
        text = getString(
            if (wirelessEnabled) R.string.connection_tab_wireless else R.string.connection_tab_wired,
        )
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(CONNECTION_TAB_TEXT)
        setPadding(dp(22), dp(10), dp(22), dp(10))
        background = GradientDrawable().apply {
            setColor(CONNECTION_ACCENT)
            cornerRadius = dp(17).toFloat()
        }
    }

    /** What the user has to do on the phone for the transport that is selected. */
    private fun connectionHintText(): String = getString(
        if (wirelessEnabled) R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow
        else R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an,
    )

    private fun buildConnectionBody(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(28), 0, dp(28), dp(24))
        }
        body.addView(buildStatusColumn(), LinearLayout.LayoutParams(0, -1, 1.15f))
        body.addView(
            buildDiagnosticColumn(),
            LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(24) },
        )
        return body
    }

    /** What is happening, how far along it is, and the one thing to do about it. */
    private fun buildStatusColumn(): View {
        val column = card()
        val lampRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val lamp = View(this).apply { background = lampDrawable(CONNECTION_LAMP_IDLE) }
        statusLamp = lamp
        lampRow.addView(lamp, LinearLayout.LayoutParams(dp(20), dp(20)))
        val headline = TextView(this).apply {
            text = getString(R.string.connection_headline_connecting)
            textSize = 26f
            setTextColor(CONNECTION_PRIMARY)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        headlineView = headline
        lampRow.addView(headline, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        column.addView(lampRow)
        val hint = TextView(this).apply {
            text = connectionHintText()
            textSize = 17f
            setTextColor(CONNECTION_MUTED)
            setPadding(0, dp(10), 0, 0)
        }
        column.addView(hint)
        // The ladder is the status readout. It used to be four dots with the whole explanation in a
        // single line above them, so a driver could see that something was stuck without seeing
        // which of the four it was.
        val steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stepListContainer = steps
        column.addView(steps, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        rebuildStepList()
        // The rows exist now; paint them with whatever the page had already worked out.
        renderConnectionProgress(renderedProgress ?: connectionProgressInitial(transport()))
        column.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val primary = Button(this).apply {
            text = getString(R.string.retry_connection)
            isAllCaps = false
            textSize = 20f
            setTextColor(CONNECTION_ON_ACCENT)
            background = GradientDrawable().apply {
                setColor(CONNECTION_ACCENT)
                cornerRadius = dp(22).toFloat()
            }
            setOnClickListener { retryConnectionNow() }
        }
        primaryAction = primary
        column.addView(primary, LinearLayout.LayoutParams(-1, dp(72)))
        val recovery = Button(this).apply {
            text = getString(R.string.reset_carplay_wi_fi)
            isAllCaps = false
            textSize = 18f
            setTextColor(CONNECTION_PRIMARY)
            background = GradientDrawable().apply {
                setColor(CONNECTION_CHIP)
                cornerRadius = dp(20).toFloat()
            }
            visibility = View.GONE
            setOnClickListener { showDiPlayHome("wireless-recovery") }
        }
        wifiRecoveryButton = recovery
        column.addView(recovery, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(12) })
        column.addView(TextView(this).apply {
            text = getString(R.string.in_carplay_swipe_down_with_three_fingers_to_open_diplay_se)
            textSize = 13f
            setTextColor(CONNECTION_MUTED)
            setPadding(0, dp(16), 0, 0)
        })
        return column
    }

    /** The bus and the handshake, kept apart from the status so neither crowds the other out. */
    private fun buildDiagnosticColumn(): View {
        val column = card()
        val adapterName = TextView(this).apply {
            text = ActionsBluetooth.BROADCAST_NAME
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(CONNECTION_PRIMARY)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            visibility = View.GONE
        }
        column.addView(adapterName, LinearLayout.LayoutParams(-1, -2))
        val adapterHint = TextView(this).apply {
            text = getString(R.string.adapter_pair_hint)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(CONNECTION_MUTED)
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        column.addView(adapterHint, LinearLayout.LayoutParams(-1, -2))
        val adapterStatus = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(CONNECTION_ATTENTION)
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        column.addView(adapterStatus, LinearLayout.LayoutParams(-1, -2))
        adapterNameView = adapterName
        adapterHintView = adapterHint
        adapterStatusView = adapterStatus

        column.addView(sectionLabel(getString(R.string.usb_devices)))
        val usbText = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(CONNECTION_MONO)
            setLineSpacing(0f, 1.12f)
            text = getString(R.string.usb_list_empty)
        }
        usbListView = usbText
        column.addView(
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = true
                addView(usbText, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )

        val handshakeHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        handshakeHeader.addView(
            sectionLabel(getString(R.string.handshake_steps)),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val toggle = TextView(this).apply {
            text = getString(R.string.connection_diagnostics_show)
            textSize = 15f
            setTextColor(CONNECTION_ACCENT)
            setPadding(dp(10), dp(6), 0, dp(6))
            isClickable = true
            setOnClickListener { setHandshakeExpanded(handshakeBody?.visibility != View.VISIBLE) }
        }
        handshakeToggle = toggle
        handshakeHeader.addView(toggle, LinearLayout.LayoutParams(-2, -2))
        column.addView(handshakeHeader, LinearLayout.LayoutParams(-1, -2))

        val handshakeText = TextView(this).apply {
            // Lines logged before this view existed are already in the buffer; without seeding they
            // would never reach the glass, because nothing else ever renders the backlog.
            text = handshakeLines.joinToString("\n")
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(CONNECTION_MONO)
            setLineSpacing(0f, 1.12f)
        }
        val handshake = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            visibility = View.GONE
            addView(handshakeText, ViewGroup.LayoutParams(-1, -2))
        }
        handshakeLogView = handshakeText
        handshakeScroll = handshake
        handshakeBody = handshake
        column.addView(handshake, LinearLayout.LayoutParams(-1, 0, 1.3f))
        return column
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(24), dp(28), dp(24))
        background = GradientDrawable().apply {
            setColor(CONNECTION_CARD)
            cornerRadius = dp(18).toFloat()
        }
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(CONNECTION_MUTED)
        setPadding(0, 0, 0, dp(6))
    }

    private fun lampDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun setHandshakeExpanded(expanded: Boolean) {
        handshakeBody?.visibility = if (expanded) View.VISIBLE else View.GONE
        handshakeToggle?.text = getString(
            if (expanded) R.string.connection_diagnostics_hide else R.string.connection_diagnostics_show,
        )
    }

    private fun transport(): CarPlayTransport =
        if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED

    /**
     * Builds the rungs for the transport in effect. The two ladders are different lengths, so a
     * transport change replaces the rows rather than relabelling them.
     */
    private fun rebuildStepList() {
        val container = stepListContainer ?: return
        container.removeAllViews()
        val glyphs = mutableListOf<TextView>()
        val labels = mutableListOf<TextView>()
        val details = mutableListOf<TextView>()
        connectionLadder(transport()).forEach { step ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val glyph = TextView(this).apply {
                text = STEP_PENDING
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(CONNECTION_MUTED)
            }
            glyphs += glyph
            row.addView(glyph, LinearLayout.LayoutParams(dp(38), -2))
            val label = TextView(this).apply {
                text = stepLabel(step)
                textSize = 20f
                setTextColor(CONNECTION_MUTED)
            }
            labels += label
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            // The reason belongs under the rung it stopped on: a driver reading one sentence at the
            // top of the card cannot tell which of the rungs it is about.
            val detail = TextView(this).apply {
                textSize = 15f
                setTextColor(CONNECTION_MUTED)
                visibility = View.GONE
                setPadding(dp(38), dp(2), 0, 0)
            }
            details += detail
            container.addView(detail, LinearLayout.LayoutParams(-1, -2))
        }
        stepGlyphs = glyphs
        stepLabels = labels
        stepDetails = details
    }

    private fun stepLabel(step: ConnectionStep): String = getString(
        when (step) {
            ConnectionStep.MFI -> R.string.connection_step_mfi
            ConnectionStep.DEVICE -> R.string.connection_step_usb
            ConnectionStep.HOTSPOT -> R.string.connection_step_hotspot
            ConnectionStep.PAIRING -> R.string.connection_step_pairing
            ConnectionStep.NETWORK -> R.string.connection_step_wireless_network
            ConnectionStep.DATA_LINK ->
                if (wirelessEnabled) R.string.connection_step_wireless_link else R.string.connection_step_link
            ConnectionStep.SESSION ->
                if (wirelessEnabled) R.string.connection_step_wireless_session else R.string.connection_step_session
        },
    )

    /**
     * The lamp and the rungs, driven by the controller's own status. A failure turns the lamp red
     * and marks the rung it stopped on, because that rung is the answer to "where".
     */
    private fun updateConnectionProgress(status: CarPlayStatus) {
        if (status is CarPlayStatus.Failed) {
            // The status names no rung; the one the attempt was on when it failed is the answer.
            failedAt = lastStep
        } else {
            failedAt = null
            connectionStepOf(status, transport())?.let { lastStep = it }
        }
        renderConnectionProgress(connectionProgress(status, transport(), failedAt))
    }

    private fun renderConnectionProgress(progress: ConnectionProgress) {
        renderedProgress = progress
        val running = !progress.failed && progress.index >= progress.ladder.size
        statusLamp?.background = lampDrawable(
            when {
                progress.failed -> CONNECTION_LAMP_ERROR
                running -> CONNECTION_LAMP_ACTIVE
                else -> CONNECTION_LAMP_BUSY
            },
        )
        if (running) {
            // A session that came up is the one thing that clears a cable verdict.
            wiredFinding = null
            updateConnectionAction()
        }
        headlineView?.text = getString(
            when {
                progress.failed -> R.string.connection_headline_failed
                running -> R.string.connection_headline_connected
                else -> R.string.connection_headline_connecting
            },
        )
        progress.rows.forEachIndexed { index, row ->
            val glyph = stepGlyphs.getOrNull(index) ?: return@forEachIndexed
            glyph.text = when (row.state) {
                ConnectionStepState.DONE -> STEP_DONE
                ConnectionStepState.ACTIVE -> STEP_ACTIVE
                ConnectionStepState.FAILED -> STEP_FAILED
                ConnectionStepState.PENDING -> STEP_PENDING
            }
            glyph.setTextColor(
                when (row.state) {
                    ConnectionStepState.FAILED -> CONNECTION_LAMP_ERROR
                    ConnectionStepState.PENDING -> CONNECTION_MUTED
                    else -> CONNECTION_ACCENT
                },
            )
            stepLabels.getOrNull(index)?.setTextColor(
                if (row.state == ConnectionStepState.PENDING) CONNECTION_MUTED else CONNECTION_PRIMARY,
            )
        }
        renderStepDetail()
    }

    /** Move the latest stage sentence under the rung it belongs to, and hide the rest. */
    private fun renderStepDetail() {
        val progress = renderedProgress ?: return
        val onRung = progress.index.takeIf { it < progress.ladder.size }
        stepDetails.forEachIndexed { index, detail ->
            if (index == onRung) {
                detail.text = latestStage
                detail.setTextColor(if (progress.failed) CONNECTION_LAMP_ERROR else CONNECTION_MUTED)
                detail.visibility = View.VISIBLE
            } else {
                detail.visibility = View.GONE
            }
        }
    }

    /**
     * The primary button says what to do about the fault the assessment found. A finding that only ever
     * reached the log left the user pressing "Reconnect" under a diagnosis that said otherwise.
     */
    private fun updateConnectionAction() {
        val button = primaryAction ?: return
        val advice = if (wirelessEnabled) {
            null
        } else {
            when (wiredFinding) {
                WiredLinkFinding.NO_USB_DEVICE -> getString(R.string.connection_action_plug_in)
                WiredLinkFinding.NOT_APPLE_DEVICE -> getString(R.string.connection_action_wrong_device)
                WiredLinkFinding.LOOSE_CONTACT -> getString(R.string.connection_action_reseat)
                WiredLinkFinding.SUPPLY_DIP -> getString(R.string.connection_action_power)
                WiredLinkFinding.NONE, null -> null
            }
        }
        button.text = advice ?: getString(R.string.retry_connection)
        button.setTextColor(if (advice == null) CONNECTION_ON_ACCENT else CONNECTION_ON_ATTENTION)
        (button.background as? GradientDrawable)?.setColor(
            if (advice == null) CONNECTION_ACCENT else CONNECTION_ATTENTION,
        )
    }

    /** TextureView keeps today's path. SurfaceView skips the extra GPU texture on API 22. */
    private fun createVideoView(): View {
        directSurfaceView = AirPlayPersistence.loadDirectSurfaceView(this)
        val mode = carPlayVideoSurfaceMode(directSurfaceView)
        videoSurfaceOwned = mode == CarPlayVideoSurfaceMode.TEXTURE
        appendLog("Video output: $mode")
        return if (mode == CarPlayVideoSurfaceMode.SURFACE) {
            SurfaceView(this).apply { holder.addCallback(surfaceCallback) }
        } else {
            TextureView(this).apply {
                isOpaque = false
                surfaceTextureListener = textureListener
            }
        }
    }

    /** The connection page stays alive under Settings, so a changed switch has to swap the view here. */
    private fun applyVideoOutputMode() {
        val wanted = AirPlayPersistence.loadDirectSurfaceView(this)
        val current = videoView ?: return
        if (wanted == (current is SurfaceView)) {
            directSurfaceView = wanted
            return
        }
        val root = current.parent as? FrameLayout ?: return
        detachVideoOutput(current)
        val index = root.indexOfChild(current)
        val params = current.layoutParams
        root.removeView(current)
        val video = createVideoView()
        root.addView(video, index, params)
        videoView = video
    }

    private fun detachVideoOutput(view: View) {
        if (view is TextureView) view.surfaceTextureListener = null
        if (view is SurfaceView) view.holder.removeCallback(surfaceCallback)
        clearAttachedSurface()
    }

    private fun clearAttachedSurface() {
        val surface = currentSurface ?: return
        sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
        sink?.clearSurface(SCREEN_TYPE_ALT, surface)
        if (videoSurfaceOwned) surface.release()
        currentSurface = null
        currentSurfaceTexture = null
    }

    /** Replaces the on-screen USB list. A changed bus is also written into the handshake log. */
    private fun onAdapterState(state: AdapterBluetoothState, reason: String) {
        val line = "adapter-bt: $state ($reason)"
        // The log first, and from whatever thread the stack reports on: it must not depend on the screen being there.
        appendLog(line)
        // The state that explains a failed attempt arrives on the stack's reader or pairing-watchdog thread, and a
        // view may only be touched from the UI thread — a setText from anywhere else throws, and that throw landed
        // inside the state machine, so the line never reached the screen or the log.
        adapterUi.run {
            showHandshakeLine(line)
            // The name is only worth showing once the adapter is advertising under it, and it should stay up while
            // the iPhone is being paired so the two names can be matched. Unplugging or a fresh reset takes it away.
            when (state) {
                AdapterBluetoothState.BROADCASTING -> adapterNamed = true
                AdapterBluetoothState.IDLE,
                AdapterBluetoothState.RESETTING,
                AdapterBluetoothState.UNADVERTISED,
                -> adapterNamed = false
                else -> Unit
            }
            val visibility = if (adapterNamed) View.VISIBLE else View.GONE
            adapterNameView?.visibility = visibility
            adapterHintView?.visibility = visibility
            // The name and hint say which device to tap. This sentence says whether startup finished and which step is stuck.
            val report = com.shilapi.xcertplay.transport.AdapterBringUpAssessment.assess(
                com.shilapi.xcertplay.transport.AdapterObservation(
                    plugged = state != AdapterBluetoothState.IDLE || reason != "adapter-unplugged",
                    permissionGranted = reason != "usb-permission-denied",
                    state = state,
                    reason = reason,
                    paired = adapterSession?.host?.pairedTarget() != null,
                ),
            )
            val status = adapterBringUpText(this@CarPlayHostActivity, report)
            adapterStatusView?.text = status
            adapterStatusView?.visibility = View.VISIBLE
            if (adapterSession?.bringUpFinished == true) maybeStartCarPlay()
        }
    }

    private fun publishUsbList() {
        val manager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val devices = manager.deviceList.values.sortedBy { it.deviceName }
        val body = if (devices.isEmpty()) {
            getString(R.string.usb_list_empty)
        } else {
            devices.joinToString("\n") { device ->
                try {
                    usbListLine(device)
                } catch (error: Exception) {
                    "${device.deviceName}  unreadable ${error.javaClass.simpleName}"
                }
            }
        }
        usbListView?.text = body
        openAdapterIfPresent()
        if (body == lastUsbList) return
        lastUsbList = body
        for (line in body.lineSequence()) {
            val logged = "usb/list $line"
            showHandshakeLine(logged)
            appendLog(logged)
        }
    }

    /**
     * Starts the USB adapter when it is plugged in.
     * The name is broadcast only once the car hotspot is known on. A hidden hotspot state does not block it.
     */
    private fun openAdapterIfPresent() {
        if (!com.shilapi.xcertplay.transport.ExternalBluetoothRoute.enabled) return
        val selected = com.shilapi.xcertplay.transport.AdapterDiscovery.runs(
            DiPlayPreferences.bluetoothHop(this),
        )
        if (!wirelessEnabled || !selected) {
            if (!selected) {
                adapterSession?.dispose()
                adapterSession = null
            }
            return
        }
        val session = adapterSession
            ?: ActionsAdapterSession(this, ::onAdapterState, ::appendLog).also { adapterSession = it }
        // An adapter that is not on the bus is reported by the session's own bring-up, which settles the
        // page on "adapter unplugged" and finishes the wait. Returning here instead left the page waiting
        // for a bring-up it had never asked for, with nothing on screen to say why.
        val advertise = com.shilapi.xcertplay.transport.AdapterDiscovery.shouldAdvertise(
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this),
        )
        session.ensureOpen(advertise)
    }

    /** A finished or failed session must not leave the adapter undiscoverable. */
    private fun resumeAdapterDiscovery() {
        adapterSession?.host?.resumeDiscovery()
        openAdapterIfPresent()
    }

    private fun usbListLine(device: UsbDevice): String {
        val configs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            (0 until device.configurationCount).joinToString(",") { index ->
                val configuration = device.getConfiguration(index)
                "${configuration.id}:${configuration.interfaceCount}"
            }
        } else {
            "if=${device.interfaceCount}"
        }
        val label = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) device.productName else null
        } catch (_: Exception) {
            null
        } ?: if (device.vendorId == 0x05ac) "Apple" else null
        return UsbBusFormat.line(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            deviceSubclass = device.deviceSubclass,
            deviceProtocol = device.deviceProtocol,
            configs = configs,
            productLabel = label,
        )
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> getString(R.string.local_offline)
        MfiTarget.USB_CH341 -> getString(R.string.usb_ch341)
        MfiTarget.I2C -> getString(R.string.i2c)
        MfiTarget.REMOTE -> getString(R.string.remote)
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText(getString(R.string.safe_area), 24f, Color.WHITE, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            Button(this).apply {
                text = getString(R.string.reset)
                isAllCaps = false
                setOnClickListener { resetSafeAreaForCurrentSize() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            Button(this).apply {
                text = getString(R.string.cancel)
                isAllCaps = false
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        controls.addView(
            Button(this).apply {
                text = getString(R.string.save)
                isAllCaps = false
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = getString(R.string.starting))
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = getString(R.string.ready),
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = getString(R.string.waiting_for_paired_iphone))
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = getString(R.string.connecting_bluetooth))
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = getString(R.string.running))
            CarPlayStatus.WirelessActive ->
                hotspotStatus.copy(state = getString(R.string.active))
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = getString(R.string.starting_airplay_service))
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = getString(R.string.error))
            else -> return
        }
        updateHotspotStatusBlock()
    }

    private fun updateHotspotStatusBlock() {
        if (!wirelessEnabled) {
            hotspotStatusView?.text = getString(R.string.wireless_hotspot_off)
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append(getString(R.string.hotspot_wireless_prefix)).append(status.state)
            status.ssid?.let { append(getString(R.string.hotspot_ssid_prefix)).append(it) }
            status.backend?.let { append(getString(R.string.hotspot_backend_prefix)).append(it) }
            status.band?.let { append(getString(R.string.hotspot_band_prefix)).append(it) }
            status.channel?.let {
                append(getString(R.string.hotspot_channel_prefix)).append(if (it == 0) getString(R.string.auto_label) else it.toString())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> settingsChoiceRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this@CarPlayHostActivity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(this@CarPlayHostActivity).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(MENU_SECONDARY)
                CompoundButtonCompat.setButtonTintList(this, ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                ))
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val value = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? T ?: return@setOnCheckedChangeListener
            onSelected(value)
        }
        addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }


    private data class CanvasSupport(val supported: Boolean, val reason: String, val details: String)

    private fun largerCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport {
        return try {
        val mime = if (hevcEnabled) "video/hevc" else "video/avc"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            val decoder = (0 until MediaCodecList.getCodecCount())
                .map { MediaCodecList.getCodecInfoAt(it) }
                .firstOrNull { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
            return if (decoder == null) {
                CanvasSupport(false, "no_decoder", "Legacy decoder capability mime=$mime result=no_decoder")
            } else {
                val hardware = !decoder.name.startsWith("OMX.google.")
                CanvasSupport(hardware, if (hardware) "supported" else "software_decoder",
                    "Legacy decoder capability codec=${decoder.name} mime=$mime hardware=$hardware")
            }
        }
        // Match MediaCodec.createDecoderByType's first suitable decoder; do not silently force
        // an enlarged stream through a software decoder on a slower head unit.
        val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
        }
        if (decoder == null) {
            CanvasSupport(false, "no_decoder", "Decoder capability mime=$mime result=no_decoder")
        } else {
            val hardware = if (Build.VERSION.SDK_INT >= 29) decoder.isHardwareAccelerated
                else !decoder.name.startsWith("OMX.google.") && !decoder.name.startsWith("c2.android.")
            val video = decoder.getCapabilitiesForType(mime).videoCapabilities
            val sizeSupported = video?.isSizeSupported(display.widthPixels, display.heightPixels) == true
            val rateSupported = sizeSupported && video?.areSizeAndRateSupported(
                display.widthPixels, display.heightPixels, display.fps.toDouble()) == true
            val reason = when {
                !hardware -> "software_decoder"
                hevcEnabled && hevcSoftwareDecoderEnabled -> "software_hevc_selected"
                video == null -> "no_video_capabilities"
                !sizeSupported -> "canvas_dimensions_unsupported"
                !rateSupported -> "frame_rate_unsupported"
                else -> "supported"
            }
            CanvasSupport(reason == "supported", reason,
                "Decoder capability codec=${decoder.name} mime=$mime hardware=$hardware " +
                    "sizeSupported=$sizeSupported rateSupported=$rateSupported " +
                    "widths=${video?.supportedWidths} heights=${video?.supportedHeights} " +
                    "alignment=${video?.widthAlignment}x${video?.heightAlignment} " +
                    "fpsRange=${video?.supportedFrameRates} result=$reason")
        }
        } catch (error: Exception) {
            CanvasSupport(false, "capability_query_${error.javaClass.simpleName}",
                "Decoder capability query failed error=${error.javaClass.simpleName}")
        }
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        val physical = resolvePhysicalSize(size)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = size.width,
            heightPixels = size.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
        )
        val resolutionDisplay = CarPlayDisplayScale.apply(baseDisplay, displayScaleTenths)
        val requestedPercent = uiScalePercent
        var scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
        val candidate = scaledDisplay
        val support = when {
            uiScalePercent >= CarPlayUiScale.DEFAULT -> CanvasSupport(true, "not_enlarging", "Decoder capability enlargement check not required")
            scaledDisplay === resolutionDisplay -> CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
            else -> largerCanvasSupport(scaledDisplay)
        }
        if (!support.supported) {
            scaledDisplay = resolutionDisplay
            uiScalePercent = CarPlayUiScale.DEFAULT
            AirPlayPersistence.saveUiScalePercent(this, uiScalePercent)
            appendLog("Larger CarPlay canvas unavailable reason=${support.reason}; using Default icon and text size")
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    getString(R.string.this_head_unit_cannot_use_the_smaller_size_at_this_resolut),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        appendLog("CarPlay size=${CarPlayUiScale.label(uiScalePercent)} canvas=${scaledDisplay.widthPixels}x${scaledDisplay.heightPixels}")
        val display = scaledDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
        val requestSummary = "Display request selected=${CarPlayUiScale.label(requestedPercent)} percent=$requestedPercent " +
            "surface=${size.width}x${size.height} resolution=${displayScaleTenths * 10}% " +
            "base=${resolutionDisplay.widthPixels}x${resolutionDisplay.heightPixels} " +
            "candidate=${candidate.widthPixels}x${candidate.heightPixels} fps=$fps " +
            "codec=${if (hevcEnabled) "HEVC" else "H.264"} softwareHevc=$hevcSoftwareDecoderEnabled"
        val effectiveSummary = "Display effective percent=$uiScalePercent " +
            "canvas=${display.widthPixels}x${display.heightPixels} decision=${support.reason} " +
            "physical=${physical.widthMm}x${physical.heightMm}mm safeArea=${display.safeArea} " +
            "drawOutside=${display.safeAreaDrawOutside}"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(this, requestSummary, support.details, effectiveSummary)
        appendLog(requestSummary)
        appendLog(support.details)
        appendLog(effectiveSummary)
        return AirPlayConfig(
            deviceName = "DiPlay",
            deviceId = DiPlayBootstrap.deviceId(airPlayIdentity),
            btMac = DiPlayBluetooth.localAddress(this) ?: DiPlayBootstrap.deviceId(airPlayIdentity),
            sourceVersion = "950.7.1",
            main = display,
            cluster = clusterDisplayConfig(),
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphoneAvailable,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        // Shown in CarPlay's app list as the "back to the car" button.
        resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
    ): AndroidMediaSink {
        // Capture this session's log: late decoder shutdown must not write into a new session.
        val diagnosticLog = sessionLog
        return AndroidMediaSink(
            surface = null,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            // Read per session: a stream-type change applies on the next connect, not only on app start.
            navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this),
            onScreenStreamActiveChanged = { type, active ->
                onScreenStreamStateChanged(controllerGeneration, type, active)
            },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this),
            onAudioDiagnostic = { message ->
                diagnosticLog?.let { enqueueSessionLine(it, message) }
            },
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
        )
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    CarPlayBackgroundSession.active = true
                    reconnectAttempts = 0
                    syncAirPlayDarkMode()
                    appendLog("AirPlay session active")
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    if (activeAirPlaySession === session) activeAirPlaySession = null
                    CarPlayBackgroundSession.active = false
                    if (controllerGeneration != restartGeneration) return@runOnUiThread
                    // A finished session must not leave the adapter undiscoverable, even if the
                    // menu is open and there is no reconnect.
                    resumeAdapterDiscovery()
                    activeScreenStreamTypes.clear()
                    setConnectionStage(getString(R.string.carplay_session_ended_reconnecting))
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) return@runOnUiThread
                    resumeAdapterDiscovery()
                    activeScreenStreamTypes.clear()
                    setConnectionStage(getString(R.string.transport_error_reconnecting))
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onWiredLinkReport(report: WiredLinkReport) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) return@runOnUiThread
                    // NONE included: the action the last finding put on the button goes away with the fault.
                    wiredFinding = report.finding.takeIf { it != WiredLinkFinding.NONE }
                    updateConnectionAction()
                }
            }

            override fun onDebugLog(message: String) {
                // "TRACE ", "PHONE ", and multiline text are dropped on purpose.
                // Protocol payloads are not written to the session file, so the old
                // TRACE file branch could never run. Drop them before the main thread.
                val redacted = DiagnosticRedactor.redact(message)
                val handshake = HandshakeLog.displayLine(message)
                if (redacted == null && handshake == null) return
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    if (handshake != null) showHandshakeLine(handshake)
                    if (redacted == null) return@runOnUiThread
                    DisplayDiagnosticSnapshot.record(this@CarPlayHostActivity, displayDiagnosticAttempt, message)
                    appendLog(message)
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = { status ->
        if (controllerGeneration == restartGeneration) {
            updateHotspotStatus(status)
            val description = status.describe()
            setConnectionStage(description)
            updateConnectionProgress(status)
            when (status) {
                is CarPlayStatus.Failed -> {
                    resumeAdapterDiscovery()
                    when {
                        status.wifiResetRequired -> wifiRecoveryButton?.visibility = View.VISIBLE

                        // A refused host identity is state on the phone, not a fault on the wire, so no number
                        // of retries changes it — and each one writes the same failure into the log the car was
                        // brought out to read. The line on the page says what to do on the iPhone instead.
                        status.reason == CarPlayFailureReason.HOST_ID_REJECTED -> {
                            wifiRecoveryButton?.visibility = View.GONE
                            appendLog("Not retrying: the iPhone refused this accessory's host identity")
                        }

                        else -> {
                            wifiRecoveryButton?.visibility = View.GONE
                            reconnectAfterLoss(description)
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        controller = snapshot.controller
        sink = snapshot.sink
        CarPlayMediaKeys.bindAudioFocusTarget(snapshot.sink::onExternalAudioFocus)
        CarPlayBackgroundSession.store(snapshot.controller, snapshot.sink, snapshot.width, snapshot.height, this) { completion ->
            runOnUiThread {
                shutdown(false, "DiPlay disconnect", completion)
                finish()
            }
        }
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                getString(R.string.carplay_service_already_running)
            } else {
                getString(R.string.carplay_session_already_running)
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || handshakeResetInProgress || controller != null) return
        val controllerGeneration = restartGeneration
        val config = try {
            createRuntimeConfig()
        } catch (error: IllegalArgumentException) {
            // A setting the runtime config refuses is something the driver can fix, so it belongs on
            // the page as a stage; thrown out of here it takes the whole screen down and says nothing.
            appendLog("CarPlay cannot start with these settings: ${error.message}")
            setConnectionStage(getString(R.string.connection_settings_incomplete))
            return
        }
        val airPlayConfig = createAirPlayConfig(size)
        val locationProvider: Iap2LocationProvider? =
            if (config.locationReportingEnabled) {
                AndroidCarPlayLocationProvider(this)
            } else {
                null
            }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${CarPlayDisplayScale.label(displayScaleTenths)}) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${CarPlayDisplayScale.label(displayScaleTenths)} " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
        )
        sink = renderer
        CarPlayMediaKeys.bindAudioFocusTarget(renderer::onExternalAudioFocus)
        currentSurface?.let(::attachSurface)
        clusterSurface?.let { renderer.setSurface(SCREEN_TYPE_ALT, it) }
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
            adapterBluetooth = if (com.shilapi.xcertplay.transport.ExternalBluetoothRoute.enabled) {
                adapterSession?.host
            } else {
                null
            },
        )
        controller = next
        CarPlayMediaKeys.attach(this, next)
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height, this) { completion ->
            runOnUiThread {
                shutdown(terminateProcess = false, reason = "DiPlay disconnect", completion = completion)
                finish()
            }
        }
        try {
            val service = Intent(this, DiPlaySessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service)
            else startService(service)
            next.start()
        } catch (error: RuntimeException) {
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            shutdown(false, "foreground service could not start")
            setConnectionStage(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
        }
    }

    private fun syncAirPlayDarkMode() {
        val session = activeAirPlaySession ?: return
        val night = darkMode
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == activeDisplaySize || size == pendingDisplaySize) return
        pendingDisplaySize = size
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun applyDisplaySize(size: DisplaySize) {
        if (shuttingDown.get() || size == activeDisplaySize) return
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (shuttingDown.get()) return
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) {
            if (!adoptBackgroundSession()) mainHandler.postDelayed({ maybeStartCarPlay() }, 500)
            return
        }
        if (controller == null && adoptBackgroundSession()) return
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            handshakeResetInProgress ||
            controller != null
        ) {
            return
        }
        if (waitingForSelectedAdapter()) {
            openAdapterIfPresent()
            return
        }
        startCarPlay(size)
    }

    /** Wireless with the external radio waits until that radio's start attempt has finished. */
    private fun waitingForSelectedAdapter(): Boolean {
        if (!wirelessEnabled) return false
        if (!com.shilapi.xcertplay.transport.ExternalBluetoothRoute.enabled) return false
        if (!com.shilapi.xcertplay.transport.AdapterDiscovery.runs(DiPlayPreferences.bluetoothHop(this))) {
            return false
        }
        return adapterSession?.bringUpFinished != true
    }

    private fun reconnectAfterLoss(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || handshakeResetInProgress) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val generation = restartGeneration
        val epoch = reconnectEpoch
        val delayMillis = if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        }
        reconnectAttempts += 1
        appendLog("$reason; retrying in ${delayMillis}ms")
        showHandshakeLine("$reason; automatic retry in ${delayMillis}ms")
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (
                    shuttingDown.get() ||
                    handshakeResetInProgress ||
                    generation != restartGeneration ||
                    epoch != reconnectEpoch
                ) {
                    return@postDelayed
                }
                restartCarPlay("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        handshakeResetInProgress = true
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        sink = null
        teardownExecutor.execute {
            oldController?.close()
            oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            oldSink?.close()
            runOnUiThread {
                val queued = startAfterHandshakeReset
                startAfterHandshakeReset = false
                if (shuttingDown.get()) return@runOnUiThread
                if (generation == restartGeneration) {
                    handshakeResetInProgress = false
                    startCarPlay(size)
                } else if (queued) {
                    handshakeResetInProgress = false
                    maybeStartCarPlay()
                }
            }
        }
    }

    private fun showDiPlayHome(page: String = "home") {
        controller?.sendTouch(emptyList())
        startActivity(Intent(this, DiPlayActivity::class.java)
            .putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun openSettingsMenu() = showDiPlayHome("settings")

    private fun shutdown(terminateProcess: Boolean, reason: String, completion: () -> Unit = {}) {
        if (!shuttingDown.compareAndSet(false, true)) { completion(); return }
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.close()
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            Log.i(TAG, "shutdown complete clean=$clean")
            applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java))
            teardownExecutor.shutdown()
            mainHandler.post { completion() }
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        if (AirPlayPersistence.loadClusterMapEnabled(this)) {
            clusterSurface?.let { sink?.setSurface(SCREEN_TYPE_ALT, it) }
        } else {
            sink?.setSurface(SCREEN_TYPE_ALT, surface)
        }
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == THREE_FINGER_COUNT && !gestureSequenceActive) {
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    controller?.sendTouch(emptyList())
                    appendLog("Three-finger swipe tracking started")
                    return true
                }
            }
        }

        if (gestureSequenceActive) {
            if (!gestureTracking || event.pointerCount != THREE_FINGER_COUNT) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= dp(THREE_FINGER_SWIPE_DISTANCE_DP) &&
                    deltaY >= deltaX * THREE_FINGER_SWIPE_DIRECTION_RATIO
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                    openSettingsMenu()
                    return true
                }
            }
            return true
        }

        val contacts = CarPlayTouchMapper.contacts(event, view.width, view.height)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            if (type == SCREEN_TYPE_ALT) {
                Log.i(ClusterMapPresentation.TAG, "cluster stream active=$active")
                appendLog("Cluster map: stream active=$active")
                clusterPresentation?.setStreamActive(active)
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    /**
     * The sentence describing the step in progress. It is drawn under the rung it belongs to, not
     * at the top of the card, so "the firmware..." arrives next to the rung it is about.
     */
    private fun setConnectionStage(message: String) {
        latestStage = message
        renderStepDetail()
        showHandshakeLine(message)
        updateDebugOverlays()
    }

    /** Starts a new phone handshake now and cancels a pending automatic retry. */
    private fun retryConnectionNow() {
        if (shuttingDown.get()) return
        if (controller != null && !CarPlayBackgroundSession.isOwner(this)) return
        reconnectEpoch += 1
        reconnectScheduled = false
        reconnectAttempts = 0
        showHandshakeLine("manual retry: start a new handshake")
        appendLog("manual retry: start a new handshake")
        if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
            showHandshakeLine("manual retry: queued until the current reset finishes")
            return
        }
        val size = activeDisplaySize
        if (controller == null) {
            val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
            val locationReady = !locationReportingEnabled || locationPermissionAvailable
            when {
                size == null -> showHandshakeLine("manual retry: display size is not ready")
                !transportReady -> showHandshakeLine("manual retry: waiting for VPN or wireless permission")
                !locationReady -> showHandshakeLine("manual retry: waiting for location permission")
                !microphonePermissionResolved -> showHandshakeLine("manual retry: waiting for microphone permission")
                else -> startCarPlay(size)
            }
            maybeStartCarPlay()
            return
        }
        restartCarPlay("Manual retry")
    }

    private fun showHandshakeLine(message: String) {
        val line = HandshakeLog.displayLine(message) ?: return
        val stamped = "${handshakeTimestamp.format(Date())}  $line"
        if (handshakeLines.lastOrNull() == stamped) return
        handshakeLines.addLast(stamped)
        while (handshakeLines.size > HandshakeLog.MAX_LINES) handshakeLines.removeFirst()
        handshakeLogView?.text = handshakeLines.joinToString("\n")
        handshakeScroll?.post { handshakeScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateDebugOverlays() {
        statusScrollView?.visibility = View.GONE
        connectionPanel?.visibility = if (activeScreenStreamTypes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun appendLog(message: String) {
        val log = sessionLog ?: return
        enqueueSessionLine(log, message)
    }

    private fun enqueueSessionLine(log: SessionLogFile, message: String) {
        val nowMillis = System.currentTimeMillis()
        try {
            sessionLogExecutor.execute {
                val safe = DiagnosticRedactor.redact(message) ?: return@execute
                log.append(formattedLogLine(safe, nowMillis))
                reportDroppedSessionLines(log)
            }
        } catch (_: RejectedExecutionException) {
            // onDestroy has already shut the writer down.
        }
    }

    /**
     * Called only on [sessionLogExecutor]. One line per burst of drops, not one per dropped line:
     * a log that quietly lost evidence must say so, and a report of the loss must not itself
     * become the storm that caused it.
     */
    private fun reportDroppedSessionLines(log: SessionLogFile) {
        val dropped = sessionLogDropped.get()
        if (dropped == reportedSessionLogDrops) return
        val added = dropped - reportedSessionLogDrops
        reportedSessionLogDrops = dropped
        log.append(
            formattedLogLine(
                "$added diagnostic lines dropped under load; $dropped dropped this session",
                System.currentTimeMillis(),
            ),
        )
    }

    /** Called only on [sessionLogExecutor]. */
    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${sessionLogTimestamp.format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "DiPlay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
        // Everything the transport, media and network code logged went to logcat and nowhere else.
        // The page is the only thing that owns a session file, so it lends one to those threads. The
        // write goes through the same bounded queue, so a transport thread still never waits on a disk.
        DiagSink.attach { line -> enqueueSessionLine(activeLog, line) }
        // The pairing half of the chain ran on the connection page, whose session is gone by now and whose lines
        // this log's reset just discarded. They are the ones the adapter's run is judged by, so they are carried
        // over instead — see AdapterDiagnostics.
        val carried = AdapterDiagnostics.drain()
        if (carried.isNotEmpty()) {
            activeLog.append("--- adapter lines from the connection page, before this run ---")
            carried.forEach(activeLog::append)
            activeLog.append("--- end of those lines ---")
        }
    }

    private fun refreshLogView(nowMillis: Long) {
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        while (logLines.firstOrNull()?.timestampMillis?.let { it <= cutoff } == true) {
            logLines.removeFirst()
        }
        statusView?.text = logLines.joinToString("\n") { it.text }
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstOrNull()?.let { oldest ->
            val delay = (oldest.timestampMillis + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun applyFullscreenMode() {
        val hideTop = hideTopBar
        val hideBottom = hideBottomBar
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> getString(R.string.starting_wireless_hotspot)
        is CarPlayStatus.HotspotReady ->
            getString(R.string.status_hotspot_ready, backend, ssid, band, if (channel == 0) getString(R.string.auto_value) else channel.toString())
        CarPlayStatus.WaitingForPairedIphone -> getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive -> getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> getString(R.string.waiting_for_iphone_over_usb)
        CarPlayStatus.RequestingIphonePermission -> getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) getString(R.string.starting_airplay_service) else getString(R.string.status_attaching_ncm)
        CarPlayStatus.RunningControl -> getString(R.string.carplay_control_running)
        CarPlayStatus.ControlEnded -> getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.Failed -> failureText(reason, detail)
    }

    /**
     * The failure in the driver's language, with the exception text kept as a diagnostic tail.
     * The controller has no resources, so it reports a reason code; the sentence is ours.
     */
    private fun failureText(reason: CarPlayFailureReason, detail: String?): String {
        val sentence = getString(
            when (reason) {
                CarPlayFailureReason.MFI_USB_PERMISSION_DENIED -> R.string.failure_mfi_usb_permission_denied
                CarPlayFailureReason.MFI_USB_PERMISSION_TIMEOUT -> R.string.failure_mfi_usb_permission_timeout
                CarPlayFailureReason.IPHONE_USB_PERMISSION_DENIED -> R.string.failure_iphone_usb_permission_denied
                CarPlayFailureReason.IPHONE_USB_PERMISSION_TIMEOUT -> R.string.failure_iphone_usb_permission_timeout
                CarPlayFailureReason.CONTROL_CHANNEL_CLOSED -> R.string.failure_control_channel_closed
                CarPlayFailureReason.WIRELESS_CONTROL_CHANNEL_CLOSED ->
                    R.string.failure_wireless_control_channel_closed
                CarPlayFailureReason.TRANSPORT_ATTACH_FAILED -> R.string.failure_transport_attach_failed
                CarPlayFailureReason.HOST_ID_REJECTED -> R.string.failure_host_id_rejected
                CarPlayFailureReason.BRING_UP_FAILED -> R.string.failure_bring_up_failed
            },
        )
        return detail?.takeIf { it.isNotBlank() }?.let { "$sentence ($it)" } ?: sentence
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val USB_LIST_INTERVAL_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val SESSION_LOG_DRAIN_TIMEOUT_MILLIS = 500L
        /** Bounded so a stalled disk cannot grow the queue without limit on a 26 MB device. */
        const val SESSION_LOG_QUEUE_CAPACITY = 64

        /**
         * Rung marks. Drawn as text rather than icons because the page already ships one typeface
         * for the log and the characters carry the same meaning at any density.
         */
        const val STEP_DONE = "✓"
        const val STEP_ACTIVE = "●"
        const val STEP_FAILED = "✗"
        const val STEP_PENDING = "○"
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val THREE_FINGER_COUNT = 3
        const val THREE_FINGER_SWIPE_DISTANCE_DP = 72
        const val THREE_FINGER_SWIPE_DIRECTION_RATIO = 1.15f
        const val MAX_SETTINGS_MENU_WIDTH_PX = 1200
        val MENU_BACKGROUND = Color.rgb(12, 16, 19)
        val MENU_SECONDARY = Color.rgb(170, 180, 190)
        val MENU_ACCENT = Color.rgb(127, 205, 154)
        val MENU_ACCENT_TRACK = Color.rgb(78, 143, 102)
        val MENU_TRACK_OFF = Color.rgb(64, 74, 80)
        val MENU_BUTTON_TEXT = Color.rgb(8, 17, 11)
        val MENU_DANGER = Color.rgb(190, 45, 45)
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)

        /** The connection page. Two cards side by side, sized to land inside 720dp without scrolling. */
        val CONNECTION_BG = Color.rgb(12, 17, 27)
        val CONNECTION_CARD = Color.rgb(20, 27, 39)
        val CONNECTION_CHIP = Color.rgb(31, 41, 57)
        val CONNECTION_PRIMARY = Color.rgb(241, 245, 252)
        val CONNECTION_MUTED = Color.rgb(168, 182, 202)
        val CONNECTION_MONO = Color.rgb(186, 230, 196)
        val CONNECTION_ACCENT = Color.rgb(127, 205, 154)
        val CONNECTION_TAB_TEXT = Color.rgb(8, 17, 11)
        val CONNECTION_ON_ACCENT = Color.rgb(12, 17, 27)
        val CONNECTION_ON_ATTENTION = Color.rgb(38, 24, 4)
        val CONNECTION_ATTENTION = Color.rgb(255, 176, 92)
        val CONNECTION_LAMP_IDLE = Color.rgb(96, 108, 124)
        val CONNECTION_LAMP_BUSY = Color.rgb(255, 176, 92)
        val CONNECTION_LAMP_ACTIVE = Color.rgb(127, 205, 154)
        val CONNECTION_LAMP_ERROR = Color.rgb(232, 106, 106)
        const val TOP_BAR_HEIGHT_DP = 64
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class LogEntry(val timestampMillis: Long, val text: String)
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
internal object CarPlayBackgroundSession {
    @Volatile var active = false
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private var owner: Any? = null
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    @Synchronized fun hasSession(): Boolean = stopAction != null || stopping
    private val stopWaiters = mutableListOf<() -> Unit>()

    fun stop(completion: () -> Unit = {}) {
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) { stopWaiters.add(completion); return }
            action = stopAction
            if (action != null) { stopping = true; stopWaiters.add(completion) }
        }
        if (action == null) { completion(); return }
        action.invoke {
            val callbacks = synchronized(this) {
                stopping = false
                stopWaiters.toList().also { stopWaiters.clear() }
            }
            callbacks.forEach { it() }
        }
    }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        return Snapshot(currentController, currentSink, width, height)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int, owner: Any, stop: (() -> Unit) -> Unit) {
        this.stopAction = stop
        this.owner = owner
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        if (!keepOwner) { stopAction = null; owner = null }
        active = false
        width = 0
        height = 0
    }
}
