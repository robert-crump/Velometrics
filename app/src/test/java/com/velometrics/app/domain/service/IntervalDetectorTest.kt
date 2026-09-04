package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Datapoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class IntervalDetectorTest {

    private lateinit var detector: IntervalDetector
    private val sessionId = 1L
    private val baseTime: Instant = Instant.parse("2025-01-01T10:00:00Z")

    @Before
    fun setUp() {
        detector = IntervalDetector()
    }

    /**
     * Helper: creates [count] datapoints with the given [power], incrementing lat
     * by 0.00001 per point (~1.11 m), fixed lon 6.07, speedKmh 30.0, 1-second timestamps
     * starting from [startIndex].
     */
    private fun makeDatapoints(
        count: Int,
        power: Int?,
        startIndex: Int = 0,
        heartRate: ((Int) -> Int?)? = null
    ): List<Datapoint> {
        return (0 until count).map { i ->
            val idx = startIndex + i
            Datapoint(
                lat = 50.78 + idx * 0.00001,
                lon = 6.07,
                speedKmh = 30.0,
                power = power,
                timestamp = baseTime.plusSeconds(idx.toLong()),
                heartRate = heartRate?.invoke(idx)
            )
        }
    }

    @Test
    fun `no intervals in low-power session`() {
        val datapoints = makeDatapoints(300, 150)
        val result = detector.detect(datapoints, sessionId, 300)
        assertTrue("Expected no intervals for low-power session", result.isEmpty())
    }

    @Test
    fun `single long interval detected`() {
        val datapoints = makeDatapoints(50, 100, 0) +
                makeDatapoints(200, 310, 50) +
                makeDatapoints(50, 100, 250)
        val result = detector.detect(datapoints, sessionId, 300)
        assertEquals("Expected 1 interval", 1, result.size)
        val interval = result[0]
        assertTrue("avgPower should be around 310", interval.avgPower in 290..330)
        assertTrue("durationSec should be around 200", interval.durationSec in 180..220)
    }

    @Test
    fun `short dip tolerated and merged into one interval`() {
        // 50@100W + 80@320W + 5@200W (short dip, rolling avg stays near threshold) + 80@320W + 50@100W
        val datapoints = makeDatapoints(50, 100, 0) +
                makeDatapoints(80, 320, 50) +
                makeDatapoints(5, 200, 130) +
                makeDatapoints(80, 320, 135) +
                makeDatapoints(50, 100, 215)
        val result = detector.detect(datapoints, sessionId, 300)
        assertEquals("Expected 1 merged interval", 1, result.size)
    }

    @Test
    fun `long dip splits into two intervals`() {
        // 50@100W + 150@320W + 25@200W (long dip > 15s) + 150@320W + 50@100W
        val datapoints = makeDatapoints(50, 100, 0) +
                makeDatapoints(150, 320, 50) +
                makeDatapoints(25, 200, 200) +
                makeDatapoints(150, 320, 225) +
                makeDatapoints(50, 100, 375)
        val result = detector.detect(datapoints, sessionId, 300)
        assertEquals("Expected 2 intervals after long dip", 2, result.size)
    }

    @Test
    fun `short intense effort accepted via normalization`() {
        // 100 points @ 400W: raw duration ~100s < 120, but normalizedDuration ~100*(400/300) ~133 >= 120
        val datapoints = makeDatapoints(50, 100, 0) +
                makeDatapoints(100, 400, 50) +
                makeDatapoints(50, 100, 150)
        val result = detector.detect(datapoints, sessionId, 300)
        assertEquals("Expected 1 interval via normalization", 1, result.size)
    }

    @Test
    fun `null power returns empty`() {
        val datapoints = makeDatapoints(300, null)
        val result = detector.detect(datapoints, sessionId, 300)
        assertTrue("Expected no intervals for null-power data", result.isEmpty())
    }

    @Test
    fun `interval at end of session detected`() {
        val datapoints = makeDatapoints(50, 100, 0) +
                makeDatapoints(200, 310, 50)
        val result = detector.detect(datapoints, sessionId, 300)
        assertEquals("Expected 1 interval at end of session", 1, result.size)
    }

    // #178 — import-time HR recovery metrics (hrr60/hrr30/avgPower60sAfter/avgPower30sAfter/restBeforeNextIntervalSec)

    @Test
    fun `hrr60, hrr30, avgPower60sAfter, and avgPower30sAfter computed over the full window with enough trailing data`() {
        // 50@100W + 200@310W lands the interval's endIdx at exactly idx 264 (rolling-window
        // finalize math: dip to 100W at idx250 crosses below the 285W threshold at idx251, then
        // the 15s rest-tolerance finalize fires at idx266, giving endIdx = lastAboveIdx(250) +
        // window(15) - 1 = 264 -- verified against this same warmup+effort+100W-tail shape without
        // HR in the "single long interval detected" test above). A 300-point 100W tail then gives
        // HR at exactly +30s (idx294) and +60s (idx324) past that endIdx.
        val datapoints = makeDatapoints(50, 100, 0) { 170 } +
                makeDatapoints(200, 310, 50) { 170 } +
                makeDatapoints(300, 100, 250) { idx ->
                    when {
                        idx <= 264 -> 170
                        idx < 294 -> 150
                        idx < 324 -> 140
                        else -> 110
                    }
                }

        val result = detector.detect(datapoints, sessionId, 300)
        assertEquals(1, result.size)
        val interval = result[0]

        assertEquals("hrr30 = 170 (at end) - 140 (at +30s)", 30, interval.hrr30)
        assertEquals("hrr60 = 170 (at end) - 110 (at +60s)", 60, interval.hrr60)
        assertEquals("60s tail window is entirely 100W", 100, interval.avgPower60sAfter)
        assertEquals("30s tail window is entirely 100W", 100, interval.avgPower30sAfter)
        assertNull("last interval in the session has no next interval", interval.restBeforeNextIntervalSec)
    }

    @Test
    fun `recovery window still populated when the next interval starts before it completes`() {
        // Same shape as "long dip splits into two intervals": the 25-point/25s dip is well short
        // of the 60s recovery window, so interval 1's window runs into interval 2's high-power
        // ramp -- exactly the back-to-back-reps case #178 requires NOT be nulled for truncation.
        val datapoints = makeDatapoints(50, 100, 0) { 150 } +
                makeDatapoints(150, 320, 50) { 150 } +
                makeDatapoints(25, 200, 200) { 150 } +
                makeDatapoints(150, 320, 225) { 150 } +
                makeDatapoints(50, 100, 375) { 150 }

        val result = detector.detect(datapoints, sessionId, 300)
        assertEquals("Expected 2 intervals after the short dip", 2, result.size)
        val interval1 = result[0]

        assertTrue(
            "rest before interval 2 should be well under the 60s recovery window",
            interval1.restBeforeNextIntervalSec != null && interval1.restBeforeNextIntervalSec!! < 60
        )
        // Contaminated by interval 2's 320W ramp entering the window -- still computed, not nulled.
        assertTrue(
            "avgPower60sAfter should reflect the contaminating high-power ramp, not just the dip's 200W",
            interval1.avgPower60sAfter != null && interval1.avgPower60sAfter!! > 200
        )
        // Same truncation, shorter window -- still populated rather than nulled.
        assertNotNull("avgPower30sAfter should still be computed despite the short rest", interval1.avgPower30sAfter)
        // Constant 150bpm throughout -> a real (zero) reading, not a null one.
        assertEquals(0, interval1.hrr60)
    }

    @Test
    fun `hrr60 and hrr30 are null when HR data is missing, independent of avgPower60sAfter`() {
        val datapoints = makeDatapoints(50, 100, 0) +
                makeDatapoints(200, 310, 50) +
                makeDatapoints(300, 100, 250)

        val result = detector.detect(datapoints, sessionId, 300)
        assertEquals(1, result.size)
        val interval = result[0]

        assertNull(interval.hrr60)
        assertNull(interval.hrr30)
        assertEquals("power data is present even though HR is not", 100, interval.avgPower60sAfter)
        assertEquals("power data is present even though HR is not", 100, interval.avgPower30sAfter)
    }

    @Test
    fun `avgPower30sAfter is populated independently when the session ends before the 60s window completes`() {
        // Same endIdx = 264 derivation as the full-window test above, but the tail stops exactly
        // at idx 294 (+30s past endIdx) -- enough to complete the 30s window but not the 60s one.
        val datapoints = makeDatapoints(50, 100, 0) +
                makeDatapoints(200, 310, 50) +
                makeDatapoints(45, 100, 250)

        val result = detector.detect(datapoints, sessionId, 300)
        assertEquals(1, result.size)
        val interval = result[0]

        assertEquals("30s window completes exactly at the last datapoint", 100, interval.avgPower30sAfter)
        assertNull("60s window never completes -- session ends 30s short of it", interval.avgPower60sAfter)
    }

    @Test
    fun `recovery metrics are null when the session ends before the window completes`() {
        // No datapoints at all past the interval's end -- the window can never reach its full
        // fixed duration, so it's treated as insufficient data rather than a partial-window value.
        val datapoints = makeDatapoints(50, 100, 0) { 170 } +
                makeDatapoints(200, 310, 50) { 170 }

        val result = detector.detect(datapoints, sessionId, 300)
        assertEquals(1, result.size)
        val interval = result[0]

        assertNull(interval.hrr60)
        assertNull(interval.hrr30)
        assertNull(interval.avgPower60sAfter)
        assertNull(interval.avgPower30sAfter)
        assertNull(interval.restBeforeNextIntervalSec)
    }
}
