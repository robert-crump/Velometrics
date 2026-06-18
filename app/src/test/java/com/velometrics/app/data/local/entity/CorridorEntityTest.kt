package com.velometrics.app.data.local.entity

import org.junit.Assert.*
import org.junit.Test

class CorridorEntityTest {

    @Test
    fun `maps all fields to domain model`() {
        val entity = CorridorEntity(
            id = 42,
            entryNode = 100L,
            exitNode = 200L,
            lengthM = 1500.0,
            pedalReward = 8.0,
            gravityReward = 3.0,
            predictedReward = 0.0,
            exitHazardScore = 0.45,
            type = "measured",
            centroidLat = 50.78,
            centroidLon = 6.08,
        )

        val corridor = entity.toDomain()

        assertEquals(42L, corridor.id)
        assertEquals(100L, corridor.entryNode)
        assertEquals(200L, corridor.exitNode)
        assertEquals(1500.0, corridor.lengthM, 1e-9)
        assertEquals(8.0, corridor.pedalReward, 1e-9)
        assertEquals(3.0, corridor.gravityReward, 1e-9)
        assertEquals(0.0, corridor.predictedReward, 1e-9)
        assertEquals(0.45, corridor.exitHazardScore, 1e-9)
        assertEquals("measured", corridor.type)
        assertEquals(50.78, corridor.centroidLat, 1e-9)
        assertEquals(6.08, corridor.centroidLon, 1e-9)
    }

    @Test
    fun `predicted corridor has zero pedal and gravity reward`() {
        val entity = CorridorEntity(
            id = 1,
            entryNode = 10L,
            exitNode = 20L,
            lengthM = 800.0,
            pedalReward = 0.0,
            gravityReward = 0.0,
            predictedReward = 2.5,
            exitHazardScore = 0.0,
            type = "predicted",
            centroidLat = 50.80,
            centroidLon = 6.10,
        )

        val corridor = entity.toDomain()

        assertEquals("predicted", corridor.type)
        assertEquals(0.0, corridor.pedalReward, 1e-9)
        assertEquals(0.0, corridor.gravityReward, 1e-9)
        assertEquals(2.5, corridor.predictedReward, 1e-9)
    }
}
