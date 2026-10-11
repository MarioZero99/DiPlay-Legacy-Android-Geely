package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.WirelessBluetoothHop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DeviceConnectionSupportTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun ready(sdkInt: Int, usbHost: Boolean = true) = HeadUnitProbe(
        sdkInt = sdkInt,
        usbHost = usbHost,
        wifiManager = true,
        hotspotApi = true,
        bluetooth = BluetoothRead.ON,
        rfcommSocket = true,
    )

    @Test fun android5Through9CanUseUsbAndCarHotspotWhenTheCallsAnswer() {
        val report = DeviceConnectionSupport.assess(ready(22))
        assertEquals(listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT), report.usable)
        assertEquals(listOf(WirelessHotspotMode.MANUAL), report.wirelessModes)
        assertTrue(report.wirelessCapable)
        assertTrue(report.carHotspotNotes.isEmpty())
    }

    @Test fun android10AddsWifiDirect() {
        val report = DeviceConnectionSupport.assess(ready(Build.VERSION_CODES.Q))
        assertEquals(
            listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT, UsableConnection.WIFI_DIRECT),
            report.usable,
        )
        assertEquals(listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P), report.wirelessModes)
        assertTrue(report.wirelessCapable)
    }

    // What the probe cannot drive, the driver still can: the car's hotspot is turned on in the car's
    // own settings and its name and password are typed in. These lock in that the manual route is
    // offered whatever the probe says about our own calls, which is what the head unit in the
    // emulator — no Bluetooth service at all — used to lose the whole page over.

    @Test fun theManualHotspotIsOfferedOnAHeadUnitWhoseBluetoothCannotBeCalled() {
        val missing = DeviceConnectionSupport.assess(ready(22).copy(bluetooth = BluetoothRead.MISSING))
        assertEquals(listOf(WirelessHotspotMode.MANUAL), missing.wirelessModes)
        assertFalse("the route itself is not capable", missing.wirelessCapable)

        val failed = DeviceConnectionSupport.assess(ready(22).copy(bluetooth = BluetoothRead.FAILED))
        assertEquals(listOf(WirelessHotspotMode.MANUAL), failed.wirelessModes)
        assertFalse(failed.wirelessCapable)
    }

    @Test fun theManualHotspotIsOfferedWithoutAHotspotSwitch() {
        val report = DeviceConnectionSupport.assess(ready(22).copy(hotspotApi = false))
        assertEquals(listOf(CarHotspotBlocker.NO_HOTSPOT_API), report.carHotspotNotes)
        assertEquals(listOf(WirelessHotspotMode.MANUAL), report.wirelessModes)
        assertFalse("we cannot drive the switch, so the route is not capable", report.wirelessCapable)
    }

    @Test fun theManualHotspotIsOfferedWithoutABluetoothDataSocket() {
        val report = DeviceConnectionSupport.assess(ready(22).copy(rfcommSocket = false))
        assertEquals(listOf(CarHotspotBlocker.NO_RFCOMM), report.carHotspotNotes)
        assertEquals(listOf(WirelessHotspotMode.MANUAL), report.wirelessModes)
        assertFalse(report.wirelessCapable)
    }

    @Test fun withNoWifiServiceThereIsNothingToSetUp() {
        val report = DeviceConnectionSupport.assess(ready(22).copy(wifiManager = false))
        assertEquals(listOf(CarHotspotBlocker.NO_WIFI), report.carHotspotNotes)
        assertTrue(report.wirelessModes.isEmpty())
        assertFalse(report.wirelessCapable)
    }

    /** The blocker a page renders next to the thing it is about, rather than all of them in one place. */
    @Test fun everyReasonTheRouteCannotBeDrivenIsStillReported() {
        val missing = DeviceConnectionSupport.assess(ready(22).copy(bluetooth = BluetoothRead.MISSING))
        assertEquals(listOf(CarHotspotBlocker.NO_BLUETOOTH_ADAPTER), missing.carHotspotNotes)

        val failed = DeviceConnectionSupport.assess(ready(22).copy(bluetooth = BluetoothRead.FAILED))
        assertEquals(listOf(CarHotspotBlocker.BLUETOOTH_CALL_FAILED), failed.carHotspotNotes)

        val denied = DeviceConnectionSupport.assess(ready(22).copy(bluetooth = BluetoothRead.DENIED))
        assertEquals(listOf(CarHotspotBlocker.BLUETOOTH_PERMISSION), denied.carHotspotNotes)
        // Denied still counts as callable: the service is there, this app may not query it yet.
        assertTrue(denied.wirelessCapable)
    }

    @Test fun bluetoothOffStaysOnTheSetupPageAndExplains() {
        val report = DeviceConnectionSupport.assess(ready(22).copy(bluetooth = BluetoothRead.OFF))
        assertEquals(listOf(UsableConnection.USB), report.usable)
        assertEquals(listOf(CarHotspotBlocker.BLUETOOTH_OFF), report.carHotspotNotes)
        assertEquals(listOf(WirelessHotspotMode.MANUAL), report.wirelessModes)
        assertTrue(report.wirelessCapable)
    }

    // The Bluetooth leg is chosen from the radios that can actually carry it, so the options come from
    // the same probe answers that decide whether there is a route at all.

    @Test fun theCarRadioIsTheOnlyChoiceWhenItsStackCanCarryTheLeg() {
        assertEquals(
            listOf(WirelessBluetoothHop.CAR),
            DeviceConnectionSupport.assess(ready(22)).bluetoothRadioOptions,
        )
    }

    @Test fun theAdapterIsTheOnlyChoiceWhenTheCarStackHasNoDataSocket() {
        val report = DeviceConnectionSupport.assess(ready(22).copy(rfcommSocket = false, adapterRfcomm = true))
        assertEquals(listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT), report.usable)
        assertEquals(listOf(WirelessBluetoothHop.USB_ADAPTER), report.bluetoothRadioOptions)
        assertTrue(report.carHotspotNotes.isEmpty())
    }

    @Test fun aHeadUnitWithNoBluetoothStackOffersTheHotspotThroughTheAdapter() {
        val report = DeviceConnectionSupport.assess(
            ready(22).copy(bluetooth = BluetoothRead.MISSING, adapterRfcomm = true),
        )
        assertEquals(listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT), report.usable)
        assertEquals(listOf(WirelessBluetoothHop.USB_ADAPTER), report.bluetoothRadioOptions)
        // No warning about the head unit's own stack: with the adapter supplying the hop, that is no longer a
        // reason this option cannot work, and DiPlay renders these notes next to the option in warning colour.
        assertTrue(report.carHotspotNotes.isEmpty())
    }

    /** Neither radio can carry the leg, so there is nothing to choose between and no route. */
    @Test fun withoutEitherRfcommHopTheHotspotStaysUnavailableAndNoRadioIsOffered() {
        val report = DeviceConnectionSupport.assess(ready(22).copy(rfcommSocket = false))
        assertEquals(listOf(UsableConnection.USB), report.usable)
        assertTrue(report.bluetoothRadioOptions.isEmpty())
        assertFalse(report.wirelessCapable)
        assertEquals(listOf(CarHotspotBlocker.NO_RFCOMM), report.carHotspotNotes)
    }

    @Test fun bluetoothOffWithTheAdapterStillOffersTheHotspot() {
        val report = DeviceConnectionSupport.assess(
            ready(22).copy(bluetooth = BluetoothRead.OFF, adapterRfcomm = true),
        )
        assertEquals(listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT), report.usable)
        assertEquals(listOf(CarHotspotBlocker.BLUETOOTH_OFF), report.carHotspotNotes)
    }

    /** Both legs are available and working, so both radios are offered, the adapter first. */
    @Test fun bothRadiosAreOfferedWhenBothCanCarryTheLeg() {
        val report = DeviceConnectionSupport.assess(ready(22).copy(adapterRfcomm = true))
        assertEquals(
            listOf(WirelessBluetoothHop.USB_ADAPTER, WirelessBluetoothHop.CAR),
            report.bluetoothRadioOptions,
        )
    }

    @Test fun theAdapterCannotSubstituteForTheHotspotCalls() {
        val report = DeviceConnectionSupport.assess(
            ready(22).copy(wifiManager = false, adapterRfcomm = true),
        )
        assertEquals(listOf(UsableConnection.USB), report.usable)
        assertTrue(report.wirelessModes.isEmpty())
        assertEquals(listOf(CarHotspotBlocker.NO_WIFI), report.carHotspotNotes)
    }

    @Test fun aSuppliedHopCarriesTheRfcommLegTheVendorStackCannot() {
        val report = DeviceConnectionSupport.assess(
            ready(22).copy(rfcommSocket = false, vendorHopRfcomm = true),
        )
        assertEquals(listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT), report.usable)
        assertEquals(listOf(WirelessBluetoothHop.CAR), report.bluetoothRadioOptions)
        assertTrue(report.wirelessCapable)
        assertTrue(report.carHotspotNotes.isEmpty())
    }

    @Test fun aHeadUnitWithNoAospBluetoothOffersTheHotspotThroughASuppliedHop() {
        val report = DeviceConnectionSupport.assess(
            ready(22).copy(bluetooth = BluetoothRead.MISSING, vendorHopRfcomm = true),
        )
        assertEquals(listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT), report.usable)
        assertEquals(listOf(WirelessBluetoothHop.CAR), report.bluetoothRadioOptions)
        assertTrue(report.carHotspotNotes.isEmpty())
    }
}
