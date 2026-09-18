package com.velometrics.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationSmoothingTest {

    @Test
    fun `empty window returns null`() {
        assertNull(LocationSmoothing.weightedAverage(emptyList()))
    }

    @Test
    fun `single sample returns that sample's position`() {
        val result = LocationSmoothing.weightedAverage(listOf(LocationSample(10.0, 20.0, 5f)))
        assertEquals(10.0, result!!.lat, 0.0001)
        assertEquals(20.0, result.lon, 0.0001)
    }

    @Test
    fun `equal accuracy samples average evenly`() {
        val samples = listOf(
            LocationSample(0.0, 0.0, 10f),
            LocationSample(10.0, 10.0, 10f),
        )
        val result = LocationSmoothing.weightedAverage(samples)!!
        assertEquals(5.0, result.lat, 0.0001)
        assertEquals(5.0, result.lon, 0.0001)
    }

    @Test
    fun `more accurate sample pulls the average toward itself`() {
        // Weight is 1/accuracy^2, so a 1m-accuracy fix outweighs a 10m-accuracy fix 100 to 1.
        val samples = listOf(
            LocationSample(0.0, 0.0, 10f),
            LocationSample(10.0, 10.0, 1f),
        )
        val result = LocationSmoothing.weightedAverage(samples)!!
        assertEquals(9.90099, result.lat, 0.001)
        assertEquals(9.90099, result.lon, 0.001)
    }

    @Test
    fun `accuracy below 1m is clamped so weight cannot exceed 1`() {
        val samples = listOf(
            LocationSample(0.0, 0.0, 0.1f),
            LocationSample(10.0, 10.0, 0.01f),
        )
        // Both accuracies clamp to 1m, so weights are equal regardless of the raw values.
        val result = LocationSmoothing.weightedAverage(samples)!!
        assertEquals(5.0, result.lat, 0.0001)
        assertEquals(5.0, result.lon, 0.0001)
    }
}
