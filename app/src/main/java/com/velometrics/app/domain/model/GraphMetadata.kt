package com.velometrics.app.domain.model

data class GraphMetadata(
    val createdAt: String,
    val bboxSouth: Double,
    val bboxWest: Double,
    val bboxNorth: Double,
    val bboxEast: Double,
    val nodeCount: Int,
    val edgeCount: Int,
    val traversedEdgeCount: Int,
    val trackCount: Int,
    val coverageGeojson: String?
)
