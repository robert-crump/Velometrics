package com.velometrics.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartAxisTest {

    @Test
    fun `roundedAxisBounds rounds outward`() {
        assertEquals(0f to 5f, roundedAxisBounds(0.4f, 4.2f, 5f))
        assertEquals(10f to 30f, roundedAxisBounds(12f, 21f))
    }

    @Test
    fun `integer ticks for a 0 to 5 range use unit-friendly steps`() {
        assertEquals(listOf(0f, 2f, 4f), integerAxisTicks(0f, 5f))
    }

    @Test
    fun `integer ticks pick a nice step for wide ranges`() {
        assertEquals(listOf(0f, 25f, 50f, 75f, 100f), integerAxisTicks(0f, 100f))
    }

    @Test
    fun `degenerate range yields a single tick`() {
        assertEquals(listOf(7f), integerAxisTicks(7f, 7f))
    }

    @Test
    fun `power axis uses multiples of 50 watts with at most five intervals`() {
        assertEquals(listOf(0f, 50f, 100f, 150f, 200f, 250f), integerAxisTicks(0f, 250f, 5, 50))
        assertEquals(listOf(0f, 100f, 200f, 300f), integerAxisTicks(0f, 300f, 5, 50))
        assertEquals(listOf(0f, 150f, 300f, 450f, 600f, 750f), integerAxisTicks(0f, 750f, 5, 50))
        assertEquals(listOf(0f, 50f), integerAxisTicks(0f, 50f, 5, 50))
    }

    @Test
    fun `ride count axis steps by 5`() {
        assertEquals(listOf(0f, 5f), integerAxisTicks(0f, 5f, 5, 5))
        assertEquals(listOf(0f, 5f, 10f, 15f, 20f, 25f), integerAxisTicks(0f, 25f, 5, 5))
        assertEquals(listOf(0f, 10f, 20f, 30f), integerAxisTicks(0f, 30f, 5, 5))
    }

    @Test
    fun `even ticks include both ends`() {
        assertEquals(listOf(0f, 1f, 2f, 3f, 4f), evenAxisTicks(0f, 4f, 4))
    }

    @Test
    fun `label stride caps label count`() {
        assertEquals(1, labelStride(3, 6))
        assertEquals(1, labelStride(11, 6))
        assertEquals(2, labelStride(12, 6))
        assertEquals(6, labelStride(30, 5))
    }

    @Test
    fun `scales map and invert`() {
        val rect = PlotRect.fromPadding(200f, 100f, 20f, 10f, 20f, 10f)
        assertEquals(20f, rect.left, 0f)
        assertEquals(180f, rect.right, 0f)
        val x = rect.xScale(0.0, 10.0)
        assertEquals(100f, x.map(5.0), 0.001f)
        assertEquals(5.0, x.invert(100f), 0.001)
        val y = rect.yScale(0.0, 10.0)
        assertEquals(90f, y.map(0.0), 0.001f)
        assertEquals(10f, y.map(10.0), 0.001f)
    }

    @Test
    fun `index scale spaces points evenly and centres a lone point`() {
        val rect = PlotRect(0f, 0f, 100f, 50f)
        val s = rect.indexScale(5)
        assertEquals(0f, s.map(0.0), 0.001f)
        assertEquals(50f, s.map(2.0), 0.001f)
        assertEquals(2.0, s.invert(50f), 0.001)
        assertEquals(50f, rect.indexScale(1).map(0.0), 0.001f)
    }
}
