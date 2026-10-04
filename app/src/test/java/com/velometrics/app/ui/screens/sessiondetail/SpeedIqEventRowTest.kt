package com.velometrics.app.ui.screens.sessiondetail

import com.velometrics.app.domain.model.BrakingEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedIqEventRowTest {

    private fun event(penaltySec: Double, standingSec: Double, peakKmh: Double = 50.0) = BrakingEvent(
        km = 17.5, brakingEnergyJ = if (penaltySec > 0) penaltySec * 190 else 0.0, penaltySec = penaltySec,
        peakKmh = if (penaltySec > 0) peakKmh else 0.0, lowKmh = 0.0, lat = 50.0, lon = 6.0,
        standingSec = standingSec
    )

    @Test
    fun `braking and standing`() {
        assertEquals(
            "km 17.5 · 1:17 lost · 57 s braking + 20 s standing · 50→0 km/h",
            speedIqEventRow(event(57.0, 20.0))
        )
    }

    @Test
    fun `braking only`() {
        assertEquals("km 17.5 · 0:31 lost · 31 s braking · 50→0 km/h", speedIqEventRow(event(31.0, 0.0)))
    }

    @Test
    fun `standing only has no speeds`() {
        assertEquals("km 17.5 · 1:40 lost · 1:40 standing", speedIqEventRow(event(0.0, 100.0)))
    }
}
