package com.velometrics.app.domain.service

import com.velometrics.app.data.local.dao.IntervalPrototypeRouteDao
import com.velometrics.app.data.local.entity.IntervalPrototypeRouteEntity
import com.velometrics.app.domain.model.IntervalPrototypeRoute
import com.velometrics.app.domain.model.IntervalSession
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

class IntervalMatcherTest {

    private lateinit var matcher: IntervalMatcher
    private val gson = Gson()

    /** Fake DAO that returns an empty list — we only use the pure matchIntervals function. */
    private val fakeDao = object : IntervalPrototypeRouteDao {
        override suspend fun insert(route: IntervalPrototypeRouteEntity): Long = 0
        override suspend fun update(route: IntervalPrototypeRouteEntity) {}
        override suspend fun delete(route: IntervalPrototypeRouteEntity) {}
        override fun getAll(): Flow<List<IntervalPrototypeRouteEntity>> = flowOf(emptyList())
        override suspend fun getById(id: Long): IntervalPrototypeRouteEntity? = null
    }

    @Before
    fun setUp() {
        matcher = IntervalMatcher(fakeDao)
    }

    /**
     * Build a GPS track JSON from a list of [lat, lon] pairs.
     * Points increment lat by 0.00001 (~1.11m) per index, fixed lon 6.07.
     */
    private fun buildGpsTrack(pointCount: Int, startLat: Double = 50.78, lon: Double = 6.07): String {
        val points = (0 until pointCount).map { i ->
            listOf(startLat + i * 0.00001, lon)
        }
        return gson.toJson(points)
    }

    private fun makeInterval(
        startLat: Double = 50.78,
        startLon: Double = 6.07,
        endLat: Double = 50.785,
        endLon: Double = 6.07,
        gpsTrack: String = "[]"
    ): IntervalSession {
        return IntervalSession(
            id = 0,
            cyclingSessionId = 1L,
            startTimestamp = Instant.parse("2025-01-01T10:00:00Z"),
            durationSec = 200,
            durationNormalizedSec = 200,
            distanceM = 500.0,
            avgPower = 310,
            avgSpeedKmh = 30.0,
            avgSpeedNormalizedKmh = 30.0,
            direction = "north",
            startLat = startLat,
            startLon = startLon,
            endLat = endLat,
            endLon = endLon,
            gpsTrack = gpsTrack,
            prototypeRouteId = null
        )
    }

    @Test
    fun `empty prototypes leaves prototypeRouteId null`() {
        val track = buildGpsTrack(250)
        val interval = makeInterval(gpsTrack = track)
        val result = matcher.matchIntervals(listOf(interval), emptyList())
        assertEquals(1, result.size)
        assertNull("prototypeRouteId should be null", result[0].prototypeRouteId)
    }

    @Test
    fun `start and end within 50m matches prototype`() {
        val startLat = 50.78
        val lon = 6.07
        val pointCount = 250
        val track = buildGpsTrack(pointCount, startLat, lon)
        // End point of track: startLat + 249 * 0.00001 = 50.78249
        val endLat = startLat + 249 * 0.00001
        val interval = makeInterval(
            startLat = startLat,
            startLon = lon,
            endLat = endLat,
            endLon = lon,
            gpsTrack = track
        )
        val proto = IntervalPrototypeRoute(
            id = 10,
            name = "Test Proto",
            startLat = startLat,      // exact match at start
            startLon = lon,
            endLat = endLat,          // exact match at track point i=249
            endLon = lon,
            distanceM = 500.0,
            avgGpsTrack = null
        )
        val result = matcher.matchIntervals(listOf(interval), listOf(proto))
        assertEquals(10L, result[0].prototypeRouteId)
    }

    @Test
    fun `start too far does not match`() {
        val startLat = 50.78
        val lon = 6.07
        val track = buildGpsTrack(250, startLat, lon)
        val interval = makeInterval(
            startLat = startLat,
            startLon = lon,
            gpsTrack = track
        )
        // Prototype start is ~111m away (0.001 deg lat ≈ 111m, well outside 50m)
        val proto = IntervalPrototypeRoute(
            id = 20,
            name = "Far Proto",
            startLat = startLat + 0.001,
            startLon = lon,
            endLat = startLat + 249 * 0.00001,
            endLon = lon,
            distanceM = 500.0,
            avgGpsTrack = null
        )
        val result = matcher.matchIntervals(listOf(interval), listOf(proto))
        assertNull("Should not match when start is too far", result[0].prototypeRouteId)
    }

    @Test
    fun `two prototypes picks higher score`() {
        val startLat = 50.78
        val lon = 6.07
        val track = buildGpsTrack(250, startLat, lon)
        val interval = makeInterval(
            startLat = startLat,
            startLon = lon,
            gpsTrack = track
        )
        // Proto A: end at track point i=100 → score ~101
        val protoA = IntervalPrototypeRoute(
            id = 30,
            name = "Proto A",
            startLat = startLat,
            startLon = lon,
            endLat = startLat + 100 * 0.00001,
            endLon = lon,
            distanceM = 200.0,
            avgGpsTrack = null
        )
        // Proto B: end at track point i=200 → score ~201
        val protoB = IntervalPrototypeRoute(
            id = 40,
            name = "Proto B",
            startLat = startLat,
            startLon = lon,
            endLat = startLat + 200 * 0.00001,
            endLon = lon,
            distanceM = 400.0,
            avgGpsTrack = null
        )
        val result = matcher.matchIntervals(listOf(interval), listOf(protoA, protoB))
        assertEquals("Should pick Proto B (higher score)", 40L, result[0].prototypeRouteId)
    }
}
