package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import org.junit.Assert.*
import org.junit.Test

class RewardComposerTest {

    // --- Edge reward ordering ---

    @Test
    fun `high-flow edge outranks low-flow edge`() {
        val highFlow = edge(pedalFlowCount = 10, gravityFlowCount = 5)
        val lowFlow = edge(pedalFlowCount = 2, gravityFlowCount = 1)

        val highReward = RewardComposer.composeEdgeReward(highFlow)
        val lowReward = RewardComposer.composeEdgeReward(lowFlow)

        assertTrue(highReward.total > lowReward.total)
    }

    @Test
    fun `pedal flow and gravity flow contribute equally by default`() {
        val pedalOnly = edge(pedalFlowCount = 5, gravityFlowCount = 0)
        val gravityOnly = edge(pedalFlowCount = 0, gravityFlowCount = 5)

        val pedalReward = RewardComposer.composeEdgeReward(pedalOnly)
        val gravityReward = RewardComposer.composeEdgeReward(gravityOnly)

        assertEquals(pedalReward.total, gravityReward.total, 1e-9)
    }

    @Test
    fun `untraversed edge with confident prediction outranks untraversed edge with no prediction`() {
        val predicted = edge(
            isTraversed = false,
            predictedPedalFlowProbability = 0.7,
            flowConfidence = 0.8,
        )
        val noPrediction = edge(
            isTraversed = false,
            predictedPedalFlowProbability = null,
            flowConfidence = null,
        )

        val predictedReward = RewardComposer.composeEdgeReward(predicted)
        val noPredictionReward = RewardComposer.composeEdgeReward(noPrediction)

        assertTrue(predictedReward.total > noPredictionReward.total)
    }

    @Test
    fun `prediction below confidence floor is excluded from explore term`() {
        val lowConfidence = edge(
            isTraversed = false,
            predictedPedalFlowProbability = 0.9,
            flowConfidence = 0.1,
        )

        val reward = RewardComposer.composeEdgeReward(
            lowConfidence,
            context = RewardContext(confidenceFloor = 0.2),
        )

        assertEquals(0.0, reward.explore, 1e-9)
    }

    @Test
    fun `traversed edge gets zero explore regardless of prediction`() {
        val traversed = edge(
            isTraversed = true,
            pedalFlowCount = 3,
            predictedPedalFlowProbability = 0.9,
            flowConfidence = 0.9,
        )

        val reward = RewardComposer.composeEdgeReward(traversed)

        assertEquals(0.0, reward.explore, 1e-9)
    }

    @Test
    fun `null flow counts treated as zero`() {
        val nullCounts = edge(pedalFlowCount = null, gravityFlowCount = null)

        val reward = RewardComposer.composeEdgeReward(nullCounts)

        assertEquals(0.0, reward.flow, 1e-9)
    }

    @Test
    fun `total is additive sum of weighted components`() {
        val edge = edge(
            pedalFlowCount = 4,
            gravityFlowCount = 2,
            isTraversed = false,
            predictedPedalFlowProbability = 0.6,
            flowConfidence = 0.5,
        )
        val weights = RewardWeights(flow = 2.0, explore = 1.0)
        val context = RewardContext(exploreExploitBalance = 0.5, confidenceFloor = 0.1)

        val reward = RewardComposer.composeEdgeReward(edge, weights, context)

        assertEquals(reward.flow + reward.explore, reward.total, 1e-9)
    }

    // --- Helpers ---

    private fun edge(
        pedalFlowCount: Int? = null,
        gravityFlowCount: Int? = null,
        isTraversed: Boolean = true,
        predictedPedalFlowProbability: Double? = null,
        predictedGravityFlowProbability: Double? = null,
        flowConfidence: Double? = null,
    ) = MapEdge(
        fromNode = 1L,
        toNode = 2L,
        lengthM = 100.0,
        highway = "residential",
        name = null,
        isTraversed = isTraversed,
        geometryEncoded = "",
        speedMedian = null,
        speedMean = null,
        speedCount = null,
        speedP25 = null,
        speedP75 = null,
        speedP90 = null,
        powerMedian = null,
        powerMean = null,
        powerCount = null,
        powerP25 = null,
        powerP75 = null,
        powerP90 = null,
        slopePercent = null,
        traversalCount = null,
        lastTraversal = null,
        timeOfDayDist = null,
        pedalFlowCount = pedalFlowCount,
        gravityFlowCount = gravityFlowCount,
        predictedPedalFlowProbability = predictedPedalFlowProbability,
        predictedGravityFlowProbability = predictedGravityFlowProbability,
        flowConfidence = flowConfidence,
    )
}
