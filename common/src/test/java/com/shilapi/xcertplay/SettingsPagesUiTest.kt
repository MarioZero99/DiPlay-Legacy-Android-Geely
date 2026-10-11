package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
}
