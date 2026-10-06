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

    val powerMedian: Double?,
    val powerMean: Double?,
    val powerCount: Int?,

    val slopePercent: Double?,
    val traversalCount: Int?,
    val lastTraversal: String?,
    val avgStopCount: Double? = null,
    val pedalFlowCount: Int? = null,
    val gravityFlowCount: Int? = null,
    val flowConfidence: Double? = null,
    val predictedGravityFlowProbability: Double? = null,
    val predictedPedalFlowProbability: Double? = null,
    val coolScore: Double? = null,
    val coolConfidence: Double? = null,
    val whPerM: Double? = null,
    val whPerMSource: String? = null,
    val whPerMConfidence: Double? = null,
)
