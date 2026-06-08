package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import com.google.gson.Gson
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class IntervalMatcherTest {

    private val gson = Gson()
    private val matcher = IntervalMatcher(mockk<RepeatedIntervalRepository>(relaxed = true))

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

    private fun edge(from: Pair<Double, Double>, to: Pair<Double, Double>, lengthM: Double): MapEdge {
        return MapEdge(
            fromNode = 0L, toNode = 1L,
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

    private fun makeArchetype(id: Long, name: String, distanceM: Double, edges: List<MapEdge>): RepeatedInterval {
        return RepeatedInterval(
            id = id, name = name, intervals = emptyList(), edges = edges,
            startLat = 50.7800, startLon = 6.0800, endLat = 50.7813, endLon = 6.0800,
            distanceM = distanceM
        )
    }

    @Test
    fun `unambiguous match assigns interval to its sole qualifying archetype`() {
        // Track and archetype edges trace the same northbound line — should qualify.
        val track = trackJson(startLat = 50.7800, pointCount = 8)
        val interval = makeInterval(id = 1, distanceM = 200.0, gpsTrack = track)

        val archetypeEdge = edge(50.7800 to 6.0800, 50.7821 to 6.0800, lengthM = 205.0)
        val archetype = makeArchetype(id = 10, name = "Climb", distanceM = 205.0, edges = listOf(archetypeEdge))

        val result = matcher.matchIntervals(listOf(interval), listOf(archetype))

        assertEquals(archetype, result[interval])
    }

    @Test
    fun `dissimilar interval matches no archetype`() {
        val track = trackJson(startLat = 50.7800, pointCount = 8)
        val interval = makeInterval(id = 1, distanceM = 200.0, gpsTrack = track)

        // Archetype is far away and a very different length — neither length nor overlap qualifies.
        val archetypeEdge = edge(50.9000 to 6.2000, 50.9300 to 6.2000, lengthM = 2000.0)
        val archetype = makeArchetype(id = 20, name = "Far Climb", distanceM = 2000.0, edges = listOf(archetypeEdge))

        val result = matcher.matchIntervals(listOf(interval), listOf(archetype))

        assertNull(result[interval])
    }

    @Test
    fun `interval qualifying for multiple archetypes is assigned to the longer one`() {
        val track = trackJson(startLat = 50.7800, pointCount = 8)
        val interval = makeInterval(id = 1, distanceM = 200.0, gpsTrack = track)

        // Both archetypes trace the same line and overlap the track within tolerance/threshold —
        // the longer one (by distanceM) should win the tie-break.
        val shorterEdge = edge(50.7800 to 6.0800, 50.7821 to 6.0800, lengthM = 205.0)
        val shorter = makeArchetype(id = 30, name = "Shorter", distanceM = 205.0, edges = listOf(shorterEdge))

        val longerEdge = edge(50.7800 to 6.0800, 50.7821 to 6.0800, lengthM = 290.0)
        val longer = makeArchetype(id = 40, name = "Longer", distanceM = 290.0, edges = listOf(longerEdge))

        val result = matcher.matchIntervals(listOf(interval), listOf(shorter, longer))

        assertEquals(longer, result[interval])
    }
}
