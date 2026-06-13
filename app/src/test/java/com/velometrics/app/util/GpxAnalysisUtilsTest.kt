package com.velometrics.app.util

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.model.PoiWithDistances
import org.junit.Assert.*
import org.junit.Test

class GpxAnalysisUtilsTest {

    private fun edgeOf(lengthM: Double, isTraversed: Boolean) = MapEdge(
        fromNode = 1L,
        toNode = 2L,
        lengthM = lengthM,
        highway = "cycleway",
        name = null,
        isTraversed = isTraversed,
        geometryEncoded = "",
        speedMedian = null,
        speedMean = null,
        speedCount = null,
        speedP25 = null,
        speedP75 = null,
        speedP90 = null,
        powerMedian = null,
        powerMean = null,
        powerCount = null,
        powerP25 = null,
        powerP75 = null,
        powerP90 = null,
        slopePercent = null,
        traversalCount = null,
        lastTraversal = null,
        timeOfDayDist = null,
    )

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

    @Test
    fun `discovery score is 0 when all matched edges are traversed`() {
        val edges = listOf(edgeOf(1000.0, isTraversed = true), edgeOf(1000.0, isTraversed = true))
        assertEquals(0, GpxAnalysisUtils.discoveryScore(edges))
    }

    @Test
    fun `discovery score is 100 when no matched edges are traversed`() {
        val edges = listOf(edgeOf(1000.0, isTraversed = false), edgeOf(1000.0, isTraversed = false))
        assertEquals(100, GpxAnalysisUtils.discoveryScore(edges))
    }

    @Test
    fun `discovery score is the length-weighted percentage of untraversed edges`() {
        val edges = listOf(
            edgeOf(750.0, isTraversed = false),
            edgeOf(250.0, isTraversed = true)
        )
        assertEquals(75, GpxAnalysisUtils.discoveryScore(edges))
    }

    @Test
    fun `discovery score is null for an empty edge list`() {
        assertNull(GpxAnalysisUtils.discoveryScore(emptyList()))
    }
}
