package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.carhop.CarBluetoothHops
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.WirelessBluetoothHop
import java.util.UUID

/**
 * Connection methods this head unit can run.
 *
 * USB needs a USB host. Driving the car hotspot ourselves needs the calls the wireless path makes:
 * the hotspot switch and a Bluetooth radio that can reach the iPhone over RFCOMM. A radio that is
 * merely off still counts as callable. Wi-Fi Direct is only offered from Android 10. Local-only
 * hotspot is not a saved choice.
 *
 * What the probe answers is what this head unit can be *driven* to do. Setting the wireless route up
 * by hand is a different question: the driver can turn the car's hotspot on in the car's own settings
 * and type the name and password, which needs no capability of ours at all, so the manual route is
 * offered wherever there is Wi-Fi.
 */
internal enum class UsableConnection { USB, CAR_HOTSPOT, WIFI_DIRECT }

internal enum class CarHotspotBlocker {
    NO_WIFI,
    NO_HOTSPOT_API,
    NO_BLUETOOTH_ADAPTER,
    BLUETOOTH_CALL_FAILED,
    BLUETOOTH_PERMISSION,
    BLUETOOTH_OFF,
    NO_RFCOMM,
}

/** What a Bluetooth state read actually returned. [DENIED] means the service is there but this app may not query it yet. */
internal enum class BluetoothRead { ON, OFF, MISSING, DENIED, FAILED }

internal data class HeadUnitProbe(
    val sdkInt: Int,
    val usbHost: Boolean,
    val wifiManager: Boolean,
    val hotspotApi: Boolean,
    val bluetooth: BluetoothRead,
    val rfcommSocket: Boolean,
    val adapterRfcomm: Boolean = false,
    /** A hop this build supplies carries the Bluetooth leg, on a head unit whose own stack cannot. */
    val vendorHopRfcomm: Boolean = false,
)

internal data class ConnectionSupportReport(
    val usable: List<UsableConnection>,
    val carHotspotNotes: List<CarHotspotBlocker>,
    /** The hotspot modes this head unit can be set up to use, in the order they are offered. */
    val wirelessModes: List<WirelessHotspotMode>,
    /**
     * Whether the wireless route can carry CarPlay here at all, ignoring what happens to be switched
     * off right now. This is the question the home page's "wireless is unavailable, use the cable"
     * line asks; whether the route can be *configured* is not this, and is not gated on the probe.
     */
    val wirelessCapable: Boolean,
    /** The Bluetooth radios that could carry the iAP2 leg here, in the order they are offered. */
    val bluetoothRadioOptions: List<WirelessBluetoothHop>,
)

