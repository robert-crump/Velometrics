package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.util.GeoUtils

data class JunctionCostConfig(
    val leftTurnSurchargeWeight: Double = 0.5,
    val lowStopThreshold: Double = 0.5,
)

object JunctionCost {

    private val DEFAULT_CONFIG = JunctionCostConfig()

    // Accepted caveat: measured stop data is per-edge, not per-(in,out)-pair — a proxy
    // until Ride-Graph #36 lands. When per-turn braking data ships, swap
    // computeStopModulation to read the (in,out) pair stat instead of the edge-level
    // avgStopCount.

    fun computeTurnCost(
        approachBearing: Double,
        exitBearing: Double,
        approachEdge: MapEdge,
        config: JunctionCostConfig = DEFAULT_CONFIG,
    ): Double {
        val turnAngle = GeoUtils.bearingDifference(approachBearing, exitBearing)
        val anglePenalty = turnAngle / 180.0

        val delta = (exitBearing - approachBearing + 360) % 360
        val isLeftTurn = delta > 180

        if (!isLeftTurn) return anglePenalty

        val stopModulation = computeStopModulation(approachEdge, config)
        return anglePenalty * (1.0 + config.leftTurnSurchargeWeight * stopModulation)
    }

    private fun computeStopModulation(
        approachEdge: MapEdge,
        config: JunctionCostConfig,
    ): Double {
        if (!approachEdge.isTraversed) return 1.0
        val avgStops = approachEdge.avgStopCount ?: return 1.0
        return (avgStops / config.lowStopThreshold).coerceIn(0.0, 1.0)
    }
}
