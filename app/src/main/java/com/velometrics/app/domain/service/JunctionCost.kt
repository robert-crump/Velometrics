package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapTurn
import com.velometrics.app.util.GeoUtils

data class JunctionCostConfig(
    val angleWeightM: Double = 20.0,
    val hazardWeightM: Double = 60.0,
    val stopWeightM: Double = 15.0,
    val brakingWeightM: Double = 30.0,
    val keDeltaWeightM: Double = 0.5,
    val unmeasuredTurnPenaltyM: Double = 10.0,
    val negligibleAngleDeg: Double = 5.0,
)

object JunctionCost {

    private val DEFAULT_CONFIG = JunctionCostConfig()

    // Composes a meters-equivalent penalty so it can be added directly onto A* g-cost
    // alongside edge lengths. `turn` is the real per-(fromNode, junctionNode, toNode) row
    // from map_turns, looked up by the caller; null means no measured data for this junction.
    fun computeTurnCost(
        approachBearing: Double,
        exitBearing: Double,
        turn: MapTurn?,
        config: JunctionCostConfig = DEFAULT_CONFIG,
    ): Double {
        val turnAngle = GeoUtils.bearingDifference(approachBearing, exitBearing)
        val angleCost = (turnAngle / 180.0) * config.angleWeightM

        if (turn != null) {
            val hazardCost = turn.hazardScore.coerceIn(0.0, 1.0) * config.hazardWeightM
            val stopCost = turn.stopPenalty.coerceAtLeast(0.0) * config.stopWeightM
            val brakingCost = (turn.brakingProbability ?: 0.0).coerceIn(0.0, 1.0) * config.brakingWeightM
            val keDeltaCost = (turn.medianKeDelta ?: 0.0).coerceAtLeast(0.0) * config.keDeltaWeightM
            return angleCost + hazardCost + stopCost + brakingCost + keDeltaCost
        }

        // No measured row: most graph nodes are plain geometry pass-through points on a single
        // road, not real junctions, and were never written to map_turns. Only charge the
        // unmeasured-risk surcharge when the bearing actually changes enough to be a real turn.
        if (turnAngle < config.negligibleAngleDeg) return 0.0
        return angleCost + config.unmeasuredTurnPenaltyM
    }
}
