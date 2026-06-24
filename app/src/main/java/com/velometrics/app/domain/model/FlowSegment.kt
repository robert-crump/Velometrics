package com.velometrics.app.domain.model

data class FlowSegment(
    val geometryEncoded: String,
    val pedalFlowCount: Int,
    val gravityFlowCount: Int,
)
