package com.velometrics.app.data.local.entity

import org.junit.Assert.*
import org.junit.Test

class MapEdgeEntityTest {

    private fun entity(
        metadata: String? = null,
        slopePercent: Double? = null,
        curvature: Double? = null,
        stopPenalty: Double? = null,
        stopPenaltySource: String? = null,
        predictedFlowScore: Double? = null,
        flowConfidence: Double? = null,
        hazardScore: Double? = null
    ) = MapEdgeEntity(
        fromNode = 1L,
        toNode = 2L,
        lengthM = 100.0,
        highway = "residential",
        name = null,
        surface = null,
        isTraversed = true,
        geometryEncoded = "",
        metadata = metadata,
        slopePercent = slopePercent,
        curvature = curvature,
        stopPenalty = stopPenalty,
        stopPenaltySource = stopPenaltySource,
        predictedFlowScore = predictedFlowScore,
        flowConfidence = flowConfidence,
        hazardScore = hazardScore
    )

    @Test
    fun `parses avg_stop_count from metadata json`() {
        val edge = entity(metadata = """{"avg_stop_count": 1.5}""").toDomain()
        assertEquals(1.5, edge.avgStopCount)
    }

    @Test
    fun `avg_stop_count is null when absent from metadata json`() {
        val edge = entity(metadata = """{"speed_median": 21.0}""").toDomain()
        assertNull(edge.avgStopCount)
    }

    @Test
    fun `avg_stop_count is null when metadata is null`() {
        val edge = entity().toDomain()
        assertNull(edge.avgStopCount)
    }

    @Test
    fun `parses pedal_flow_count and gravity_flow_count from metadata json`() {
        val edge = entity(metadata = """{"pedal_flow_count": 3, "gravity_flow_count": 2}""").toDomain()
        assertEquals(3, edge.pedalFlowCount)
        assertEquals(2, edge.gravityFlowCount)
    }

    @Test
    fun `pedal_flow_count and gravity_flow_count are null when absent from metadata json`() {
        val edge = entity(metadata = """{"speed_median": 21.0}""").toDomain()
        assertNull(edge.pedalFlowCount)
        assertNull(edge.gravityFlowCount)
    }

    @Test
    fun `pedal_flow_count and gravity_flow_count are null when metadata is null`() {
        val edge = entity().toDomain()
        assertNull(edge.pedalFlowCount)
        assertNull(edge.gravityFlowCount)
    }

    @Test
    fun `slope_percent reads from column not metadata json`() {
        val edge = entity(
            metadata = """{"slope_percent": 5.0}""",
            slopePercent = 3.2
        ).toDomain()
        assertEquals(3.2, edge.slopePercent)
    }

    @Test
    fun `slope_percent is null when column is null`() {
        val edge = entity().toDomain()
        assertNull(edge.slopePercent)
    }

    @Test
    fun `maps new v4 columns to domain`() {
        val edge = entity(
            curvature = 12.5,
            stopPenalty = 4.2,
            stopPenaltySource = "measured",
            predictedFlowScore = 0.85,
            flowConfidence = 0.72,
            hazardScore = 0.45
        ).toDomain()
        assertEquals(12.5, edge.curvature)
        assertEquals(4.2, edge.stopPenalty)
        assertEquals("measured", edge.stopPenaltySource)
        assertEquals(0.85, edge.predictedFlowScore)
        assertEquals(0.72, edge.flowConfidence)
        assertEquals(0.45, edge.hazardScore)
    }

    @Test
    fun `new v4 columns are null when absent`() {
        val edge = entity().toDomain()
        assertNull(edge.curvature)
        assertNull(edge.stopPenalty)
        assertNull(edge.stopPenaltySource)
        assertNull(edge.predictedFlowScore)
        assertNull(edge.flowConfidence)
        assertNull(edge.hazardScore)
    }
}
