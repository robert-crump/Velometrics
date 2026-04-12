package com.cyclegraph.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MapOverlayUtilsTest {

    @Test
    fun `speedToColor - 0 kmh returns black`() {
        assertEquals("#000000", MapOverlayUtils.speedToColor(0.0))
    }

    @Test
    fun `speedToColor - 15 kmh returns yellow`() {
        assertEquals("#FFEDA0", MapOverlayUtils.speedToColor(15.0))
    }

    @Test
    fun `speedToColor - 22 kmh returns gold`() {
        assertEquals("#FEB24C", MapOverlayUtils.speedToColor(22.0))
    }

    @Test
    fun `speedToColor - 27 kmh returns dark orange`() {
        assertEquals("#FD8D3C", MapOverlayUtils.speedToColor(27.0))
    }

    @Test
    fun `speedToColor - 35 kmh returns red`() {
        assertEquals("#F03B20", MapOverlayUtils.speedToColor(35.0))
    }

    @Test
    fun `speedToColor - 45 kmh returns dark red`() {
        assertEquals("#BD0026", MapOverlayUtils.speedToColor(45.0))
    }

    @Test
    fun `speedToColor - 55 kmh returns light blue`() {
        assertEquals("#6BAED6", MapOverlayUtils.speedToColor(55.0))
    }
}
