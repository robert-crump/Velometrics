package com.velometrics.app.ui.components

import com.velometrics.app.domain.model.BrakingEvent
import com.velometrics.app.domain.model.SpeedIq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedIqMarkersTest {

    private fun event(lat: Double, lon: Double) = BrakingEvent(
        km = 1.0, brakingEnergyJ = 5000.0, penaltySec = 20.0, peakKmh = 40.0, lowKmh = 5.0, lat = lat, lon = lon
    )

    private fun speedIq(events: List<BrakingEvent>, hasElevation: Boolean = true) = SpeedIq(
        hasElevation = hasElevation, brakingPenaltySec = 60.0, standingSec = 0.0, standingInTimerSec = 0.0,
        eventCount = events.size, referencePowerW = 200, systemMassKg = 85.0, topEvents = events
    )

    private val events = listOf(event(50.1, 6.1), event(50.2, 6.2), event(50.3, 6.3))

    @Test
    fun `on shows one marker per listed event, numbered like the list`() {
        assertEquals(
            listOf(SpeedIqMarker(1, 50.1, 6.1), SpeedIqMarker(2, 50.2, 6.2), SpeedIqMarker(3, 50.3, 6.3)),
            speedIqMarkers(speedIq(events), showOnMap = true)
        )
    }

    @Test
    fun `off shows no markers`() {
        assertTrue(speedIqMarkers(speedIq(events), showOnMap = false).isEmpty())
    }

    @Test
    fun `a ride without events or Speed IQ shows no markers`() {
        assertTrue(speedIqMarkers(speedIq(emptyList()), showOnMap = true).isEmpty())
        assertTrue(speedIqMarkers(speedIq(events, hasElevation = false), showOnMap = true).isEmpty())
        assertTrue(speedIqMarkers(null, showOnMap = true).isEmpty())
    }
}
