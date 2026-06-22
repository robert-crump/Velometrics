package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Corridor
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
    fun `stop penalty reduces reward`() {
        val noStop = edge(pedalFlowCount = 5, stopPenalty = 0.0)
        val heavyStop = edge(pedalFlowCount = 5, stopPenalty = 3.0)

        val noStopReward = RewardComposer.composeEdgeReward(noStop)
        val heavyStopReward = RewardComposer.composeEdgeReward(heavyStop)

        assertTrue(noStopReward.total > heavyStopReward.total)
    }

    @Test
    fun `untraversed edge with confident prediction outranks untraversed edge with no prediction`() {
        val predicted = edge(
            isTraversed = false,
            predictedFlowScore = 5.0,
            flowConfidence = 0.8,
        )
        val noPrediction = edge(
            isTraversed = false,
            predictedFlowScore = null,
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
            predictedFlowScore = 10.0,
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
            predictedFlowScore = 10.0,
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
    fun `null stop penalty treated as zero`() {
        val nullStop = edge(stopPenalty = null)

        val reward = RewardComposer.composeEdgeReward(nullStop)

        assertEquals(0.0, reward.stop, 1e-9)
    }

    @Test
    fun `total is additive sum of weighted components`() {
        val edge = edge(
            pedalFlowCount = 4,
            gravityFlowCount = 2,
            stopPenalty = 1.5,
            isTraversed = false,
            predictedFlowScore = 3.0,
            flowConfidence = 0.5,
        )
        val weights = RewardWeights(flow = 2.0, stop = 1.0, explore = 1.0)
        val context = RewardContext(exploreExploitBalance = 0.5, confidenceFloor = 0.1)

        val reward = RewardComposer.composeEdgeReward(edge, weights, context)

        assertEquals(reward.flow + reward.stop + reward.explore, reward.total, 1e-9)
    }

    // --- Corridor reward ordering ---

    @Test
    fun `high-reward corridor outranks low-reward corridor`() {
        val high = corridor(pedalReward = 10.0, gravityReward = 5.0)
        val low = corridor(pedalReward = 2.0, gravityReward = 1.0)

        val highReward = RewardComposer.composeCorridorReward(high)
        val lowReward = RewardComposer.composeCorridorReward(low)

        assertTrue(highReward.total > lowReward.total)
    }

    @Test
    fun `predicted corridor gets explore term, measured does not`() {
        val predicted = corridor(
            type = "predicted",
            pedalReward = 0.0,
            gravityReward = 0.0,
            predictedReward = 5.0,
        )
        val measured = corridor(
            type = "measured",
            pedalReward = 0.0,
            gravityReward = 0.0,
            predictedReward = 5.0,
        )

        val predictedReward = RewardComposer.composeCorridorReward(predicted)
        val measuredReward = RewardComposer.composeCorridorReward(measured)

        assertTrue(predictedReward.explore > 0.0)
        assertEquals(0.0, measuredReward.explore, 1e-9)
    }

    @Test
    fun `corridor pedal and gravity reward contribute equally`() {
        val pedalOnly = corridor(pedalReward = 8.0, gravityReward = 0.0)
        val gravityOnly = corridor(pedalReward = 0.0, gravityReward = 8.0)

        val pedalResult = RewardComposer.composeCorridorReward(pedalOnly)
        val gravityResult = RewardComposer.composeCorridorReward(gravityOnly)

        assertEquals(pedalResult.total, gravityResult.total, 1e-9)
    }

    // --- Helpers ---

    private fun edge(
        pedalFlowCount: Int? = null,
        gravityFlowCount: Int? = null,
        stopPenalty: Double? = null,
        isTraversed: Boolean = true,
        predictedFlowScore: Double? = null,
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
        stopPenalty = stopPenalty,
        predictedFlowScore = predictedFlowScore,
        flowConfidence = flowConfidence,
    )

    private fun corridor(
        pedalReward: Double = 0.0,
        gravityReward: Double = 0.0,
        predictedReward: Double = 0.0,
        type: String = "measured",
    ) = Corridor(
        id = 1L,
        entryNode = 10L,
        exitNode = 20L,
        lengthM = 1000.0,
        pedalReward = pedalReward,
        gravityReward = gravityReward,
        predictedReward = predictedReward,
        exitHazardScore = 0.0,
        type = type,
        centroidLat = 50.78,
        centroidLon = 6.08,
    )
}
