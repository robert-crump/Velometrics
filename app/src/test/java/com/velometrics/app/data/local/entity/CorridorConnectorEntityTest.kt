package com.velometrics.app.data.local.entity

import org.junit.Assert.*
import org.junit.Test

class CorridorConnectorEntityTest {

    @Test
    fun `maps all fields to domain model`() {
        val entity = CorridorConnectorEntity(
            fromCorridor = 1L,
            toCorridor = 5L,
            distanceM = 320.0,
        )

        val connector = entity.toDomain()

        assertEquals(1L, connector.fromCorridor)
        assertEquals(5L, connector.toCorridor)
        assertEquals(320.0, connector.distanceM, 1e-9)
    }
}
