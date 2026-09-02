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
            slopePercent = 0.0, traversalCount = 0, lastTraversal = null, timeOfDayDist = null
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

        // Clustering loads one shared Region per spatial cell and matches against it; delegate the
        // Region's match to the same fake matchTrack so tests describe map-matching in one place.
        val region = mockk<MapMatcher.Region>()
        val regionTrackSlot = slot<List<List<Double>>>()
        coEvery { region.match(capture(regionTrackSlot)) } answers { matchTrack(regionTrackSlot.captured) }
        coEvery { mapMatcher.loadRegion(any(), any(), any(), any()) } returns region

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
    fun `a shorter archetype that is mostly a subset of a longer, length-similar one is merged into it`() = runTest {
        val edge0 = edge(0L, 1L, 200.0, 50.7800 to 6.0800, 50.7818 to 6.0800)
        val edge1 = edge(1L, 2L, 200.0, 50.7818 to 6.0800, 50.7836 to 6.0800)
        val edge2 = edge(2L, 3L, 200.0, 50.7836 to 6.0800, 50.7854 to 6.0800)
        val edge3 = edge(3L, 4L, 200.0, 50.7854 to 6.0800, 50.7872 to 6.0800)
        val edge4 = edge(4L, 5L, 200.0, 50.7872 to 6.0800, 50.7890 to 6.0800)

        // Long archetype: 5 edges (1000m). Short archetype: first 4 of those edges (800m) — fully
        // contained (shared/own = 1.0 ≥ INTERVAL_SUBSET_OVERLAP_THRESHOLD) AND within the merge
        // length tolerance (delta 200m ≤ max(FLOOR=500m, PCT=20% of 1000m=200m) = 500m) — so it
        // merges rather than staying separate: both raw intervals end up on the kept archetype.
        val long1 = makeInterval(id = 1, distanceM = 1000.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))
        val short1 = makeInterval(id = 2, distanceM = 800.0, gpsTrack = trackJson(startLat = 50.9000, pointCount = 6))

        val (service, saved, _) = buildService(listOf(long1, short1)) { track ->
            if (track.first()[0] < 50.85) listOf(edge0, edge1, edge2, edge3, edge4) else listOf(edge0, edge1, edge2, edge3)
        }

        service.runClustering()

        assertEquals(1, saved.size)
        assertEquals(setOf(1L, 2L), saved.single().intervals.map { it.id }.toSet())
        assertEquals(listOf(edge0, edge1, edge2, edge3, edge4), saved.single().edges)
    }

    @Test
    fun `a shorter archetype that is a full subset but too different in length stays a separate archetype`() = runTest {
        val edge0 = edge(0L, 1L, 300.0, 50.7800 to 6.0800, 50.7827 to 6.0800)
        val edge1 = edge(1L, 2L, 300.0, 50.7827 to 6.0800, 50.7854 to 6.0800)
        val edge2 = edge(2L, 3L, 300.0, 50.7854 to 6.0800, 50.7881 to 6.0800)
        val edge3 = edge(3L, 4L, 300.0, 50.7881 to 6.0800, 50.7908 to 6.0800)
        val edge4 = edge(4L, 5L, 300.0, 50.7908 to 6.0800, 50.7935 to 6.0800)

        // Long archetype: 5 edges (1500m). Short archetype: first 3 of those edges (900m) — fully
        // contained (shared/own = 1.0 ≥ INTERVAL_SUBSET_OVERLAP_THRESHOLD) but 600m apart in
        // length, beyond the merge length tolerance (max(FLOOR=500m, PCT=20% of 1500m=300m) =
        // 500m) — a deliberately different-length variant of the same road (e.g. a climb extended
        // much further onto a follow-up road), not a near-duplicate, so it must NOT merge despite
        // full edge overlap: both stay as their own archetypes.
        val long1 = makeInterval(id = 1, distanceM = 1500.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))
        val short1 = makeInterval(id = 2, distanceM = 900.0, gpsTrack = trackJson(startLat = 50.9000, pointCount = 6))

        val (service, saved, _) = buildService(listOf(long1, short1)) { track ->
            if (track.first()[0] < 50.85) listOf(edge0, edge1, edge2, edge3, edge4) else listOf(edge0, edge1, edge2)
        }

        service.runClustering()

        assertEquals(2, saved.size)
        val byFrequency = saved.sortedByDescending { it.distanceM }
        assertEquals(setOf(1L), byFrequency[0].intervals.map { it.id }.toSet())
        assertEquals(listOf(edge0, edge1, edge2, edge3, edge4), byFrequency[0].edges)
        assertEquals(setOf(2L), byFrequency[1].intervals.map { it.id }.toSet())
        assertEquals(listOf(edge0, edge1, edge2), byFrequency[1].edges)
    }

    @Test
    fun `on a long route, the percentage tolerance can exceed the floor and let a merge through`() = runTest {
        // Mirrors a real pair a user reported ("Camerig lang" 3283m / "Camerig mit Wald" 2651m,
        // delta 632m, 95% edge overlap): on a long enough route, PCT of the longer archetype's
        // distance can exceed FLOOR, so raising PCT (10% -> 20%) is what let this merge, not FLOOR.
        val edge0 = edge(0L, 1L, 500.0, 50.7800 to 6.0800, 50.7845 to 6.0800)
        val edge1 = edge(1L, 2L, 500.0, 50.7845 to 6.0800, 50.7890 to 6.0800)
        val edge2 = edge(2L, 3L, 500.0, 50.7890 to 6.0800, 50.7935 to 6.0800)
        val edge3 = edge(3L, 4L, 500.0, 50.7935 to 6.0800, 50.7980 to 6.0800)
        val edge4 = edge(4L, 5L, 500.0, 50.7980 to 6.0800, 50.8025 to 6.0800)
        val edge5 = edge(5L, 6L, 500.0, 50.8025 to 6.0800, 50.8070 to 6.0800)
        val edge4Alt = edge(4L, 7L, 450.0, 50.7980 to 6.0800, 50.8023 to 6.0800)

        // Long archetype: 6 edges (3000m). Short archetype: first 4 shared edges (2000m) plus one
        // non-matching edge (450m) = 2450m — shared/own = 2000/2450 = 0.816 ≥
        // INTERVAL_SUBSET_OVERLAP_THRESHOLD. Delta is 550m: beyond FLOOR (500m) and beyond the old
        // 10% PCT (300m), but within the new 20% PCT of the longer archetype's distance (600m).
        val long1 = makeInterval(id = 1, distanceM = 3000.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))
        val short1 = makeInterval(id = 2, distanceM = 2450.0, gpsTrack = trackJson(startLat = 50.9000, pointCount = 6))

        val (service, saved, _) = buildService(listOf(long1, short1)) { track ->
            if (track.first()[0] < 50.85) listOf(edge0, edge1, edge2, edge3, edge4, edge5)
            else listOf(edge0, edge1, edge2, edge3, edge4Alt)
        }

        service.runClustering()

        assertEquals(1, saved.size)
        assertEquals(setOf(1L, 2L), saved.single().intervals.map { it.id }.toSet())
        assertEquals(listOf(edge0, edge1, edge2, edge3, edge4, edge5), saved.single().edges)
    }

    @Test
    fun `a candidate with geometrically close but edge-key-disjoint edges merges via the geometric fallback (road vs bike lane, #176)`() = runTest {
        // Longer archetype: "the road" — 5 edges (1000m).
        val edge0 = edge(0L, 1L, 200.0, 50.7800 to 6.0800, 50.7818 to 6.0800)
        val edge1 = edge(1L, 2L, 200.0, 50.7818 to 6.0800, 50.7836 to 6.0800)
        val edge2 = edge(2L, 3L, 200.0, 50.7836 to 6.0800, 50.7854 to 6.0800)
        val edge3 = edge(3L, 4L, 200.0, 50.7854 to 6.0800, 50.7872 to 6.0800)
        val edge4 = edge(4L, 5L, 200.0, 50.7872 to 6.0800, 50.7890 to 6.0800)

        // Shorter archetype: "the bike lane" running ~7m alongside the road's first 4 edges (200m
        // each = 800m) — entirely distinct node ids, so zero (fromNode, toNode) overlap with the
        // road, but well within INTERVAL_POINT_MATCH_RADIUS_M (20m), so it can only qualify via
        // the #176 geometric fallback, not the edge-key test.
        val bikeLane0 = edge(100L, 101L, 200.0, 50.7800 to 6.0801, 50.7818 to 6.0801)
        val bikeLane1 = edge(101L, 102L, 200.0, 50.7818 to 6.0801, 50.7836 to 6.0801)
        val bikeLane2 = edge(102L, 103L, 200.0, 50.7836 to 6.0801, 50.7854 to 6.0801)
        val bikeLane3 = edge(103L, 104L, 200.0, 50.7854 to 6.0801, 50.7872 to 6.0801)

        // Spatially far apart raw GPS tracks so the two intervals stay in separate Step-1 clusters
        // and only Step 2's archetype-level merge test is exercised.
        val long1 = makeInterval(id = 1, distanceM = 1000.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))
        val short1 = makeInterval(id = 2, distanceM = 800.0, gpsTrack = trackJson(startLat = 50.9000, pointCount = 6))

        val (service, saved, _) = buildService(listOf(long1, short1)) { track ->
            if (track.first()[0] < 50.85) listOf(edge0, edge1, edge2, edge3, edge4)
            else listOf(bikeLane0, bikeLane1, bikeLane2, bikeLane3)
        }

        service.runClustering()

        assertEquals(1, saved.size)
        assertEquals(setOf(1L, 2L), saved.single().intervals.map { it.id }.toSet())
        assertEquals(listOf(edge0, edge1, edge2, edge3, edge4), saved.single().edges)
    }

    @Test
    fun `a geometrically distant, edge-key-disjoint candidate does not merge via the geometric fallback`() = runTest {
        val edge0 = edge(0L, 1L, 200.0, 50.7800 to 6.0800, 50.7818 to 6.0800)
        val edge1 = edge(1L, 2L, 200.0, 50.7818 to 6.0800, 50.7836 to 6.0800)
        val edge2 = edge(2L, 3L, 200.0, 50.7836 to 6.0800, 50.7854 to 6.0800)
        val edge3 = edge(3L, 4L, 200.0, 50.7854 to 6.0800, 50.7872 to 6.0800)
        val edge4 = edge(4L, 5L, 200.0, 50.7872 to 6.0800, 50.7890 to 6.0800)

        // A genuinely separate parallel road ~32m away (well beyond INTERVAL_POINT_MATCH_RADIUS_M,
        // 20m) — distinct node ids AND too far apart geometrically, so neither overlap test
        // qualifies; must not be mistaken for the road/bike-lane case above.
        val parallel0 = edge(100L, 101L, 200.0, 50.7800 to 6.08045, 50.7818 to 6.08045)
        val parallel1 = edge(101L, 102L, 200.0, 50.7818 to 6.08045, 50.7836 to 6.08045)
        val parallel2 = edge(102L, 103L, 200.0, 50.7836 to 6.08045, 50.7854 to 6.08045)
        val parallel3 = edge(103L, 104L, 200.0, 50.7854 to 6.08045, 50.7872 to 6.08045)

        val long1 = makeInterval(id = 1, distanceM = 1000.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))
        val short1 = makeInterval(id = 2, distanceM = 800.0, gpsTrack = trackJson(startLat = 50.9000, pointCount = 6))

        val (service, saved, _) = buildService(listOf(long1, short1)) { track ->
            if (track.first()[0] < 50.85) listOf(edge0, edge1, edge2, edge3, edge4)
            else listOf(parallel0, parallel1, parallel2, parallel3)
        }

        service.runClustering()

        assertEquals(2, saved.size)
        val byFrequency = saved.sortedByDescending { it.distanceM }
        assertEquals(setOf(1L), byFrequency[0].intervals.map { it.id }.toSet())
        assertEquals(setOf(2L), byFrequency[1].intervals.map { it.id }.toSet())
    }

    @Test
    fun `a cluster is still saved via GPS-only fallback when map-matching fails for every member`() = runTest {
        // Two GPS-similar intervals that qualify as a cluster, but the fake matcher never returns
        // road-graph edges for them (simulating a leaf-pruned/gapped road graph) — the whole cluster
        // must not be silently dropped (#175).
        val a = makeInterval(id = 1, distanceM = 200.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 8))
        val b = makeInterval(id = 2, distanceM = 205.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 8))

        val (service, saved, _) = buildService(listOf(a, b)) { null }

        service.runClustering()

        assertEquals(1, saved.size)
        val archetype = saved.single()
        assertEquals(setOf(1L, 2L), archetype.intervals.map { it.id }.toSet())
        assertTrue("GPS-only fallback should have no map-matched edges", archetype.edges.isEmpty())
        // Falls back to the median-length member's (sorted by distanceM, index size/2 = b here)
        // own recorded distance/endpoints.
        assertEquals(b.distanceM, archetype.distanceM, 0.001)
        assertEquals(b.startLat, archetype.startLat, 0.0001)
        assertEquals(b.endLat, archetype.endLat, 0.0001)
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

    @Test
    fun `when a merge absorbs a previously-separate archetype, the more-established name and id win`() = runTest {
        val edge0 = edge(0L, 1L, 90.0, 50.7800 to 6.0800, 50.7809 to 6.0800)
        val edge1 = edge(1L, 2L, 90.0, 50.7809 to 6.0800, 50.7818 to 6.0800)
        val edge2 = edge(2L, 3L, 90.0, 50.7818 to 6.0800, 50.7827 to 6.0800)
        val edge3 = edge(3L, 4L, 90.0, 50.7827 to 6.0800, 50.7836 to 6.0800)

        // Prior run: two separate archetypes — a curated "Schlangenweg" (2 intervals) and an
        // auto-numbered near-duplicate (1 interval) that will turn out to be a length-similar
        // subset of it once map-matched this run.
        val schlangenweg = RepeatedInterval(
            id = 42L, name = "Schlangenweg",
            intervals = listOf(makeInterval(id = 1, distanceM = 360.0, gpsTrack = "[]"), makeInterval(id = 2, distanceM = 360.0, gpsTrack = "[]")),
            edges = listOf(edge0, edge1, edge2, edge3),
            startLat = 50.7800, startLon = 6.0800, endLat = 50.7836, endLon = 6.0800,
            distanceM = 360.0
        )
        val autoNumbered = RepeatedInterval(
            id = 99L, name = "Repeated Interval 8",
            intervals = listOf(makeInterval(id = 3, distanceM = 270.0, gpsTrack = "[]")),
            edges = listOf(edge0, edge1, edge2),
            startLat = 50.9000, startLon = 6.0800, endLat = 50.9027, endLon = 6.0800,
            distanceM = 270.0
        )

        // This run: {1, 2} cluster together (similar GPS tracks) and map-match to all 4 edges;
        // {3} is spatially distinct so it forms its own cluster, matching only the first 3 edges —
        // a subset within length tolerance of the {1, 2} archetype, so Step 2 merges it in.
        val a = makeInterval(id = 1, distanceM = 360.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))
        val b = makeInterval(id = 2, distanceM = 362.0, gpsTrack = trackJson(startLat = 50.7800, pointCount = 6))
        val c = makeInterval(id = 3, distanceM = 270.0, gpsTrack = trackJson(startLat = 50.9000, pointCount = 6))

        val (service, saved, deleted) = buildService(
            listOf(a, b, c),
            existing = listOf(schlangenweg, autoNumbered)
        ) { track ->
            if (track.first()[0] < 50.85) listOf(edge0, edge1, edge2, edge3) else listOf(edge0, edge1, edge2)
        }

        service.runClustering()

        assertEquals(1, saved.size)
        val result = saved.single()
        assertEquals("the more-established (2-interval) archetype's name should win", "Schlangenweg", result.name)
        assertEquals(42L, result.id)
        assertEquals(setOf(1L, 2L, 3L), result.intervals.map { it.id }.toSet())
        assertEquals(listOf(99L), deleted.flatten())
    }
}
