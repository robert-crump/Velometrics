package com.velometrics.app.data.local.entity

import org.junit.Assert.*
import org.junit.Test

class MapTurnEntityTest {

    @Test
    fun `maps all fields to domain model`() {
        val entity = MapTurnEntity(
            fromNode = 1L,
            junctionNode = 2L,
            toNode = 3L,
            hazardScore = 0.45,
            hazardSource = "measured",
            stopPenalty = 2.1,
            stopPenaltySource = "measured",
            brakingProbability = 0.3,
            medianKeDelta = 12.1,
            stopPenaltyConfidence = 0.8,
        )

        val turn = entity.toDomain()

        assertEquals(1L, turn.fromNode)
        assertEquals(2L, turn.junctionNode)
        assertEquals(3L, turn.toNode)
        assertEquals(0.45, turn.hazardScore, 1e-9)
        assertEquals("measured", turn.hazardSource)
        assertEquals(2.1, turn.stopPenalty, 1e-9)
        assertEquals("measured", turn.stopPenaltySource)
        assertEquals(0.3, turn.brakingProbability)
        assertEquals(12.1, turn.medianKeDelta)
        assertEquals(0.8, turn.stopPenaltyConfidence)
    }

    @Test
    fun `optional fields are null when absent`() {
        val entity = MapTurnEntity(
            fromNode = 1L,
            junctionNode = 2L,
            toNode = 3L,
            hazardScore = 0.0,
            hazardSource = "highway_tag_prior",
            stopPenalty = 0.0,
            stopPenaltySource = "highway_tag_prior",
            brakingProbability = null,
            medianKeDelta = null,
            stopPenaltyConfidence = null,
        )

        val turn = entity.toDomain()

        assertNull(turn.brakingProbability)
        assertNull(turn.medianKeDelta)
        assertNull(turn.stopPenaltyConfidence)
    }
}
