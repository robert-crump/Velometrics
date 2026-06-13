package com.velometrics.app.util

import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.model.PoiWithDistances
import org.junit.Assert.*
import org.junit.Test

class GpxAnalysisUtilsTest {

    private fun poiAt(trackDistanceM: Double?) = PoiWithDistances(
        poi = Poi(
            poiId = "poi-$trackDistanceM",
            name = "Test POI",
            category = "cafe",
            cuisine = null,
            lat = 0.0,
            lon = 0.0,
            openingHours = null
        ),
        airDistanceM = null,
        trackDistanceM = trackDistanceM
    )

    @Test
    fun `tracks shorter than 5km produce a single bucket`() {
        val pois = listOf(poiAt(500.0), poiAt(2000.0))
        val counts = GpxAnalysisUtils.poiCountsPer5kmBucket(pois, totalDistanceM = 3000.0)
        assertEquals(listOf(2), counts)
    }

    @Test
    fun `pois are bucketed into 5km segments along the track`() {
        val pois = listOf(
            poiAt(0.0),
            poiAt(4999.0),
            poiAt(5000.0),
            poiAt(9000.0),
            poiAt(12000.0)
        )
        val counts = GpxAnalysisUtils.poiCountsPer5kmBucket(pois, totalDistanceM = 13000.0)
        assertEquals(listOf(2, 2, 1), counts)
    }

    @Test
    fun `pois with null trackDistanceM are ignored`() {
        val pois = listOf(poiAt(1000.0), poiAt(null))
        val counts = GpxAnalysisUtils.poiCountsPer5kmBucket(pois, totalDistanceM = 6000.0)
        assertEquals(listOf(1, 0), counts)
    }

    @Test
    fun `poi exactly at total distance is clamped to last bucket`() {
        val pois = listOf(poiAt(10000.0))
        val counts = GpxAnalysisUtils.poiCountsPer5kmBucket(pois, totalDistanceM = 10000.0)
        assertEquals(listOf(0, 1), counts)
    }
}
