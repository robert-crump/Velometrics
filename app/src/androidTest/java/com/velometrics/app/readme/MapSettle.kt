package com.velometrics.app.readme

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import com.velometrics.app.MainActivity
import org.maplibre.android.maps.MapView

/**
 * Waits for the MapLibre maps on screen to finish loading, so a screenshot never shows blank or
 * half-loaded tiles. A map counts as settled once its style is loaded and it has been idle (every
 * visible tile loaded, no camera movement, no frame rendered) for [QUIET_MS].
 */
object MapSettle {
    private const val QUIET_MS = 1_500L
    private const val POLL_MS = 250L

    fun await(scenario: ActivityScenario<MainActivity>, timeoutMs: Long) {
        // Written and read on the main thread only: MapLibre calls its listeners there, and the
        // checks below run in onActivity.
        val idleSince = HashMap<MapView, Long?>()
        scenario.onActivity { activity ->
            val maps = mapViews(activity.window.decorView)
            check(maps.isNotEmpty()) { "No map on screen" }
            maps.forEach { mapView ->
                idleSince[mapView] = null
                mapView.addOnWillStartRenderingFrameListener { idleSince[mapView] = null }
                mapView.addOnDidBecomeIdleListener { idleSince[mapView] = SystemClock.uptimeMillis() }
                // An already idle map renders one more frame, then reports idle again.
                mapView.getMapAsync { it.triggerRepaint() }
            }
        }

        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            var settled = false
            scenario.onActivity {
                val now = SystemClock.uptimeMillis()
                settled = idleSince.all { (mapView, since) ->
                    var styleLoaded = false
                    // Runs synchronously: the map is ready by the time its view is on screen.
                    mapView.getMapAsync { styleLoaded = it.style?.isFullyLoaded == true }
                    styleLoaded && since != null && now - since >= QUIET_MS
                }
            }
            if (settled) return
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Map tiles didn't finish loading")
            Thread.sleep(POLL_MS)
        }
    }

    private fun mapViews(view: View): List<MapView> = when (view) {
        is MapView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { mapViews(view.getChildAt(it)) }
        else -> emptyList()
    }
}
