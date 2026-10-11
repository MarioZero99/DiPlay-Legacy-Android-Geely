package com.shilapi.xcertplay

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The settings area is a hub with one page per bucket. These lock in what the split is for: every
 * page opens from the hub, back goes to the hub rather than all the way home, and an action is
 * offered once rather than on whichever pages happened to want it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class SettingsPagesUiTest {

    private val bucket = listOf(
        Triple("Open wireless settings", "Wireless settings", "wireless"),
        Triple("Open wired settings", "Wired settings", "wired"),
        Triple("Open display and audio", "Display and audio", "display"),
        Triple("Open system settings", "System settings", "system"),
    )

    private fun open(page: String): DiPlayActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
            .putExtra("page", page)
        return Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()
    }

    /** Setup mode is restored from saved state, which is how a driver who left the page mid-setup returns to it. */
    private fun openInHotspotSetup(): DiPlayActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
            .putExtra("page", "wireless")
        val pending = Bundle().apply { putBoolean("pending_car_hotspot", true) }
        return Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup(pending).get()
    }

    private fun allViews(activity: DiPlayActivity): List<View> {
        val found = mutableListOf<View>()
        fun walk(view: View) {
            found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(activity.findViewById(android.R.id.content))
        return found
    }

    private inline fun <reified T : View> views(activity: DiPlayActivity): List<T> =
        allViews(activity).filterIsInstance<T>()

    private fun texts(activity: DiPlayActivity) =
        views<TextView>(activity).mapNotNull { it.text?.toString() }.filter { it.isNotBlank() }

    private fun onlyButton(activity: DiPlayActivity, label: String): Button {
        val found = views<Button>(activity).filter { it.text?.toString() == label }
        assertEquals("exactly one button labelled '$label'", 1, found.size)
        return found.single()
    }

    @Test fun theHubOffersEveryBucketAndNothingElse() {
        val hub = texts(open("settings"))
        bucket.forEach { (action, _, _) -> assertTrue("the hub offers '$action'", action in hub) }
        // The old single settings page is gone: its contents are behind the buckets now.
        assertFalse("no diagnostic button on the hub", "Save diagnostic report" in hub)
        assertFalse("no display control on the hub", "Resolution" in hub)
    }

    @Test fun everyBucketOpensItsOwnPage() {
        bucket.forEach { (action, heading, _) ->
            val activity = open("settings")
            onlyButton(activity, action).performClick()
            val shown = texts(activity)
            assertTrue("'$action' opens the page headed '$heading'", heading in shown)
            bucket.filter { it.first != action }.forEach { (other, _, _) ->
                assertFalse("'$action' leaves the hub, so '$other' is gone", other in shown)
            }
        }
    }

    @Test fun backFromAPageReturnsToTheHubNotHome() {
        bucket.forEach { (action, _, _) ->
            val activity = open("settings")
            onlyButton(activity, action).performClick()
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(
                "'$action' goes back to the hub",
                bucket.all { (entry, _, _) -> entry in texts(activity) },
            )
            assertFalse("back from a bucket does not leave the app", activity.isFinishing)
        }
    }

    /** The hub is a page, not the bottom of the stack: back from it is the home page. */
    @Test fun backFromTheHubReturnsToTheHomePage() {
        val activity = open("settings")
        assertTrue(
            "a page inside settings offers Back",
            views<Button>(activity).any { it.text?.toString() == "Back" },
        )
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(
            "the home page is shown",
            views<Button>(activity).any { it.text?.toString() == "Car home" },
        )
        assertFalse("the hub is behind us", "Open wireless settings" in texts(activity))
        assertFalse("back from the hub does not leave the app", activity.isFinishing)
    }

    @Test fun noPageOffersTheSameActionTwice() {
        listOf("settings", "wireless", "wired", "display", "system", "about").forEach { page ->
            val labels = views<Button>(open(page)).mapNotNull { it.text?.toString() }
            val repeated = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue("$page repeats $repeated", repeated.isEmpty())
        }
    }

    /**
     * The permission deep link used to sit on the connection page, on the wired section and on the
     * settings page at once. It lives on the system page now; this is the assertion that keeps it
     * from creeping back.
     */
    @Test fun theAppPermissionsLinkIsOfferedInOnePlace() {
        listOf("settings", "wireless", "wired", "display").forEach { page ->
            assertFalse("$page does not offer App permissions", "App permissions" in texts(open(page)))
        }
        assertTrue("the system page offers App permissions", "App permissions" in texts(open("system")))
    }

    /**
     * The car's Bluetooth screen is reached from the pairing step on the wireless page, through the
     * "Open Bluetooth" button that step shows while there is no phone paired yet. Nothing on the
     * system page reaches it: the copy that used to sit there was a second way to the same screen.
     */
    @Test fun theSystemPageDoesNotOfferTheBluetoothScreen() {
        assertFalse("the system page leaves Bluetooth to the wireless page", "Bluetooth settings" in texts(open("system")))
    }

    /** The phone is chosen where the radio it is paired to is chosen, and nowhere else. */
    @Test fun thePhoneChooserIsOnlyOnTheWirelessPage() {
        listOf("settings", "wired", "display", "system").forEach { page ->
            val labels = views<Button>(open(page)).mapNotNull { it.text?.toString() }
            assertFalse(
                "$page does not offer a phone chooser",
                labels.any { it.startsWith("Choose iPhone") },
            )
        }
    }

    /**
     * Both legs of the wireless route are on the page and the hotspot is reachable. This is the shape the
     * page used to lose: the whole thing sat behind `deviceSupportsWireless()`, so a head unit whose
     * Bluetooth or hotspot calls did not answer got a page with no way to set the hotspot up at all --
     * the one route that asks nothing of those calls, since the driver turns the car's hotspot on and
     * types its name in. The probe half of that is pinned in DeviceConnectionSupportTest; this is the page.
     */
    @Test fun theWirelessPageCarriesBothLegsAndTheConnectStep() {
        val shown = texts(open("wireless"))
        assertTrue("the hotspot leg is a step", "1 · Car hotspot (Wi-Fi)" in shown)
        assertTrue("the Bluetooth leg is a step", "2 · Bluetooth and phone" in shown)
        assertTrue("the connect step is there", "3 · Connect" in shown)
        assertTrue("the hotspot can be opened", "Open car hotspot settings" in shown)
        assertTrue("the hotspot can be edited", shown.any { it.startsWith("Edit saved hotspot") })
        assertFalse("no blocked-connect card without a blocked connect", "Wireless is not ready" in shown)
    }

    /** The step is named for the leg it is, not for the gesture the leg used to hide behind. */
    @Test fun theWirelessStepsAreNamedForTheirLegs() {
        val shown = texts(open("wireless"))
        assertFalse("nothing is called a connection choice", "1 · Choose your connection" in shown)
        assertFalse("nothing is called just a pairing", "2 · Pair your iPhone" in shown)
    }

    /**
     * A mode saved where it was offered can outlive the mode being offered. Storage keeps WIFI_P2P on Android
     * 10 and later, and the probe can still say the hotspot calls do not answer there, which leaves the manual
     * mode as the only one on offer. Drawing the saved mode anyway put the step on the Wi-Fi Direct controls:
     * the hotspot could not be opened, and the one mode on offer was drawn as a button that never showed as
     * chosen.
     */
    @Test
    @Config(sdk = [33], qualifiers = "en")
    fun aSavedHotspotModeThatIsNoLongerOfferedFallsBackToTheOneThatIs() {
        AirPlayPersistence.saveWirelessHotspotMode(RuntimeEnvironment.getApplication(), WirelessHotspotMode.WIFI_P2P)
        val activity = open("wireless")
        val shown = texts(activity)
        assertTrue("the manual mode's own controls are drawn", "Open car hotspot settings" in shown)
        assertFalse("the Wi-Fi Direct controls are not drawn", "Open car Wi-Fi settings" in shown)
        assertFalse(
            "the lone mode is stated as the one in use, not left as an unchosen button",
            views<Button>(activity).any { it.text?.toString() == "Built-in car hotspot" },
        )
    }

    /**
     * A refused connect leads the page with what it was refused for, and that card has to be recomputed rather
     * than remembered. The driver saves the hotspot details, or turns the hotspot on in the car settings, and
     * comes back — a card holding the readiness captured at the refusal went on saying it was unsaved while
     * the connect step below it said nothing was missing.
     */
    @Test fun aRefusedConnectLeadsWithWhyAndStopsSayingItOnceItIsFixed() {
        val activity = open("wireless")
        onlyButton(activity, "Connect phone").performClick()
        assertTrue("the refusal is led with", "Wireless is not ready" in texts(activity))
        assertTrue(
            "the unsaved hotspot is named",
            texts(activity).any { it.startsWith("The hotspot name and password are not saved") },
        )
        // The driver saves them, then looks again the way the app offers: out to the hub and back in.
        AirPlayPersistence.saveManualHotspotSsid(activity, "MyCar")
        AirPlayPersistence.saveManualHotspotPassphrase(activity, "hunter2hunter2")
        onlyButton(activity, "Back").performClick()
        onlyButton(activity, "Open wireless settings").performClick()
        // The card is still raised -- other gaps remain -- so its absence below is recomputation, not the card
        // having gone away, which is the shape that would make this assertion pass for the wrong reason.
        assertTrue("the card is still there", "Wireless is not ready" in texts(activity))
        assertTrue(
            "a gap that is still open is still named",
            texts(activity).any { it.startsWith("Bluetooth is chosen, but the iPhone is not") },
        )
        assertFalse(
            "the gap that was fixed is no longer said",
            texts(activity).any { it.startsWith("The hotspot name and password are not saved") },
        )
    }

    /**
     * Setup mode used to be one-way on this head unit. Its one mode is drawn as a statement, so there was no
     * other mode to tap, and the only thing that cleared the flag was saving the dialog — a driver who tapped
     * the mode to look at it, or who was sent there by a connect that found nothing usable, had to save
     * something before the page would behave normally again.
     */
    @Test fun hotspotSetupCanBeLeftWithoutSaving() {
        val activity = openInHotspotSetup()
        assertTrue("setup asks for the details", "Save hotspot details and use this mode" in texts(activity))
        onlyButton(activity, "Cancel").performClick()
        assertFalse(
            "setup is no longer being asked for",
            "Save hotspot details and use this mode" in texts(activity),
        )
        assertTrue(
            "the ordinary edit button is back",
            texts(activity).any { it.startsWith("Edit saved hotspot") },
        )
        assertFalse(
            "the way out went with the mode it left",
            views<Button>(activity).any { it.text?.toString() == "Cancel" },
        )
    }
}
