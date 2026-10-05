package com.velometrics.app.data.fitimport

import com.velometrics.app.domain.model.Datapoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GpsQualityFilterTest {

    // 1 deg latitude = ~111.2 km, so metres north convert to degrees at this scale.
    private val metersPerDeg = 111_195.0

    private fun point(sec: Long, northM: Double, accuracy: Int? = null) = Datapoint(
        lat = 48.0 + northM / metersPerDeg, lon = 11.0, speedKmh = null, power = null,
        timestamp = Instant.ofEpochSecond(sec), gpsAccuracyM = accuracy
    )

    /** 1 Hz records at a steady [mps] heading north. */
    private fun steady(count: Int, mps: Double = 8.0, accuracy: (Int) -> Int? = { null }) =
        (0 until count).map { point(it.toLong(), it * mps, accuracy(it)) }

    @Test
    fun `records with accuracy above 15 m are discarded`() {
        val points = steady(10) { if (it == 4 || it == 7) 16 else 15 }

        val result = GpsQualityFilter.filter(points)

        assertEquals(8, result.kept.size)
        assertEquals(2, result.discardCounts["accuracy"])
        assertTrue(result.kept.none { it.gpsAccuracyM == 16 })
    }

    @Test
    fun `files without gps_accuracy are unaffected`() {
        val result = GpsQualityFilter.filter(steady(10))

        assertEquals(10, result.kept.size)
        assertEquals(0, result.discardCounts["accuracy"])
    }

    @Test
    fun `a GPS jump implying more than 5 m per s squared is discarded`() {
        // 8 m/s, then one record 10 m ahead of the track: 18 m/s in 1 s = 10 m/s²
        val points = steady(5) + point(5, 4 * 8.0 + 18.0) + (6 until 10).map { point(it.toLong(), it * 8.0) }

        val result = GpsQualityFilter.filter(points)

        assertEquals(1, result.discardCounts["accel"])
        assertEquals(9, result.kept.size)
        assertTrue(result.kept.none { it.timestamp == Instant.ofEpochSecond(5) })
    }

    @Test
    fun `hard braking under 5 m per s squared is kept`() {
        // 10 m/s, then 5.5 m/s, then 1 m/s
        val points = listOf(point(0, 0.0), point(1, 10.0), point(2, 20.0), point(3, 25.5), point(4, 26.5))

        val result = GpsQualityFilter.filter(points)

        assertEquals(5, result.kept.size)
        assertEquals(0, result.discardCounts["accel"])
    }

    @Test
    fun `no acceleration check across a gap over 10 s`() {
        // 8 m/s, an 11 s gap covering only 11 m (artificially low ~1 m/s), then 8 m/s again
        val before = steady(5)
        val gapEnd = 4 * 8.0 + 11.0
        val after = (0 until 5).map { point(15L + it, gapEnd + it * 8.0) }

        val result = GpsQualityFilter.filter(before + after)

        assertEquals(10, result.kept.size)
        assertEquals(0, result.discardCounts["accel"])
    }

    @Test
    fun `a 10 s gap is still checked`() {
        // 8 m/s, then 10 s covering 300 m = 30 m/s: (30 - 8) / 10 = 2.2 m/s², kept; then back to 8 m/s
        // from 30 m/s in 1 s = 22 m/s², discarded
        val before = steady(5)
        val gapEnd = 4 * 8.0 + 300.0
        val points = before + point(14, gapEnd) + point(15, gapEnd + 8.0)

        val result = GpsQualityFilter.filter(points)

        assertEquals(1, result.discardCounts["accel"])
        assertEquals(6, result.kept.size)
    }

    @Test
    fun `log line reports accuracy and accel counts`() {
        val line = GpsQualityFilter.filter(steady(3)).logLine()

        assertTrue(line, line.contains("discardAccuracy=0"))
        assertTrue(line, line.contains("discardAccel=0"))
        assertTrue(line, line.startsWith("GPS filter: kept=3"))
    }

    @Test
    fun `existing rules still apply`() {
        val points = steady(6).toMutableList()
        points[2] = points[2].copy(lat = 0.0)
        points[4] = points[4].copy(power = 1600)

        val result = GpsQualityFilter.filter(points)

        assertEquals(1, result.discardCounts["zero"])
        assertEquals(1, result.discardCounts["power"])
        assertEquals(4, result.kept.size)
    }
}
