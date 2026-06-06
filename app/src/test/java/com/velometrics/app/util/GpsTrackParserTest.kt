package com.velometrics.app.util

import org.junit.Assert.*
import org.junit.Test

class GpsTrackParserTest {

    @Test
    fun `valid track parses correctly`() {
        val json = "[[50.78,6.07],[50.79,6.08],[50.80,6.09]]"
        val result = GpsTrackParser.parse(json)
        assertEquals(3, result.size)
        assertEquals(50.78, result[0].latitude, 0.001)
        assertEquals(6.07, result[0].longitude, 0.001)
        assertEquals(50.79, result[1].latitude, 0.001)
        assertEquals(6.08, result[1].longitude, 0.001)
    }

    @Test
    fun `null input returns empty list`() {
        assertEquals(emptyList<Any>(), GpsTrackParser.parse(null))
    }

    @Test
    fun `empty string returns empty list`() {
        assertEquals(emptyList<Any>(), GpsTrackParser.parse(""))
    }

    @Test
    fun `blank string returns empty list`() {
        assertEquals(emptyList<Any>(), GpsTrackParser.parse("   "))
    }

    @Test
    fun `malformed JSON returns empty list`() {
        assertEquals(emptyList<Any>(), GpsTrackParser.parse("not json"))
    }

    @Test
    fun `empty array returns empty list`() {
        assertEquals(emptyList<Any>(), GpsTrackParser.parse("[]"))
    }

    @Test
    fun `single point returns list of one`() {
        val json = "[[50.78,6.07]]"
        val result = GpsTrackParser.parse(json)
        assertEquals(1, result.size)
        assertEquals(50.78, result[0].latitude, 0.001)
        assertEquals(6.07, result[0].longitude, 0.001)
    }

    @Test
    fun `sub-arrays with less than 2 elements are skipped`() {
        val json = "[[50.78],[50.79,6.08]]"
        val result = GpsTrackParser.parse(json)
        assertEquals(1, result.size)
        assertEquals(50.79, result[0].latitude, 0.001)
    }

    @Test
    fun `computeBounds with less than 2 points returns null`() {
        assertNull(GpsTrackParser.computeBounds(emptyList()))
        val singlePoint = GpsTrackParser.parse("[[50.78,6.07]]")
        assertNull(GpsTrackParser.computeBounds(singlePoint))
    }

    @Test
    fun `computeBounds with 3 points returns correct bounds`() {
        val points = GpsTrackParser.parse("[[50.70,6.00],[50.80,6.10],[50.75,6.05]]")
        val bounds = GpsTrackParser.computeBounds(points)
        assertNotNull(bounds)
        assertEquals(50.80, bounds!!.getLatNorth(), 0.001)
        assertEquals(50.70, bounds.getLatSouth(), 0.001)
        assertEquals(6.10, bounds.getLonEast(), 0.001)
        assertEquals(6.00, bounds.getLonWest(), 0.001)
    }
}
