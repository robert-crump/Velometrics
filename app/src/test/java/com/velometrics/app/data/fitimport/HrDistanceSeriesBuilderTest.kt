package com.velometrics.app.data.fitimport

import com.velometrics.app.domain.model.Datapoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HrDistanceSeriesBuilderTest {

    // ~11.1 m per 0.0001 deg of latitude; 1000 records = ~11.1 km heading north.
    private fun ride(
        count: Int = 1001,
        stepDeg: Double = 0.0001,
        hr: (Int) -> Int? = { 100 + it / 10 },
        alt: (Int) -> Double? = { it.toDouble() }
    ) = (0 until count).map { i ->
        Datapoint(
            lat = 48.0 + i * stepDeg, lon = 11.0, speedKmh = 30.0, power = null,
            timestamp = Instant.ofEpochSecond(i.toLong()), heartRate = hr(i), altitude = alt(i)
        )
    }

    /** Index of the record point [k] samples: the first whose cumulative distance reaches k/100. */
    private fun sampleIndex(k: Int): Int {
        val cumulative = HrDistanceSeriesBuilder.cumulativeMeters(ride())
        return cumulative.indexOfFirst { it >= cumulative.last() * k / 100 }
    }

    @Test
    fun `produces 100 points, the last being the final record`() {
        val points = HrDistanceSeriesBuilder.build(ride())!!
        assertEquals(100, points.size)
        assertEquals(200, points.last().heartRate) // record 1000 -> 100 + 100
        assertEquals(1000.0, points.last().altitudeM!!, 0.0)
        val total = HrDistanceSeriesBuilder.cumulativeMeters(ride()).last() / 1000.0
        assertEquals(total, points.last().distanceKm, 1e-9)
    }

    @Test
    fun `point k is the first record reaching k over 100 of the distance`() {
        val points = HrDistanceSeriesBuilder.build(ride())!!
        // Uniform steps: point 1 lands on ~record 10, point 50 on ~record 500.
        assertEquals(100 + sampleIndex(1) / 10, points[0].heartRate)
        assertEquals(100 + sampleIndex(50) / 10, points[49].heartRate)
        assertTrue(sampleIndex(1) in 9..11)
        assertTrue(sampleIndex(50) in 499..501)
        assertEquals(points.last().distanceKm / 100, points[0].distanceKm, 0.02)
    }

    @Test
    fun `missing HR at a sample falls back to the nearest record within five, earlier winning ties`() {
        // Records around point 50's sample all lack HR except the ones 2 before and 2 after it.
        val s = sampleIndex(50)
        val hr = { i: Int -> if (i == s - 2) 111 else if (i == s + 2) 222 else if (i in s - 5..s + 5) null else 100 }
        assertEquals(111, HrDistanceSeriesBuilder.build(ride(hr = hr))!![49].heartRate)
    }

    @Test
    fun `later record is used when it is nearer than any earlier one`() {
        val s = sampleIndex(50)
        val hr = { i: Int -> if (i == s + 3) 222 else if (i in s - 5..s + 5) null else 100 }
        assertEquals(222, HrDistanceSeriesBuilder.build(ride(hr = hr))!![49].heartRate)
    }

    @Test
    fun `HR missing beyond five records leaves the point null`() {
        val s = sampleIndex(50)
        val hr = { i: Int -> if (i in s - 6..s + 6) null else 100 }
        val points = HrDistanceSeriesBuilder.build(ride(hr = hr))!!
        assertNull(points[49].heartRate)
        assertNotNull(points[48].heartRate)
    }

    @Test
    fun `zero HR is treated as missing`() {
        val s = sampleIndex(50)
        val hr = { i: Int -> if (i == s) 0 else if (i == s - 1) 140 else 100 }
        assertEquals(140, HrDistanceSeriesBuilder.build(ride(hr = hr))!![49].heartRate)
    }

    @Test
    fun `altitude gaps are filled independently of HR`() {
        val s = sampleIndex(50)
        val alt = { i: Int -> if (i == s + 1) 42.0 else null }
        assertEquals(42.0, HrDistanceSeriesBuilder.build(ride(alt = alt))!![49].altitudeM!!, 0.0)
    }

    @Test
    fun `returns null under one kilometre`() {
        assertNull(HrDistanceSeriesBuilder.build(ride(count = 50)))
    }

    @Test
    fun `returns null when there is no heart rate`() {
        assertNull(HrDistanceSeriesBuilder.build(ride(hr = { null })))
    }

    @Test
    fun `returns null for no datapoints`() {
        assertNull(HrDistanceSeriesBuilder.build(emptyList()))
    }
}
