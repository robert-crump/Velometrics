package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class IntervalClusteringServiceTest {

    private val gson = Gson()

    // ─── Polyline encoding (inverse of PolylineDecoder.decode) — for building MapEdge.geometryEncoded ───

    private fun encodePolyline(points: List<Pair<Double, Double>>): String {
        val sb = StringBuilder()
        var prevLat = 0
        var prevLng = 0
        for ((lat, lng) in points) {
            val latI = Math.round(lat * 1e5).toInt()
            val lngI = Math.round(lng * 1e5).toInt()
            encodeValue(latI - prevLat, sb)
            encodeValue(lngI - prevLng, sb)
            prevLat = latI
            prevLng = lngI
        }
        return sb.toString()
    }

    private fun encodeValue(value: Int, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toChar())
    }

    private fun edge(fromNode: Long, toNode: Long, lengthM: Double, from: Pair<Double, Double>, to: Pair<Double, Double>): MapEdge {
        return MapEdge(
            fromNode = fromNode, toNode = toNode,
            lengthM = lengthM, highway = "residential", name = null,
            isTraversed = false, geometryEncoded = encodePolyline(listOf(from, to)),
            speedMedian = null, speedMean = null, speedCount = null,
            speedP25 = null, speedP75 = null, speedP90 = null,
            powerMedian = null, powerMean = null, powerCount = null,
            powerP25 = null, powerP75 = null, powerP90 = null,
            slopePercent = 0.0, traversalCount = 0, lastTraversal = null, timeOfDayDist = null,
            stopCount = null, avgStopDurationS = null, stopProbability = null, estimatedStopTimeS = null
        )
    }

    /** Builds a GPS track JSON from [lat, lon] pairs at fixed longitude, increasing latitude. */
    private fun trackJson(startLat: Double, pointCount: Int, lon: Double = 6.0800, step: Double = 0.0003): String {
        val points = (0 until pointCount).map { i -> listOf(startLat + i * step, lon) }
        return gson.toJson(points)
    }

    private fun makeInterval(id: Long, distanceM: Double, gpsTrack: String): IntervalSession {
        return IntervalSession(
            id = id,
            cyclingSessionId = id,
            startTimestamp = Instant.parse("2025-0${(id % 9) + 1}-01T10:00:00Z"),
            durationSec = 200,
            durationNormalizedSec = 200,
            distanceM = distanceM,
            avgPower = 300,
            avgSpeedKmh = 25.0,
            avgSpeedNormalizedKmh = 25.0,
            direction = "out",
            startLat = 50.78, startLon = 6.08, endLat = 50.79, endLon = 6.08,
            gpsTrack = gpsTrack
        )
    }

    private fun buildService(
        intervals: List<IntervalSession>,
        existing: List<RepeatedInterval> = emptyList(),
        matchTrack: (List<List<Double>>) -> List<MapEdge>?
    ): Triple<IntervalClusteringService, MutableList<RepeatedInterval>, MutableList<List<Long>>> {
        val intervalRepository = mockk<IntervalRepository>()
        every { intervalRepository.getAllIntervals() } returns flowOf(intervals)

        val saved = mutableListOf<RepeatedInterval>()
        val deleted = mutableListOf<List<Long>>()
        val repeatedIntervalRepository = mockk<RepeatedIntervalRepository>()
        coEvery { repeatedIntervalRepository.getAllRepeatedIntervalsList() } returns existing
        coEvery { repeatedIntervalRepository.deleteAll() } returns Unit
        val deleteIdsSlot = slot<List<Long>>()
        coEvery { repeatedIntervalRepository.deleteRepeatedIntervalsByIds(capture(deleteIdsSlot)) } answers {
            deleted.add(deleteIdsSlot.captured)
        }
        val savedSlot = slot<RepeatedInterval>()
        coEvery { repeatedIntervalRepository.saveRepeatedInterval(capture(savedSlot)) } answers {
            saved.add(savedSlot.captured)
            savedSlot.captured.id
        }

        val mapMatcher = mockk<MapMatcher>()
        val trackSlot = slot<List<List<Double>>>()
        coEvery { mapMatcher.matchTrack(capture(trackSlot)) } answers { matchTrack(trackSlot.captured) }

        val service = IntervalClusteringService(intervalRepository, repeatedIntervalRepository, mapMatcher)
        return Triple(service, saved, deleted)
    }

    @Test
    fun `similar intervals are grouped into a single archetype with map-matched edges`() = runTest {
        val n0 = 0L; val n1 = 1L; val n2 = 2L
        val edge0 = edge(n0, n1, 100.0, 50.7800 to 6.0800, 50.7810 to 6.0800)
        val edge1 = edge(n1, n2, 100.0, 50.7810 to 6.0800, 50.7820 to 6.0800)

        // Two near-identical raw intervals — same length, overlapping GPS points
        val a = makeInterval(id = 1, distanceM = 200.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 8))
        val b = makeInterval(id = 2, distanceM = 205.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 8))

        val (service, saved, _) = buildService(listOf(a, b)) { listOf(edge0, edge1) }

        service.runClustering()

        assertEquals(1, saved.size)
        val archetype = saved.single()
        assertEquals(setOf(1L, 2L), archetype.intervals.map { it.id }.toSet())
        assertEquals(listOf(edge0, edge1), archetype.edges)
        assertEquals(edge0.lengthM + edge1.lengthM, archetype.distanceM, 0.001)
    }

    @Test
    fun `dissimilar intervals form separate archetypes with no minimum group size`() = runTest {
        val edgeNorth = edge(0L, 1L, 150.0, 50.7800 to 6.0800, 50.7813 to 6.0800)
        val edgeEast = edge(2L, 3L, 90.0, 50.9000 to 6.0800, 50.9000 to 6.0820)

        // Group A: similar short northbound intervals
        val a1 = makeInterval(id = 1, distanceM = 200.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))
        val a2 = makeInterval(id = 2, distanceM = 202.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))
        // Group B: a longer, spatially distinct interval far to the north — too dissimilar to join A
        val b1 = makeInterval(id = 3, distanceM = 500.0, gpsTrack = trackJson(startLat = 50.9000, pointCount = 6))

        val (service, saved, _) = buildService(listOf(a1, a2, b1)) { track ->
            if (track.first()[0] < 50.85) listOf(edgeNorth) else listOf(edgeEast)
        }

        service.runClustering()

        assertEquals(2, saved.size)
        val byFrequency = saved.sortedBy { it.intervals.size }
        assertEquals(setOf(3L), byFrequency[0].intervals.map { it.id }.toSet())
        assertEquals(setOf(1L, 2L), byFrequency[1].intervals.map { it.id }.toSet())
    }

    @Test
    fun `a shorter archetype that is mostly a subset of a longer one is discarded`() = runTest {
        val edge0 = edge(0L, 1L, 150.0, 50.7800 to 6.0800, 50.7813 to 6.0800)
        val edge1 = edge(1L, 2L, 150.0, 50.7813 to 6.0800, 50.7826 to 6.0800)
        val edge2 = edge(2L, 3L, 150.0, 50.7826 to 6.0800, 50.7839 to 6.0800)

        // Long archetype: 3 edges (450m). Short archetype: first 2 of those edges (300m) — fully
        // contained, so its shared length / own distance = 1.0 ≥ INTERVAL_SUBSET_OVERLAP_THRESHOLD.
        val long1 = makeInterval(id = 1, distanceM = 450.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))
        val short1 = makeInterval(id = 2, distanceM = 300.0, gpsTrack = trackJson(startLat = 50.9000, pointCount = 6))

        val (service, saved, _) = buildService(listOf(long1, short1)) { track ->
            if (track.first()[0] < 50.85) listOf(edge0, edge1, edge2) else listOf(edge0, edge1)
        }

        service.runClustering()

        assertEquals(1, saved.size)
        assertEquals(setOf(1L), saved.single().intervals.map { it.id }.toSet())
        assertEquals(listOf(edge0, edge1, edge2), saved.single().edges)
    }

    @Test
    fun `re-running preserves an existing archetype's name and id by overlapping interval-id subset`() = runTest {
        val edge0 = edge(0L, 1L, 150.0, 50.7800 to 6.0800, 50.7813 to 6.0800)
        val edge1 = edge(1L, 2L, 150.0, 50.7813 to 6.0800, 50.7826 to 6.0800)

        val existingInterval = makeInterval(id = 1, distanceM = 300.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))
        val existing = RepeatedInterval(
            id = 42L, name = "My Favorite Climb",
            intervals = listOf(existingInterval),
            edges = listOf(edge0),
            startLat = 50.7800, startLon = 6.0800, endLat = 50.7813, endLon = 6.0800,
            distanceM = 150.0
        )

        // The new run finds a bigger cluster ({1, 2}) whose ID set is a superset of the existing one's
        val a = makeInterval(id = 1, distanceM = 300.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))
        val b = makeInterval(id = 2, distanceM = 302.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))

        val (service, saved, deleted) = buildService(listOf(a, b), existing = listOf(existing)) { listOf(edge0, edge1) }

        service.runClustering()

        assertEquals(1, saved.size)
        val result = saved.single()
        assertEquals(42L, result.id)
        assertEquals("My Favorite Climb", result.name)
        assertEquals(setOf(1L, 2L), result.intervals.map { it.id }.toSet())
        assertTrue("no stale archetypes should be deleted", deleted.all { it.isEmpty() })
    }
}
