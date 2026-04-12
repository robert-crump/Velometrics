package com.cyclegraph.app.domain.service

import com.cyclegraph.app.domain.model.Datapoint
import org.junit.Assert.assertEquals
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
    private fun makeDatapoints(count: Int, power: Int?, startIndex: Int = 0): List<Datapoint> {
        return (0 until count).map { i ->
            val idx = startIndex + i
            Datapoint(
                lat = 50.78 + idx * 0.00001,
                lon = 6.07,
                speedKmh = 30.0,
                power = power,
                timestamp = baseTime.plusSeconds(idx.toLong())
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
}
