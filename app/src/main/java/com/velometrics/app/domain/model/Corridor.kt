package com.velometrics.app.domain.model

data class Corridor(
    val id: Long,
    val entryNode: Long,
    val exitNode: Long,
    val lengthM: Double,
    val pedalReward: Double,
    val gravityReward: Double,
    val exitHazardScore: Double,
    val centroidLat: Double,
    val centroidLon: Double,
    val edgeList: List<Pair<Long, Long>>,
    val popularity: Int,
    val groupId: Long,
)
