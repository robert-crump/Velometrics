package com.velometrics.app.util

import com.velometrics.app.data.local.entity.CorridorEntity
import org.junit.Assert.*
import org.junit.Test

class EntityMappersTest {

    @Test
    fun `corridor toDomain parses edge_list and maps all fields`() {
        val entity = CorridorEntity(
            id = 42L,
            entryNode = 100L,
            exitNode = 200L,
            lengthM = 1500.0,
            pedalReward = 8.0,
            gravityReward = 3.0,
            exitHazardScore = 0.45,
            centroidLat = 50.78,
            centroidLon = 6.08,
            edgeList = "[[101,102],[102,103],[103,987]]",
            popularity = 12,
            groupId = 42L,
        )

        val corridor = entity.toDomain()

        assertEquals(42L, corridor.id)
        assertEquals(100L, corridor.entryNode)
        assertEquals(200L, corridor.exitNode)
        assertEquals(1500.0, corridor.lengthM, 1e-9)
        assertEquals(8.0, corridor.pedalReward, 1e-9)
        assertEquals(3.0, corridor.gravityReward, 1e-9)
        assertEquals(0.45, corridor.exitHazardScore, 1e-9)
        assertEquals(50.78, corridor.centroidLat, 1e-9)
        assertEquals(6.08, corridor.centroidLon, 1e-9)
        assertEquals(12, corridor.popularity)
        assertEquals(42L, corridor.groupId)
        assertEquals(3, corridor.edgeList.size)
        assertEquals(Pair(101L, 102L), corridor.edgeList[0])
        assertEquals(Pair(102L, 103L), corridor.edgeList[1])
        assertEquals(Pair(103L, 987L), corridor.edgeList[2])
    }

    @Test
    fun `corridor toDomain handles empty edge_list`() {
        val entity = CorridorEntity(
            id = 1L,
            entryNode = 10L,
            exitNode = 20L,
            lengthM = 500.0,
            pedalReward = 0.0,
            gravityReward = 0.0,
            exitHazardScore = 0.0,
            centroidLat = 50.0,
            centroidLon = 6.0,
            edgeList = "[]",
            popularity = 0,
            groupId = 1L,
        )

        val corridor = entity.toDomain()

        assertTrue(corridor.edgeList.isEmpty())
    }
}
