package com.velometrics.app.util

import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.RepeatedInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MapOverlayUtilsIntervalTest {

    // --- normalizedDurationToColor ---

    @Test
    fun `normalizedDurationToColor - at min 120s returns lightest color`() {
        assertEquals("#FFFFB2", MapOverlayUtils.normalizedDurationToColor(120))
    }

    @Test
    fun `normalizedDurationToColor - at max 480s returns darkest color`() {
        assertEquals("#BD0026", MapOverlayUtils.normalizedDurationToColor(480))
    }

    @Test
    fun `normalizedDurationToColor - at midpoint 300s returns orange`() {
        assertEquals("#FD8D3C", MapOverlayUtils.normalizedDurationToColor(300))
    }

    @Test
    fun `normalizedDurationToColor - below min clamps to lightest`() {
        assertEquals("#FFFFB2", MapOverlayUtils.normalizedDurationToColor(60))
    }

    @Test
    fun `normalizedDurationToColor - above max clamps to darkest`() {
        assertEquals("#BD0026", MapOverlayUtils.normalizedDurationToColor(600))
    }

    @Test
    fun `normalizedDurationToColor - interpolates between stops`() {
        // 210s is exactly at the second stop
        val color = MapOverlayUtils.normalizedDurationToColor(210)
        assertEquals("#FECC5C", color)

        // 255s is between stops 1 (210s) and 2 (300s) — halfway = (210+300)/2 = 255
        val midColor = MapOverlayUtils.normalizedDurationToColor(255)
        // Should be between #FECC5C and #FD8D3C — an intermediate orange
        // Verify it starts with # and has 7 chars
        assertTrue(midColor.startsWith("#"))
        assertEquals(7, midColor.length)
        // R should be between 0xFE (254) and 0xFD (253) — very close
        val r = midColor.substring(1, 3).toInt(16)
        assertTrue(r in 253..254)
    }
}
