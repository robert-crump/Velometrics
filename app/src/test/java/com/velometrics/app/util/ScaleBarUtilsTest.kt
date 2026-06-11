package com.velometrics.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaleBarUtilsTest {

    @Test
    fun `niceDistanceMeters - rounds down to nearest 1-2-5 step`() {
        assertEquals(1.0, ScaleBarUtils.niceDistanceMeters(1.0), 0.0)
        assertEquals(1.0, ScaleBarUtils.niceDistanceMeters(1.9), 0.0)
        assertEquals(2.0, ScaleBarUtils.niceDistanceMeters(2.0), 0.0)
        assertEquals(2.0, ScaleBarUtils.niceDistanceMeters(4.9), 0.0)
        assertEquals(5.0, ScaleBarUtils.niceDistanceMeters(5.0), 0.0)
        assertEquals(5.0, ScaleBarUtils.niceDistanceMeters(9.9), 0.0)
        assertEquals(10.0, ScaleBarUtils.niceDistanceMeters(10.0), 0.0)
        assertEquals(50.0, ScaleBarUtils.niceDistanceMeters(99.0), 0.0)
        assertEquals(100.0, ScaleBarUtils.niceDistanceMeters(100.0), 0.0)
        assertEquals(500.0, ScaleBarUtils.niceDistanceMeters(999.0), 0.0)
        assertEquals(1000.0, ScaleBarUtils.niceDistanceMeters(1000.0), 0.0)
        assertEquals(5000.0, ScaleBarUtils.niceDistanceMeters(9999.0), 0.0)
    }

    @Test
    fun `niceDistanceMeters - clamps to a minimum of 1 meter`() {
        assertEquals(1.0, ScaleBarUtils.niceDistanceMeters(0.5), 0.0)
        assertEquals(1.0, ScaleBarUtils.niceDistanceMeters(0.0), 0.0)
    }

    @Test
    fun `formatLabel - meters below 1000`() {
        assertEquals("1 m", ScaleBarUtils.formatLabel(1.0))
        assertEquals("50 m", ScaleBarUtils.formatLabel(50.0))
        assertEquals("500 m", ScaleBarUtils.formatLabel(500.0))
    }

    @Test
    fun `formatLabel - kilometers at and above 1000`() {
        assertEquals("1 km", ScaleBarUtils.formatLabel(1000.0))
        assertEquals("5 km", ScaleBarUtils.formatLabel(5000.0))
        assertEquals("20 km", ScaleBarUtils.formatLabel(20000.0))
    }

    @Test
    fun `computeScaleBar - picks nice distance and scales width accordingly`() {
        // 1 meter per pixel, max width 80px -> largest nice step <= 80 is 50
        val info = ScaleBarUtils.computeScaleBar(metersPerPixel = 1.0, maxWidthPx = 80.0)
        assertEquals("50 m", info.label)
        assertEquals(50.0, info.widthPx, 0.0001)
    }

    @Test
    fun `computeScaleBar - kilometer scale`() {
        // 50 meters per pixel, max width 80px -> max 4000m, nice step is 2000m -> "2 km"
        val info = ScaleBarUtils.computeScaleBar(metersPerPixel = 50.0, maxWidthPx = 80.0)
        assertEquals("2 km", info.label)
        assertEquals(40.0, info.widthPx, 0.0001)
    }
}
