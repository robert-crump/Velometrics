package com.velometrics.app.domain.model

data class GpxPoiItem(
    val poiWD: PoiWithDistances,
    val distanceM: Double,
    val isAhead: Boolean
)
