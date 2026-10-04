package com.velometrics.app.util

import com.velometrics.app.domain.model.SpeedIq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
