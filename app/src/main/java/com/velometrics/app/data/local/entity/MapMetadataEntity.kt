package com.velometrics.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.velometrics.app.domain.model.GraphMetadata

@Entity(tableName = "metadata")
data class MapMetadataEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "bbox_south") val bboxSouth: Double,
    @ColumnInfo(name = "bbox_west") val bboxWest: Double,
    @ColumnInfo(name = "bbox_north") val bboxNorth: Double,
    @ColumnInfo(name = "bbox_east") val bboxEast: Double,
    @ColumnInfo(name = "node_count") val nodeCount: Int,
    @ColumnInfo(name = "edge_count") val edgeCount: Int,
    @ColumnInfo(name = "traversed_edge_count") val traversedEdgeCount: Int,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "coverage_geojson") val coverageGeojson: String?
)

fun MapMetadataEntity.toDomain(): GraphMetadata = GraphMetadata(
    createdAt = createdAt,
    bboxSouth = bboxSouth,
    bboxWest = bboxWest,
    bboxNorth = bboxNorth,
    bboxEast = bboxEast,
    nodeCount = nodeCount,
    edgeCount = edgeCount,
    traversedEdgeCount = traversedEdgeCount,
    trackCount = trackCount,
    coverageGeojson = coverageGeojson
)
