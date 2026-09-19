package com.velometrics.app.domain.model

data class PowerCurvePoint(
    val durationSec: Int,
    val label: String,
    val watts: Int?,
    val sessionId: Long?,
    val date: String?
)
