package com.cyclegraph.app.domain.model

import java.time.Instant

data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val timestamp: Instant,
)
