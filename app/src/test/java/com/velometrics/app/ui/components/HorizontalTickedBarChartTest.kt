package com.velometrics.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalTickedBarChartTest {

    @Test
    fun `largest value ends at 80 percent of the bar area`() {
        assertEquals(0.8f, barFraction(42f, 42f), 1e-6f)
    }

    @Test
    fun `smaller values keep their proportion to the largest`() {
        assertEquals(0.4f, barFraction(21f, 42f), 1e-6f)
        assertEquals(0.2f, barFraction(10f, 40f), 1e-6f)
    }

    @Test
    fun `zero value has zero fraction`() {
        assertEquals(0f, barFraction(0f, 30f), 0f)
    }
}