internal object DeviceConnectionSupport {
    fun assess(probe: HeadUnitProbe): ConnectionSupportReport {
        val notes = mutableListOf<CarHotspotBlocker>()
        if (!probe.wifiManager) notes += CarHotspotBlocker.NO_WIFI
        else if (!probe.hotspotApi) notes += CarHotspotBlocker.NO_HOTSPOT_API
        // With an adapter or a supplied hop carrying the Bluetooth leg, the car's own stack being absent,
        // blocked or unable to open an RFCOMM socket is no longer a reason the hotspot cannot work — and
        // these notes are rendered as warnings under the option, so saying so next to a working choice
        // would be wrong.
        val vendorNotesApply = !probe.adapterRfcomm && !probe.vendorHopRfcomm
        val vendorBluetoothCallable = when (probe.bluetooth) {
            BluetoothRead.MISSING -> {
                if (vendorNotesApply) notes += CarHotspotBlocker.NO_BLUETOOTH_ADAPTER
                false
            }
            BluetoothRead.FAILED -> {
                if (vendorNotesApply) notes += CarHotspotBlocker.BLUETOOTH_CALL_FAILED
                false
            }
            BluetoothRead.DENIED -> {
                if (vendorNotesApply) notes += CarHotspotBlocker.BLUETOOTH_PERMISSION
                true
            }
            BluetoothRead.OFF, BluetoothRead.ON -> {
                if (!probe.rfcommSocket && vendorNotesApply) notes += CarHotspotBlocker.NO_RFCOMM
                probe.rfcommSocket
            }
        }
        // A USB Bluetooth adapter, or the hop this head unit supplies, carries the RFCOMM leg the
        // vendor stack does not.
        val bluetoothCallable = vendorBluetoothCallable || probe.adapterRfcomm || probe.vendorHopRfcomm
        if (vendorBluetoothCallable && probe.bluetooth == BluetoothRead.OFF) notes += CarHotspotBlocker.BLUETOOTH_OFF
        val callsAnswer = probe.wifiManager && probe.hotspotApi && bluetoothCallable
        val radioReady = callsAnswer &&
            (probe.bluetooth == BluetoothRead.ON || probe.adapterRfcomm || probe.vendorHopRfcomm)
        val usable = buildList {
            if (probe.usbHost) add(UsableConnection.USB)
            if (radioReady) {
                add(UsableConnection.CAR_HOTSPOT)
                if (probe.sdkInt >= Build.VERSION_CODES.Q) add(UsableConnection.WIFI_DIRECT)
            }
        }
        val modes = buildList {
            // The car's own hotspot is set up by the driver, not driven by us: DiPlay records the name
            // and password and, when connecting, only tries to help the switch along. It is offered on
            // any head unit with Wi-Fi — the hotspot API and the Bluetooth radio decide what else is
            // available here, not whether the route can be configured at all.
            if (probe.wifiManager) add(WirelessHotspotMode.MANUAL)
            if (callsAnswer && probe.sdkInt >= Build.VERSION_CODES.Q) add(WirelessHotspotMode.WIFI_P2P)
        }
        val radios = buildList {
            if (probe.adapterRfcomm) add(WirelessBluetoothHop.USB_ADAPTER)
            if (vendorBluetoothCallable || probe.vendorHopRfcomm) add(WirelessBluetoothHop.CAR)
        }
        return ConnectionSupportReport(usable, notes, modes, callsAnswer, radios)
    }

    fun inspect(context: Context): ConnectionSupportReport = assess(probe(context))

    @SuppressLint("MissingPermission")
    fun probe(context: Context): HeadUnitProbe {
        val wifi = runCatching { context.getSystemService(Context.WIFI_SERVICE) as? WifiManager }.getOrNull()
        val adapter = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()
        val bluetooth = when (adapter) {
            null -> BluetoothRead.MISSING
            else -> runCatching { if (adapter.isEnabled) BluetoothRead.ON else BluetoothRead.OFF }
                .getOrElse { error -> if (error is SecurityException) BluetoothRead.DENIED else BluetoothRead.FAILED }
        }
        return HeadUnitProbe(
            sdkInt = Build.VERSION.SDK_INT,
            usbHost = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            wifiManager = wifi != null,
            hotspotApi = wifi != null && hotspotSwitchExists(),
            bluetooth = bluetooth,
            rfcommSocket = rfcommSocketExists(),
            adapterRfcomm = UsbBluetoothRadios.present(context),
            vendorHopRfcomm = vendorHopRfcomm(context),
        )
    }

    private fun hotspotSwitchExists(): Boolean = runCatching {
        WifiManager::class.java.getMethod(
            "setWifiApEnabled",
            WifiConfiguration::class.java,
            Boolean::class.javaPrimitiveType,
        )
    }.isSuccess

    private fun rfcommSocketExists(): Boolean = runCatching {
        BluetoothDevice::class.java.getMethod("createRfcommSocketToServiceRecord", UUID::class.java)
    }.isSuccess

    /**
     * Whether the hop this build supplies is worth offering: it is for this head unit and could start.
     *
     * Answering costs a round trip that forks a process on the head unit, and the connection page asks
     * this on every redraw — during layout, where a wedged service would park the page. A few seconds of
     * staleness is invisible next to that.
     */
    private fun vendorHopRfcomm(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - vendorHopProbeAt < VENDOR_HOP_PROBE_TTL_MILLIS) return vendorHopAvailable
        vendorHopAvailable = runCatching {
            val hop = CarBluetoothHops.active
            hop != null && hop.applies(context) && hop.ready(context)
        }.getOrDefault(false)
        vendorHopProbeAt = now
        return vendorHopAvailable
    }

    private const val VENDOR_HOP_PROBE_TTL_MILLIS = 5_000L
    private var vendorHopProbeAt = 0L
    private var vendorHopAvailable = false
}
