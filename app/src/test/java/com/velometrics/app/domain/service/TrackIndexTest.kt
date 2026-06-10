package com.velometrics.app.domain.service

import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class TrackIndexTest {

    private val straightTrack = listOf(
        LatLng(50.780, 6.070),
        LatLng(50.781, 6.070),
        LatLng(50.782, 6.070),
        LatLng(50.783, 6.070)
    )

    @Test
    fun `project on-route point matches full scan`() {
        val index = TrackIndex.build(straightTrack)
        val point = LatLng(50.781, 6.070) // exactly on second point

        val expected = TrackGeometryUtils.projectPointOntoTrack(point, straightTrack)
        val actual = index.project(point)

        assertEquals(expected.segmentIndex, actual.segmentIndex)
        assertEquals(expected.fraction, actual.fraction, 1e-9)
        assertEquals(expected.distanceFromTrackM, actual.distanceFromTrackM, 1e-6)
    }

    @Test
    fun `project near-route point matches full scan`() {
        val index = TrackIndex.build(straightTrack)
        val point = LatLng(50.7815, 6.071) // slightly east of track, between seg 0-1

        val expected = TrackGeometryUtils.projectPointOntoTrack(point, straightTrack)
        val actual = index.project(point)

        assertEquals(expected.segmentIndex, actual.segmentIndex)
        assertEquals(expected.fraction, actual.fraction, 1e-9)
        assertEquals(expected.distanceFromTrackM, actual.distanceFromTrackM, 1e-6)
    }

    @Test
    fun `project off-route point falls back to full scan and matches`() {
        // ~700m east of the track - well outside the grid's 3x3 neighborhood for a small cell size
        val index = TrackIndex.build(straightTrack, cellSizeM = 50.0)
        val point = LatLng(50.780, 6.080)

        val expected = TrackGeometryUtils.projectPointOntoTrack(point, straightTrack)
        val actual = index.project(point)

        assertEquals(expected.segmentIndex, actual.segmentIndex)
        assertEquals(expected.fraction, actual.fraction, 1e-9)
        assertEquals(expected.distanceFromTrackM, actual.distanceFromTrackM, 1e-6)
    }

    @Test
    fun `distanceAlongTrack matches computeDistanceAlongTrack from start`() {
        val index = TrackIndex.build(straightTrack)
        val startProjection = TrackProjection(0, 0.0, 0.0, straightTrack[0])
        val projection = TrackProjection(2, 0.5, 0.0, straightTrack[2])

        val expected = TrackGeometryUtils.computeDistanceAlongTrack(straightTrack, startProjection, projection)
        val actual = index.distanceAlongTrack(projection)

        assertEquals(expected, actual, 1e-6)
    }

    @Test
    fun `distanceAlongTrack at track start is zero`() {
        val index = TrackIndex.build(straightTrack)
        val startProjection = TrackProjection(0, 0.0, 0.0, straightTrack[0])

        assertEquals(0.0, index.distanceAlongTrack(startProjection), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `build requires at least two points`() {
        TrackIndex.build(listOf(LatLng(50.780, 6.070)))
    }
}
