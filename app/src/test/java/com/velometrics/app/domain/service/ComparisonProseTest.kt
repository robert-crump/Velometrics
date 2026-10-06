package com.velometrics.app.domain.service

import com.velometrics.app.domain.service.RecapMetricKind.AVERAGE_POWER
import com.velometrics.app.domain.service.RecapMetricKind.CARDIAC_DRIFT
import com.velometrics.app.domain.service.RecapMetricKind.DURATION
import com.velometrics.app.domain.service.RecapMetricKind.FAT_BURNED
import com.velometrics.app.domain.service.RecapMetricKind.FAT_EFFICIENCY
import com.velometrics.app.domain.service.RecapMetricKind.HEART_RATE
import com.velometrics.app.domain.service.RecapMetricKind.INTERVAL_COUNT
import com.velometrics.app.domain.service.RecapMetricKind.SPEED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonProseTest {

    private fun m(kind: RecapMetricKind, current: Double, median: Double) = RecapMetric(kind, current, median)

    private fun prose(vararg candidates: RecapMetric, anchor: RecapMetricKind? = null) =
        ComparisonProse.paragraph("other Zone 2 rides", "your typical Zone 2 ride", candidates.toList(), anchor)

    @Test
    fun `relative bands treat the boundary as comparable`() {
        assertTrue(m(DURATION, 6000.0, 5000.0).comparable)      // +20%
        assertFalse(m(DURATION, 6060.0, 5000.0).comparable)     // +21.2%
        assertTrue(m(AVERAGE_POWER, 189.0, 180.0).comparable)   // +5%
        assertFalse(m(AVERAGE_POWER, 190.0, 180.0).comparable)
        assertTrue(m(SPEED, 28.35, 27.0).comparable)            // +5%
        assertFalse(m(SPEED, 28.5, 27.0).comparable)
    }

    @Test
    fun `absolute bands for drift, fat efficiency and interval count`() {
        assertTrue(m(CARDIAC_DRIFT, 4.5, 3.5).comparable)
        assertFalse(m(CARDIAC_DRIFT, 4.6, 3.5).comparable)
        assertTrue(m(FAT_EFFICIENCY, 65.0, 60.0).comparable)
        assertFalse(m(FAT_EFFICIENCY, 66.0, 60.0).comparable)
        assertTrue(m(INTERVAL_COUNT, 6.0, 4.0).comparable)
        assertFalse(m(INTERVAL_COUNT, 7.0, 4.0).comparable)
    }

    @Test
    fun `a zero median only matches a zero value`() {
        assertTrue(m(FAT_BURNED, 0.0, 0.0).comparable)
        assertFalse(m(FAT_BURNED, 5.0, 0.0).comparable)
    }

    @Test
    fun `no candidates yields no paragraph`() {
        assertNull(prose())
    }

    @Test
    fun `differences then comparable metrics`() {
        assertEquals(
            "Compared with other Zone 2 rides, your ride was shorter (1h14min vs. 2h15min). " +
                "Average power (185 W vs. 180 W) and cardiac drift (3.2% vs. 3.5%) were comparable.",
            prose(m(DURATION, 4440.0, 8100.0), m(AVERAGE_POWER, 185.0, 180.0), m(CARDIAC_DRIFT, 3.2, 3.5), anchor = AVERAGE_POWER)
        )
    }

    @Test
    fun `all comparable`() {
        assertEquals(
            "This ride was close to your typical Zone 2 ride. " +
                "Average power (180 W vs. 180 W), heart rate (140 bpm vs. 141 bpm) and duration (1h00min vs. 1h00min) were all comparable.",
            prose(m(DURATION, 3600.0, 3600.0), m(HEART_RATE, 140.0, 141.0), m(AVERAGE_POWER, 180.0, 180.0), anchor = AVERAGE_POWER)
        )
    }

    @Test
    fun `all different puts the third into an also sentence`() {
        assertEquals(
            "Compared with other Zone 2 rides, you burned less fat (10 g vs. 30 g) and your ride was shorter (30min vs. 1h00min). " +
                "You also did more intervals (8 vs. 4).",
            prose(m(DURATION, 1800.0, 3600.0), m(FAT_BURNED, 10.0, 30.0), m(INTERVAL_COUNT, 8.0, 4.0))
        )
    }

    @Test
    fun `at most three metrics, anchor kept even when least notable`() {
        val text = prose(
            m(DURATION, 1800.0, 3600.0),          // 2.5 bands
            m(FAT_EFFICIENCY, 80.0, 60.0),        // 4 bands
            m(CARDIAC_DRIFT, 6.0, 3.0),           // 3 bands
            m(AVERAGE_POWER, 181.0, 180.0),       // comparable anchor
            anchor = AVERAGE_POWER
        )
        assertEquals(
            "Compared with other Zone 2 rides, fat efficiency was higher (80 vs. 60) and cardiac drift was higher (6.0% vs. 3.0%). " +
                "Average power (181 W vs. 180 W) was comparable.",
            text
        )
    }

    @Test
    fun `missing anchor falls back to plain ranking`() {
        assertEquals(
            "Compared with other Zone 2 rides, heart rate was lower (120 bpm vs. 140 bpm).",
            prose(m(HEART_RATE, 120.0, 140.0), anchor = AVERAGE_POWER)
        )
    }
}
