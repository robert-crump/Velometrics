package com.cyclegraph.app.domain.model

data class Poi(
    val poiId: String,
    val name: String,
    val category: String,
    val cuisine: String?,
    val lat: Double,
    val lon: Double,
    val openingHours: String?
)
