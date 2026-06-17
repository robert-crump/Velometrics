package com.velometrics.app.util

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.model.PoiWithDistances
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class GpxAnalysisUtilsTest {

    private fun edgeOf(
        lengthM: Double,
        isTraversed: Boolean,
        speedMean: Double? = null,
        powerMean: Double? = null,
    ) = MapEdge(
        fromNode = 1L,
        toNode = 2L,
        lengthM = lengthM,
        highway = "cycleway",
        name = null,
        isTraversed = isTraversed,
        geometryEncoded = "",
        speedMedian = null,
        speedMean = speedMean,
        speedCount = null,
        speedP25 = null,
        speedP75 = null,
        speedP90 = null,
        powerMedian = null,
        powerMean = powerMean,
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
        val counts = GpxAnalysisUtils.poiCountsPerBucket(pois, totalDistanceM = 3000.0, bucketSizeM = 5000.0)
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
        val counts = GpxAnalysisUtils.poiCountsPerBucket(pois, totalDistanceM = 13000.0, bucketSizeM = 5000.0)
        assertEquals(listOf(2, 2, 1), counts)
    }

    @Test
    fun `pois with null trackDistanceM are ignored`() {
        val pois = listOf(poiAt(1000.0), poiAt(null))
        val counts = GpxAnalysisUtils.poiCountsPerBucket(pois, totalDistanceM = 6000.0, bucketSizeM = 5000.0)
        assertEquals(listOf(1, 0), counts)
    }

    @Test
    fun `poi exactly at total distance is clamped to last bucket`() {
        val pois = listOf(poiAt(10000.0))
        val counts = GpxAnalysisUtils.poiCountsPerBucket(pois, totalDistanceM = 10000.0, bucketSizeM = 5000.0)
        assertEquals(listOf(0, 1), counts)
    }

    @Test
    fun `bucket size stays 5km for routes up to 50km`() {
        assertEquals(5_000.0, GpxAnalysisUtils.poiDensityBucketSizeM(50_000.0), 0.0)
    }

    @Test
    fun `bucket size scales up for longer routes to keep around 10 buckets`() {
        assertEquals(10_000.0, GpxAnalysisUtils.poiDensityBucketSizeM(100_000.0), 0.0)
        assertEquals(25_000.0, GpxAnalysisUtils.poiDensityBucketSizeM(250_000.0), 0.0)
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

    @Test
    fun `elevationProfile pairs cumulative distance with non-null elevations`() {
        val points = listOf(
            LatLng(0.0, 0.0),
            LatLng(0.0, 0.001),
            LatLng(0.0, 0.002)
        )
        val elevations = listOf(100.0, 110.0, 120.0)
        val profile = GpxAnalysisUtils.elevationProfile(points, elevations)

        assertEquals(3, profile.size)
        assertEquals(0.0, profile[0].first, 1e-6)
        assertEquals(100.0, profile[0].second, 1e-6)
        assertEquals(120.0, profile[2].second, 1e-6)
        assertTrue(profile[1].first > 0.0)
    }

    @Test
    fun `elevationProfile skips points with null elevation`() {
        val points = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.001), LatLng(0.0, 0.002))
        val elevations = listOf(100.0, null, 120.0)
        val profile = GpxAnalysisUtils.elevationProfile(points, elevations)

        assertEquals(2, profile.size)
        assertEquals(100.0, profile[0].second, 1e-6)
        assertEquals(120.0, profile[1].second, 1e-6)
    }

    @Test
    fun `smoothElevations averages within a centered window`() {
        val raw = listOf(100.0, 200.0, 100.0, 200.0, 100.0)
        val smoothed = GpxAnalysisUtils.smoothElevations(raw, windowSize = 3)

        assertEquals(5, smoothed.size)
        // middle point averages itself with both neighbours
        assertEquals((100.0 + 200.0 + 100.0) / 3, smoothed[1], 1e-6)
        // first point only has itself and its single right neighbour
        assertEquals((100.0 + 200.0) / 2, smoothed[0], 1e-6)
    }

    @Test
    fun `smoothElevations returns empty for empty input`() {
        assertEquals(emptyList<Double>(), GpxAnalysisUtils.smoothElevations(emptyList()))
    }

    @Test
    fun `elevationGainLoss sums positive and negative deltas separately`() {
        val smoothed = listOf(100.0, 150.0, 120.0, 180.0)
        val (gain, loss) = GpxAnalysisUtils.elevationGainLoss(smoothed)

        assertEquals(110, gain) // +50, +60
        assertEquals(30, loss)  // -30
    }

    @Test
    fun `elevationGainLoss is zero for a flat profile`() {
        val (gain, loss) = GpxAnalysisUtils.elevationGainLoss(listOf(100.0, 100.0, 100.0))
        assertEquals(0, gain)
        assertEquals(0, loss)
    }

    @Test
    fun `elevationGainPer100km scales gain to a 100km distance`() {
        assertEquals(1000, GpxAnalysisUtils.elevationGainPer100km(gainM = 100, totalDistanceM = 10_000.0))
    }

    @Test
    fun `elevationGainPer100km is zero for a zero-distance track`() {
        assertEquals(0, GpxAnalysisUtils.elevationGainPer100km(gainM = 100, totalDistanceM = 0.0))
    }

    @Test
    fun `speedPowerEstimate is null for an empty edge list`() {
        assertNull(GpxAnalysisUtils.speedPowerEstimate(emptyList(), routeTotalDistanceM = 2000.0))
    }

    @Test
    fun `speedPowerEstimate reports zero coverage when no edges have ride history`() {
        val edges = listOf(
            edgeOf(1000.0, isTraversed = false),
            edgeOf(1000.0, isTraversed = true, speedMean = null, powerMean = null)
        )
        val result = GpxAnalysisUtils.speedPowerEstimate(edges, routeTotalDistanceM = 2000.0)
        assertEquals(SpeedPowerEstimate(avgSpeedKmh = 0, avgPowerW = 0, coveragePercent = 0), result)
    }

    @Test
    fun `elevationAxisStep uses 50m steps for small ranges`() {
        assertEquals(50.0, GpxAnalysisUtils.elevationAxisStep(minEle = 100.0, maxEle = 220.0), 0.0)
    }

    @Test
    fun `elevationAxisStep uses 100m steps for medium ranges`() {
        assertEquals(100.0, GpxAnalysisUtils.elevationAxisStep(minEle = 0.0, maxEle = 450.0), 0.0)
    }

    @Test
    fun `elevationAxisStep uses 200m steps for large ranges`() {
        assertEquals(200.0, GpxAnalysisUtils.elevationAxisStep(minEle = 0.0, maxEle = 1100.0), 0.0)
    }

    @Test
    fun `elevationAxisStep falls back to 200m for very large ranges`() {
        assertEquals(200.0, GpxAnalysisUtils.elevationAxisStep(minEle = 0.0, maxEle = 3000.0), 0.0)
    }

    @Test
    fun `truncateFileName returns short names unchanged`() {
        assertEquals("ride.gpx", GpxAnalysisUtils.truncateFileName("ride.gpx"))
    }

    @Test
    fun `truncateFileName strips extension when needed to fit`() {
        // 28 chars with extension, 24 without -> fits once the extension is dropped
        assertEquals("morning_commute_loop_2024", GpxAnalysisUtils.truncateFileName("morning_commute_loop_2024.gpx"))
    }

    @Test
    fun `truncateFileName truncates with ellipsis when still too long without extension`() {
        val name = "a_very_long_descriptive_ride_name.gpx"
        val result = GpxAnalysisUtils.truncateFileName(name)
        assertEquals(25, result.length)
        assertTrue(result.endsWith("…"))
        assertEquals("a_very_long_descriptive_" + "…", result)
    }

    @Test
    fun `speedPowerEstimate computes length-weighted averages and coverage from ride-history edges`() {
        val edges = listOf(
            edgeOf(750.0, isTraversed = true, speedMean = 20.0, powerMean = 200.0),
            edgeOf(250.0, isTraversed = true, speedMean = 30.0, powerMean = 240.0),
            edgeOf(1000.0, isTraversed = false)
        )
        val result = GpxAnalysisUtils.speedPowerEstimate(edges, routeTotalDistanceM = 4000.0)

        // weighted over the 1000m of ride-history coverage (750m @20km/h, 250m @30km/h)
        assertEquals(23, result?.avgSpeedKmh) // (750*20 + 250*30) / 1000 = 22.5 -> 23
        assertEquals(210, result?.avgPowerW) // (750*200 + 250*240) / 1000 = 210
        // 1000m of ride-history coverage out of 4000m full route length
        assertEquals(25, result?.coveragePercent)
    }
}
