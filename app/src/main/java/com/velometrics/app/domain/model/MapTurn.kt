package com.velometrics.app.domain.model

data class MapTurn(
    val fromNode: Long,
    val junctionNode: Long,
    val toNode: Long,
    val hazardScore: Double,
    val hazardSource: String,
    val stopPenalty: Double,
    val stopPenaltySource: String,
    val brakingProbability: Double?,
    val medianKeDelta: Double?,
    val stopPenaltyConfidence: Double?,
)
