package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge

data class RewardWeights(
    val flow: Double = 1.0,
    val explore: Double = 1.0,
)

data class RewardContext(
    val exploreExploitBalance: Double = 0.3,
    val confidenceFloor: Double = 0.2,
)

data class ComposedReward(
    val flow: Double,
    val explore: Double,
    val total: Double,
)

object RewardComposer {

    private val DEFAULT_WEIGHTS = RewardWeights()
    private val DEFAULT_CONTEXT = RewardContext()

    // Deferred future terms (extension points — each is just another + w·term):
    //   vista:   DEM prominence, pure geometry — rewards high-vista edges
    //   surface: penalty for poor surface quality (column shipped in DB, not yet mapped to MapEdge)
    //   quiet:   traffic exposure penalty
    //   wind:    ± request-time tailwind on effortful legs; account for wind shelter vs open-field exposure
    //
    // Rejected terms (considered and discarded):
    //   scenery:  too subjective, no measurable signal
    //   crowding: time-of-day dependent, no reliable data source in v1
    //   training-match: out of scope — this is ride-quality, not training-plan optimization

    fun composeEdgeReward(
        edge: MapEdge,
        weights: RewardWeights = DEFAULT_WEIGHTS,
        context: RewardContext = DEFAULT_CONTEXT,
    ): ComposedReward {
        val flowTerm = ((edge.pedalFlowCount ?: 0) + (edge.gravityFlowCount ?: 0)).toDouble()

        val exploreTerm = computeExploreTerm(
            novelty = if (edge.isTraversed) 0.0 else 1.0,
            predictedFlowScore = (edge.predictedGravityFlowProbability ?: 0.0) + (edge.predictedPedalFlowProbability ?: 0.0),
            flowConfidence = edge.flowConfidence,
            confidenceFloor = context.confidenceFloor,
            balance = context.exploreExploitBalance,
        )

        val total = weights.flow * flowTerm +
            weights.explore * exploreTerm

        return ComposedReward(
            flow = weights.flow * flowTerm,
            explore = weights.explore * exploreTerm,
            total = total,
        )
    }

    private fun computeExploreTerm(
        novelty: Double,
        predictedFlowScore: Double?,
        flowConfidence: Double?,
        confidenceFloor: Double,
        balance: Double,
    ): Double {
        if (novelty == 0.0) return 0.0
        val confidence = flowConfidence ?: 0.0
        if (confidence < confidenceFloor) return 0.0
        val predicted = predictedFlowScore ?: 0.0
        return balance * novelty * predicted * confidence
    }
}
