package com.velometrics.app.domain.service

import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class TrackGeometryUtilsTest {

    private val straightTrack = listOf(
        LatLng(50.780, 6.070),
        LatLng(50.781, 6.070),
        LatLng(50.782, 6.070),
        LatLng(50.783, 6.070)
    )

    @Test
    fun `project point onto nearest segment`() {
        val point = LatLng(50.7815, 6.071) // slightly east of track, between seg 0-1
        val projection = TrackGeometryUtils.projectPointOntoTrack(point, straightTrack)

        // Point is between segments 0 and 1, could project to either
        assertTrue(projection.segmentIndex in 0..1)
        assertTrue(projection.distanceFromTrackM > 0)
        assertTrue(projection.distanceFromTrackM < 200) // should be close
    }

    @Test
    fun `project point exactly on track vertex`() {
        val point = LatLng(50.781, 6.070) // exactly on second point
        val projection = TrackGeometryUtils.projectPointOntoTrack(point, straightTrack)

        assertTrue(projection.distanceFromTrackM < 1.0) // essentially zero
    }

    @Test
    fun `remaining track from midpoint`() {
        val projection = TrackProjection(1, 0.5, 0.0, LatLng(50.7815, 6.070))
        val remaining = TrackGeometryUtils.remainingTrackFrom(straightTrack, projection)

        assertEquals(3, remaining.size) // projected point + 2 remaining vertices
        assertEquals(50.7815, remaining[0].latitude, 1e-6)
        assertEquals(50.782, remaining[1].latitude, 1e-6)
        assertEquals(50.783, remaining[2].latitude, 1e-6)
    }

    @Test
    fun `remaining track from start`() {
        val projection = TrackProjection(0, 0.0, 0.0, straightTrack[0])
        val remaining = TrackGeometryUtils.remainingTrackFrom(straightTrack, projection)

        assertEquals(4, remaining.size) // all points
    }

    @Test
    fun `distance along track between two projections`() {
        val from = TrackProjection(0, 0.0, 0.0, straightTrack[0])
        val to = TrackProjection(2, 1.0, 0.0, straightTrack[3])

        val distance = TrackGeometryUtils.computeDistanceAlongTrack(straightTrack, from, to)
        // 3 segments of ~111m each (0.001 deg lat ≈ 111m)
        assertTrue(distance > 300 && distance < 400)
    }

    @Test
    fun `distance along single segment`() {
        val from = TrackProjection(0, 0.0, 0.0, straightTrack[0])
        val to = TrackProjection(0, 1.0, 0.0, straightTrack[1])

        val distance = TrackGeometryUtils.computeDistanceAlongTrack(straightTrack, from, to)
        assertTrue(distance > 100 && distance < 120) // ~111m
    }

    @Test
    fun `bounding box with buffer`() {
        val points = listOf(LatLng(50.78, 6.07), LatLng(50.79, 6.08))
        val bbox = TrackGeometryUtils.computeBoundingBox(points, 500.0)

        assertTrue(bbox.minLat < 50.78)
        assertTrue(bbox.maxLat > 50.79)
        assertTrue(bbox.minLon < 6.07)
        assertTrue(bbox.maxLon > 6.08)
    }

    @Test
    fun `POI on-route has small off-route distance`() {
        val track = listOf(LatLng(50.780, 6.070), LatLng(50.790, 6.070))
        val (distAlong, offRoute) = TrackGeometryUtils.projectPoiOntoTrack(
            50.785, 6.070, track // directly on the track
        )

        assertTrue(offRoute < 10.0) // essentially on-route
        assertTrue(distAlong > 0)
    }

    @Test
    fun `POI far off-route has large off-route distance`() {
        val track = listOf(LatLng(50.780, 6.070), LatLng(50.790, 6.070))
        val (_, offRoute) = TrackGeometryUtils.projectPoiOntoTrack(
            50.785, 6.090, track // 0.02 deg east ≈ 1.4 km
        )

        assertTrue(offRoute > 1000.0)
    }

    @Test
    fun `bounding box with single point`() {
        val points = listOf(LatLng(50.78, 6.07))
        val bbox = TrackGeometryUtils.computeBoundingBox(points, 500.0)

        assertTrue(bbox.minLat < 50.78)
        assertTrue(bbox.maxLat > 50.78)
    }
}
