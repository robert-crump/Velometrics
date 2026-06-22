package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge

data class HazardConfig(
    val hazardScoreThreshold: Double = 0.7,
    val raceToStopThreshold: Double = 3.0,
)

object HazardFilter {

    private val DEFAULT_CONFIG = HazardConfig()

    // Extension point for Ride-Graph #37 (maxspeed >= 70 km/h rule):
    // When #37 ships a maxspeed column on MapEdge, add a check here that rejects
    // edges with maxspeed >= 70. The highway-tag prior below is the interim proxy
    // for that rule; swap it for the measured value once available.

    private val FAST_ROAD_HIGHWAY_TAGS = setOf(
        "motorway", "motorway_link",
        "trunk", "trunk_link",
    )

    fun shouldKeep(
        edge: MapEdge,
        config: HazardConfig = DEFAULT_CONFIG,
    ): Boolean {
        val hazard = edge.hazardScore
        if (hazard != null && hazard >= config.hazardScoreThreshold) return false

        if (hazard == null && edge.highway in FAST_ROAD_HIGHWAY_TAGS) return false

        val stop = edge.stopPenalty
        if (stop != null && stop >= config.raceToStopThreshold) return false

        return true
    }
}
