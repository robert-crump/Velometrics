package com.velometrics.app.domain.model

data class MapEdge(
    val fromNode: Long,
    val toNode: Long,
    val lengthM: Double,
    val highway: String,
    val name: String?,
    val isTraversed: Boolean,
    val geometryEncoded: String,

    // Traversal statistics (only present when isTraversed == true)
    val speedMedian: Double?,
    val speedMean: Double?,
    val speedCount: Int?,
    val speedP25: Double?,
    val speedP75: Double?,
    val speedP90: Double?,

    val powerMedian: Double?,
    val powerMean: Double?,
    val powerCount: Int?,
    val powerP25: Double?,
    val powerP75: Double?,
    val powerP90: Double?,

    val slopePercent: Double?,
    val traversalCount: Int?,
    val lastTraversal: String?,
    val timeOfDayDist: List<Int>?,
)
