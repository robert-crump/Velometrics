package com.velometrics.app.data.local.entity

import org.junit.Assert.*
import org.junit.Test

class MapEdgeEntityTest {

    private fun entity(
        metadata: String? = null,
        slopePercent: Double? = null,
        flowConfidence: Double? = null,
        predictedGravityFlowProbability: Double? = null,
        predictedPedalFlowProbability: Double? = null,
        coolScore: Double? = null,
        coolConfidence: Double? = null,
        whPerM: Double? = null,
        whPerMSource: String? = null,
        whPerMConfidence: Double? = null,
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
        flowConfidence = flowConfidence,
        predictedGravityFlowProbability = predictedGravityFlowProbability,
        predictedPedalFlowProbability = predictedPedalFlowProbability,
        coolScore = coolScore,
        coolConfidence = coolConfidence,
        whPerM = whPerM,
        whPerMSource = whPerMSource,
        whPerMConfidence = whPerMConfidence,
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
    fun `maps v6 columns to domain`() {
        val edge = entity(
            flowConfidence = 0.72,
            predictedGravityFlowProbability = 0.85,
            predictedPedalFlowProbability = 0.6,
            coolScore = 0.4,
            coolConfidence = 0.9,
            whPerM = 8.5,
            whPerMSource = "measured",
            whPerMConfidence = 0.65,
        ).toDomain()
        assertEquals(0.72, edge.flowConfidence)
        assertEquals(0.85, edge.predictedGravityFlowProbability)
        assertEquals(0.6, edge.predictedPedalFlowProbability)
        assertEquals(0.4, edge.coolScore)
        assertEquals(0.9, edge.coolConfidence)
        assertEquals(8.5, edge.whPerM)
        assertEquals("measured", edge.whPerMSource)
        assertEquals(0.65, edge.whPerMConfidence)
    }

    @Test
    fun `v6 columns are null when absent`() {
        val edge = entity().toDomain()
        assertNull(edge.flowConfidence)
        assertNull(edge.predictedGravityFlowProbability)
        assertNull(edge.predictedPedalFlowProbability)
        assertNull(edge.coolScore)
        assertNull(edge.coolConfidence)
        assertNull(edge.whPerM)
        assertNull(edge.whPerMSource)
        assertNull(edge.whPerMConfidence)
    }
}
