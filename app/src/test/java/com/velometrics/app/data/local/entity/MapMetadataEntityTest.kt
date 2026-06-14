package com.velometrics.app.data.local.entity

import org.junit.Assert.*
import org.junit.Test

class MapMetadataEntityTest {

    @Test
    fun `maps bbox fields and coverage geojson to domain model`() {
        val entity = MapMetadataEntity(
            id = 1,
            createdAt = "2026-06-14T11:25:48.575907+00:00",
            bboxSouth = 50.555351,
            bboxWest = 5.624501,
            bboxNorth = 50.979869,
            bboxEast = 6.467195,
            nodeCount = 210276,
            edgeCount = 511597,
            traversedEdgeCount = 14637,
            trackCount = 0,
            coverageGeojson = """{"type":"MultiPolygon","coordinates":[]}"""
        )

        val metadata = entity.toDomain()

        assertEquals("2026-06-14T11:25:48.575907+00:00", metadata.createdAt)
        assertEquals(50.555351, metadata.bboxSouth, 1e-9)
        assertEquals(5.624501, metadata.bboxWest, 1e-9)
        assertEquals(50.979869, metadata.bboxNorth, 1e-9)
        assertEquals(6.467195, metadata.bboxEast, 1e-9)
        assertEquals(210276, metadata.nodeCount)
        assertEquals(511597, metadata.edgeCount)
        assertEquals(14637, metadata.traversedEdgeCount)
        assertEquals(0, metadata.trackCount)
        assertEquals("""{"type":"MultiPolygon","coordinates":[]}""", metadata.coverageGeojson)
    }

    @Test
    fun `coverage geojson is null when absent`() {
        val entity = MapMetadataEntity(
            id = 1,
            createdAt = "2026-06-14T11:25:48.575907+00:00",
            bboxSouth = 0.0,
            bboxWest = 0.0,
            bboxNorth = 0.0,
            bboxEast = 0.0,
            nodeCount = 0,
            edgeCount = 0,
            traversedEdgeCount = 0,
            trackCount = 0,
            coverageGeojson = null
        )

        assertNull(entity.toDomain().coverageGeojson)
    }
}
