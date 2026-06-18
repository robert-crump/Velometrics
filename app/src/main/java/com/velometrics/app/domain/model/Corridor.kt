package com.velometrics.app.domain.model

data class Corridor(
    val id: Long,
    val entryNode: Long,
    val exitNode: Long,
    val lengthM: Double,
    val pedalReward: Double,
    val gravityReward: Double,
    val predictedReward: Double,
    val exitHazardScore: Double,
    val type: String,
    val centroidLat: Double,
    val centroidLon: Double,
)
