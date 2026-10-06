package com.velometrics.app.util

import com.velometrics.app.domain.model.SpeedIq
import com.velometrics.app.domain.model.SpeedIqEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedIqSummaryTest {

    @Test
    fun `the system weight and its source survive the stored JSON`() {
        val speedIq = SpeedIq(
            hasElevation = true, brakingPenaltySec = 60.0, standingSec = 0.0, standingInTimerSec = 0.0,
            eventCount = 2, referencePowerW = 190, systemMassKg = 72.0, massFromSettings = true, topEvents = emptyList()
        )

        val restored = SpeedIqSummary.of(speedIq).toJsonString().parseJson<SpeedIqSummary>().toDomain(emptyList())

        assertEquals(72.0, restored.systemMassKg, 0.0)
        assertTrue(restored.massFromSettings)
    }

    @Test
    fun `a summary stored before the weight setting reads as the assumed default`() {
        val json = """{"hasElevation":true,"brakingPenaltySec":60.0,"eventCount":2,"referencePowerW":190,""" +
            """"systemMassKg":85.0,"standingSec":0.0,"standingInTimerSec":0.0}"""

        val restored = json.parseJson<SpeedIqSummary>().toDomain(emptyList())

        assertEquals(85.0, restored.systemMassKg, 0.0)
        assertFalse(restored.massFromSettings)
    }

    @Test
    fun `an estimated P survives the stored JSON and older summaries read as measured`() {
        val speedIq = SpeedIq(
            hasElevation = true, brakingPenaltySec = 60.0, standingSec = 0.0, standingInTimerSec = 0.0,
            eventCount = 2, referencePowerW = 165, systemMassKg = 85.0, referencePowerEstimated = true, topEvents = emptyList()
        )
        val restored = SpeedIqSummary.of(speedIq).toJsonString().parseJson<SpeedIqSummary>().toDomain(emptyList())
        assertTrue(restored.referencePowerEstimated)
        assertEquals(165, restored.referencePowerW)

        val old = """{"hasElevation":true,"brakingPenaltySec":60.0,"eventCount":2,"referencePowerW":190,"systemMassKg":85.0}"""
        assertFalse(old.parseJson<SpeedIqSummary>().toDomain(emptyList()).referencePowerEstimated)
    }

    @Test
    fun `slow loss and k survive the stored JSON and older summaries read as not analysed`() {
        val speedIq = SpeedIq(
            hasElevation = true, brakingPenaltySec = 60.0, standingSec = 0.0, standingInTimerSec = 0.0,
            eventCount = 2, referencePowerW = 190, systemMassKg = 85.0, topEvents = emptyList(),
            slowSec = 42.0, speedFactor = 1.02
        )
        val restored = SpeedIqSummary.of(speedIq).toJsonString().parseJson<SpeedIqSummary>().toDomain(emptyList())
        assertEquals(42.0, restored.slowSec!!, 0.0)
        assertEquals(1.02, restored.speedFactor!!, 0.0)

        val old = """{"hasElevation":true,"brakingPenaltySec":60.0,"eventCount":2,"referencePowerW":190,"systemMassKg":85.0}"""
        val restoredOld = old.parseJson<SpeedIqSummary>().toDomain(emptyList())
        assertNull(restoredOld.slowSec)
        assertNull(restoredOld.speedFactor)
    }

    @Test
    fun `events stored before slow segments read with no slow loss`() {
        val json = """[{"km":17.5,"brakingEnergyJ":9700.0,"penaltySec":51.0,"peakKmh":50.0,"lowKmh":0.0,"lat":50.0,"lon":6.0,"standingSec":20.0}]"""
        val event = json.parseJson<List<SpeedIqEvent>>().single()
        assertEquals(0.0, event.slowSec, 0.0)
        assertNull(event.slowAvgKmh)
        assertEquals(71.0, event.lostSec, 1e-9)
    }
}
