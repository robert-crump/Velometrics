package com.cyclegraph.app.domain.model

data class GraphMetadata(
    val createdAt: String,
    val bbox: List<Double>,
    val nodeCount: Int,
    val edgeCount: Int,
    val traversedEdgeCount: Int,
    val trackCount: Int
)
