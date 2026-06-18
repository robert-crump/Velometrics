package com.velometrics.app.domain.model

data class CorridorConnector(
    val fromCorridor: Long,
    val toCorridor: Long,
    val distanceM: Double,
)
