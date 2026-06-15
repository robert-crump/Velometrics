package com.velometrics.app.data.local.entity

import org.junit.Assert.*
import org.junit.Test

class MapEdgeEntityTest {

    private fun entity(metadata: String?) = MapEdgeEntity(
        fromNode = 1L,
        toNode = 2L,
        lengthM = 100.0,
        highway = "residential",
        name = null,
        surface = null,
        isTraversed = true,
        geometryEncoded = "",
        metadata = metadata
    )

    @Test
    fun `parses avg_stop_count from metadata json`() {
        val edge = entity("""{"avg_stop_count": 1.5}""").toDomain()
        assertEquals(1.5, edge.avgStopCount)
    }

    @Test
    fun `avg_stop_count is null when absent from metadata json`() {
        val edge = entity("""{"speed_median": 21.0}""").toDomain()
        assertNull(edge.avgStopCount)
    }

    @Test
    fun `avg_stop_count is null when metadata is null`() {
        val edge = entity(null).toDomain()
        assertNull(edge.avgStopCount)
    }

    @Test
    fun `parses pedal_flow_count and gravity_flow_count from metadata json`() {
        val edge = entity("""{"pedal_flow_count": 3, "gravity_flow_count": 2}""").toDomain()
        assertEquals(3, edge.pedalFlowCount)
        assertEquals(2, edge.gravityFlowCount)
    }

    @Test
    fun `pedal_flow_count and gravity_flow_count are null when absent from metadata json`() {
        val edge = entity("""{"speed_median": 21.0}""").toDomain()
        assertNull(edge.pedalFlowCount)
        assertNull(edge.gravityFlowCount)
    }

    @Test
    fun `pedal_flow_count and gravity_flow_count are null when metadata is null`() {
        val edge = entity(null).toDomain()
        assertNull(edge.pedalFlowCount)
        assertNull(edge.gravityFlowCount)
    }
}
