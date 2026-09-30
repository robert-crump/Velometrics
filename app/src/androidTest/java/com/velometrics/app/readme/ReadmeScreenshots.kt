package com.velometrics.app.readme

import android.app.UiModeManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velometrics.app.MainActivity
import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.demo.DemoLoop
import com.velometrics.app.demo.DemoRide
import com.velometrics.app.demo.DemoRideGenerator
import com.velometrics.app.demo.DemoTrack
import com.velometrics.app.di.ReadmeScreenshotsEntryPoint
import com.velometrics.app.domain.service.ImportProgress
import com.velometrics.app.domain.service.ImportSource
import com.velometrics.app.util.FormatUtils
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Screenshots for the README (#220, #221), taken from the #219 demo rides on an emulator. Run
 * through `./gradlew readmeScreenshots`, which also clears the app's data, sets up a clean status
 * bar and copies the PNGs to `docs/screenshots/`. Replaces the app's rides and settings, so
 * [EmulatorOnlyRule] skips it without the argument and refuses real devices.
 */
@RunWith(AndroidJUnit4::class)
class ReadmeScreenshots {
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(EmulatorOnlyRule()).around(compose)

    private lateinit var context: Context
    private lateinit var screenshots: ReadmeScreenshotCapture
    private lateinit var rides: List<DemoRide>

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        screenshots = ReadmeScreenshotCapture.cleared(context)
        context.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)

        val app = EntryPointAccessors.fromApplication(context, ReadmeScreenshotsEntryPoint::class.java)
        val assets = instrumentation.context.assets
        runBlocking {
            app.ftpHistoryRepository().save(null, DemoRideGenerator.FTP)
            // The hero loop's public start point, never the developer's real home.
            val start = assets.open(DemoLoop.SUED_LIMBURG.asset).use { DemoTrack.parseGpx(it) }.first()
            app.userSettingsRepository().saveHomeLocation(start.lat, start.lon, HOME_NAME)

            rides = DemoRideGenerator.generate(LocalDate.now(DemoRideGenerator.ZONE), assets::open)
            val sources = rides.map { ride -> ImportSource(ride.fileName) { ride.fitBytes } }
            // The same path as Home's file picker; it reclusters in the background afterwards.
            val finished = app.rideLifecycle().import(sources) { true }
                .filterIsInstance<ImportProgress.Finished>()
                .last()
            val failed = finished.results.filterNot { it is ImportResult.Success }
            assertEquals("Demo rides that didn't import: $failed", emptyList<ImportResult>(), failed)

            // Routes recluster before intervals, so intervals appearing means both are done.
            withTimeout(RECLUSTER_TIMEOUT_MS) {
                app.repeatedRoutesCache().routes.first { it.isNotEmpty() }
                app.repeatedIntervalsCache().repeatedIntervals.first { it.isNotEmpty() }
            }

            // Name each route after its loop, as a rider would, instead of "Repeated Route N".
            val loopByStart = rides.associate { it.start to it.loop }
            val routes = app.repeatedRoutesCache().routes.value
            routes.forEach { route ->
                val loop = route.sessions.mapNotNull { loopByStart[it.sessionStart] }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return@forEach
                app.repeatedRouteRepository().renameRoute(route.id, ROUTE_NAMES.getValue(loop))
            }
            withTimeout(UI_TIMEOUT_MS) {
                app.repeatedRoutesCache().routes.first { cached -> cached.all { it.name in ROUTE_NAMES.values } }
            }
        }
    }

    @Test
    fun captureReadmeScreenshots() {
        captureHome()
        captureRide()
        captureRoute()
        captureStats()
    }

    /** Home list at scroll 0, newest ride (the Süd-Limburg hero) first. */
    private fun captureHome() {
        launchHome().use {
            screenshots.capture(compose, "home")
        }
    }

    /**
     * The hero ride's Session Detail as it opens (map fitted above the half-open drawer), then
     * with the drawer fully open and the Power card right under the docked header.
     */
    private fun captureRide() {
        launchHome().use { scenario ->
            val hero = rides.last()
            compose.onAllNodesWithText(FormatUtils.formatDate(hero.start)).onFirst().performClick()
            waitForText("Power")
            MapSettle.await(scenario, MAP_TIMEOUT_MS)
            screenshots.capture(compose, "ride")

            val drawer = scroller(containing = "Power")
            drawer.performTouchInput { swipeUp(startY = bottom - 1f, endY = top, durationMillis = 400) }
            compose.waitForIdle()
            // The drawer ignores scrolling right after a swipe opened it, until the next touch.
            drawer.performTouchInput {
                down(center)
                cancel()
            }
            compose.waitForIdle()
            val back = compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes()
            assertEquals("Drawer didn't dock its header", 1, back.size)
            val headerBottom = back.single().boundsInWindow.bottom
            // Card padding above the title, plus a small gap to the header.
            scrollTitleTo(drawer, "Power", headerBottom + dp(16f + 8f))
            screenshots.capture(compose, "ride-power")
        }
    }

    /** The Aachen loop's Route Detail, scrolled to the Duration Estimate with the scatter plots below. */
    private fun captureRoute() {
        launchHome().use { scenario ->
            // The bottom bar's tab: Home has no other "Routes" text.
            compose.onAllNodesWithText("Routes").onFirst().performClick()
            waitForText(ROUTE_NAMES.getValue(DemoLoop.AACHEN))
            compose.onAllNodesWithText(ROUTE_NAMES.getValue(DemoLoop.AACHEN)).onFirst().performClick()
            waitForText("Duration Estimate")
            MapSettle.await(scenario, MAP_TIMEOUT_MS)
            scrollTitleTo(scroller(containing = "Duration Estimate"), "Duration Estimate", belowTopAppBar())
            screenshots.capture(compose, "route")
        }
    }

    /** All-time Stats scrolled to the power curve. */
    private fun captureStats() {
        launchHome().use {
            compose.onAllNodesWithContentDescription("More options").onFirst().performClick()
            compose.onAllNodesWithText("All-time stats").onFirst().performClick()
            waitForText("Power curve")
            scrollTitleTo(scroller(containing = "Power curve"), "Power curve", belowTopAppBar())
            screenshots.capture(compose, "stats")
        }
    }

    private fun launchHome(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("${rides.size} rides")
        return scenario
    }

    private fun waitForText(text: String) {
        compose.waitUntil(UI_TIMEOUT_MS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The outermost scrollable container that holds [containing]. */
    private fun scroller(containing: String): SemanticsNodeInteraction =
        compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasText(containing))).onFirst()

    /**
     * Where a card's title goes so the card sits just under a TopAppBar: the bar's 64 dp ends
     * 8 dp below its 48 dp back button, then the screen's 16 dp gap and the card's 16 dp padding.
     */
    private fun belowTopAppBar(): Float {
        val back = compose.onAllNodes(hasContentDescription("Back")).onFirst().fetchSemanticsNode()
        return back.boundsInWindow.bottom + dp(8f + 16f + 16f)
    }

    /** Scrolls [scroller] until the first [title] text's top edge is at [top] px in the window. */
    private fun scrollTitleTo(scroller: SemanticsNodeInteraction, title: String, top: Float) {
        repeat(SCROLL_ATTEMPTS) {
            val dy = compose.onAllNodesWithText(title).onFirst().fetchSemanticsNode().boundsInWindow.top - top
            if (abs(dy) < 1f) return
            scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
            compose.waitForIdle()
        }
        throw AssertionError("Couldn't scroll \"$title\" into place")
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    private companion object {
        const val HOME_NAME = "Simpelveld"
        const val RECLUSTER_TIMEOUT_MS = 180_000L
        const val UI_TIMEOUT_MS = 30_000L
        const val MAP_TIMEOUT_MS = 60_000L
        const val SCROLL_ATTEMPTS = 3

        val ROUTE_NAMES = mapOf(
            DemoLoop.AACHEN to "Aachen",
            DemoLoop.HERZOGENRATH to "Herzogenrath",
            DemoLoop.MECHELEN to "Mechelen",
            DemoLoop.SUED_LIMBURG to "Süd-Limburg"
        )
    }
}

