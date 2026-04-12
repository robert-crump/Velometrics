package com.cyclegraph.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun `formatDuration 0 seconds`() {
        assertEquals("0s", FormatUtils.formatDuration(0))
    }

    @Test
    fun `formatDuration seconds only`() {
        assertEquals("45s", FormatUtils.formatDuration(45))
    }

    @Test
    fun `formatDuration minutes and seconds`() {
        assertEquals("2m 5s", FormatUtils.formatDuration(125))
    }

    @Test
    fun `formatDuration hours and minutes`() {
        assertEquals("1h 1m", FormatUtils.formatDuration(3661))
    }

    @Test
    fun `formatDuration exactly one hour`() {
        assertEquals("1h 0m", FormatUtils.formatDuration(3600))
    }

    @Test
    fun `formatDistance normal`() {
        assertEquals("42.3 km", FormatUtils.formatDistance(42.345))
    }

    @Test
    fun `formatDistance less than one`() {
        assertEquals("0.8 km", FormatUtils.formatDistance(0.8))
    }

    @Test
    fun `formatSpeed normal`() {
        assertEquals("27.8 km/h", FormatUtils.formatSpeed(27.83))
    }

    @Test
    fun `formatPower normal`() {
        assertEquals("245 W", FormatUtils.formatPower(245))
    }

    @Test
    fun `formatComparison with median shows diff and percentage`() {
        val result = FormatUtils.formatComparison(245, 233, "W", true)
        assertEquals("245 W (+12 | +5%)", result)
    }

    @Test
    fun `formatComparison without median shows just value`() {
        val result = FormatUtils.formatComparison(245, null, "W", true)
        assertEquals("245 W", result)
    }

    @Test
    fun `formatComparison negative diff`() {
        val result = FormatUtils.formatComparison(220, 233, "W", true)
        assertEquals("220 W (-13 | -6%)", result)
    }

    @Test
    fun `formatComparison speed with decimal`() {
        val result = FormatUtils.formatComparison(28.5, 27.0, "km/h", true)
        assertEquals("28.5 km/h (+1.5 | +6%)", result)
    }

    @Test
    fun `formatDurationComparison positive diff`() {
        val result = FormatUtils.formatDurationComparison(5520, 5100)
        assertEquals("1h 32m (+7m 0s | +8%)", result)
    }

    @Test
    fun `formatDurationComparison negative diff`() {
        val result = FormatUtils.formatDurationComparison(4800, 5100)
        assertEquals("1h 20m (-5m 0s | -6%)", result)
    }

    @Test
    fun `formatDurationComparison no median`() {
        val result = FormatUtils.formatDurationComparison(5520, null)
        assertEquals("1h 32m", result)
    }

    @Test
    fun `formatDurationComparison zero diff`() {
        val result = FormatUtils.formatDurationComparison(5100, 5100)
        assertEquals("1h 25m (+0s | +0%)", result)
    }

    @Test
    fun `formatDurationLong always shows all components`() {
        assertEquals("0h 2m 5s", FormatUtils.formatDurationLong(125))
        assertEquals("1h 1m 1s", FormatUtils.formatDurationLong(3661))
    }
}
