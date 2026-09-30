package com.velometrics.app.readme

import android.app.UiModeManager
import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velometrics.app.MainActivity
import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.demo.DemoLoop
import com.velometrics.app.demo.DemoRideGenerator
import com.velometrics.app.demo.DemoTrack
import com.velometrics.app.di.ReadmeScreenshotsEntryPoint
import com.velometrics.app.domain.service.ImportProgress
import com.velometrics.app.domain.service.ImportSource
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
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
 * Screenshots for the README (#220), taken from the #219 demo rides on an emulator. Run through
 * `./gradlew readmeScreenshots`, which also clears the app's data, sets up a clean status bar and
 * copies the PNGs to `docs/screenshots/`. Replaces the app's rides and settings, so
 * [EmulatorOnlyRule] skips it without the argument and refuses real devices.
 */
@RunWith(AndroidJUnit4::class)
class ReadmeScreenshots {
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(EmulatorOnlyRule()).around(compose)

    private lateinit var context: Context
    private lateinit var screenshots: ReadmeScreenshotCapture
    private var rideCount = 0

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

            val rides = DemoRideGenerator.generate(LocalDate.now(DemoRideGenerator.ZONE), assets::open)
            rideCount = rides.size
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
        }
    }

    @Test
    fun captureReadmeScreenshots() {
        captureHome()
    }

    /** Home list at scroll 0, newest ride (the Süd-Limburg hero) first. */
    private fun captureHome() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(UI_TIMEOUT_MS) {
                compose.onAllNodesWithText("$rideCount rides").fetchSemanticsNodes().isNotEmpty()
            }
            screenshots.capture(compose, "home")
        }
    }

    private companion object {
        const val HOME_NAME = "Simpelveld"
        const val RECLUSTER_TIMEOUT_MS = 180_000L
        const val UI_TIMEOUT_MS = 30_000L
    }
}
