package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapTurn
import org.junit.Assert.*
import org.junit.Test

class JunctionCostTest {

    // --- Graduated turn-angle penalty (no measured data) ---

    @Test
    fun `straight continuation with no turn data has zero cost`() {
        val cost = JunctionCost.computeTurnCost(90.0, 90.0, turn = null)
        assertEquals(0.0, cost, 0.001)
    }

    @Test
    fun `negligible angle change with no turn data has zero cost`() {
        val cost = JunctionCost.computeTurnCost(90.0, 93.0, turn = null)
        assertEquals(0.0, cost, 0.001)
    }

    @Test
    fun `gentle turn costs less than sharp turn when unmeasured`() {
        val gentle = JunctionCost.computeTurnCost(90.0, 110.0, turn = null)
        val moderate = JunctionCost.computeTurnCost(90.0, 180.0, turn = null)
        val sharp = JunctionCost.computeTurnCost(90.0, 240.0, turn = null)

        assertTrue("gentle ($gentle) < moderate ($moderate)", gentle < moderate)
        assertTrue("moderate ($moderate) < sharp ($sharp)", moderate < sharp)
    }

    @Test
    fun `penalty increases monotonically with turn angle when unmeasured`() {
        val costs = (0..180 step 10).map { angle ->
            val exitBearing = (90.0 + angle) % 360
            JunctionCost.computeTurnCost(90.0, exitBearing, turn = null)
        }
        for (i in 1 until costs.size) {
            assertTrue(
                "cost at ${i * 10} deg (${costs[i]}) >= cost at ${(i - 1) * 10} deg (${costs[i - 1]})",
                costs[i] >= costs[i - 1],
            )
        }
    }

    @Test
    fun `bearing wrap-around produces correct angle`() {
        val cost350to10 = JunctionCost.computeTurnCost(350.0, 10.0, turn = null)
        val cost90to110 = JunctionCost.computeTurnCost(90.0, 110.0, turn = null)
        assertEquals("20 deg turn via wrap equals 20 deg turn without", cost90to110, cost350to10, 0.001)
    }

    @Test
    fun `unmeasured real turn incurs surcharge on top of angle cost`() {
        val config = JunctionCostConfig()
        val cost = JunctionCost.computeTurnCost(90.0, 180.0, turn = null, config = config)
        val angleOnly = (90.0 / 180.0) * config.angleWeightM
        assertEquals(angleOnly + config.unmeasuredTurnPenaltyM, cost, 0.001)
    }

    // --- Measured map_turns data ---

    @Test
    fun `higher hazard score increases cost`() {
        val low = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(hazardScore = 0.0))
        val high = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(hazardScore = 0.9))
        assertTrue("high hazard ($high) > low hazard ($low)", high > low)
    }

    @Test
    fun `higher stop penalty increases cost`() {
        val low = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(stopPenalty = 0.0))
        val high = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(stopPenalty = 2.0))
        assertTrue("high stop penalty ($high) > low ($low)", high > low)
    }

    @Test
    fun `higher braking probability increases cost`() {
        val low = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(brakingProbability = 0.0))
        val high = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(brakingProbability = 0.9))
        assertTrue("high braking ($high) > low ($low)", high > low)
    }

    @Test
    fun `null braking probability treated as zero`() {
        val nullBraking = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(brakingProbability = null))
        val zeroBraking = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(brakingProbability = 0.0))
        assertEquals(zeroBraking, nullBraking, 0.001)
    }

    @Test
    fun `higher median ke delta increases cost`() {
        val low = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(medianKeDelta = 0.0))
        val high = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(medianKeDelta = 20.0))
        assertTrue("high ke delta ($high) > low ($low)", high > low)
    }

    @Test
    fun `null median ke delta treated as zero`() {
        val nullKe = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(medianKeDelta = null))
        val zeroKe = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(medianKeDelta = 0.0))
        assertEquals(zeroKe, nullKe, 0.001)
    }

    @Test
    fun `negative median ke delta is clamped to zero`() {
        val negative = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(medianKeDelta = -5.0))
        val zero = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(medianKeDelta = 0.0))
        assertEquals(zero, negative, 0.001)
    }

    @Test
    fun `measured turn with all-zero fields plus straight bearing has zero cost`() {
        val cost = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn())
        assertEquals(0.0, cost, 0.001)
    }

    @Test
    fun `measured hazard applies even on a straight-through junction`() {
        // A junction can be hazardous (e.g. crossing a busy road) without changing bearing.
        val straightHazardous = JunctionCost.computeTurnCost(90.0, 90.0, turn = turn(hazardScore = 0.8))
        assertTrue(straightHazardous > 0.0)
    }

    @Test
    fun `measured cost is the sum of angle, hazard, stop, braking and ke-delta terms`() {
        val config = JunctionCostConfig()
        val t = turn(hazardScore = 0.5, stopPenalty = 1.0, brakingProbability = 0.4, medianKeDelta = 10.0)
        val cost = JunctionCost.computeTurnCost(90.0, 180.0, turn = t, config = config)

        val expected = (90.0 / 180.0) * config.angleWeightM +
            0.5 * config.hazardWeightM +
            1.0 * config.stopWeightM +
            0.4 * config.brakingWeightM +
            10.0 * config.keDeltaWeightM

        assertEquals(expected, cost, 0.001)
    }

    // --- Helpers ---

    private fun turn(
        hazardScore: Double = 0.0,
        stopPenalty: Double = 0.0,
        brakingProbability: Double? = 0.0,
        medianKeDelta: Double? = 0.0,
    ) = MapTurn(
        fromNode = 1L,
        junctionNode = 2L,
        toNode = 3L,
        hazardScore = hazardScore,
        hazardSource = "measured",
        stopPenalty = stopPenalty,
        stopPenaltySource = "measured",
        brakingProbability = brakingProbability,
        medianKeDelta = medianKeDelta,
        stopPenaltyConfidence = 0.8,
    )
}
