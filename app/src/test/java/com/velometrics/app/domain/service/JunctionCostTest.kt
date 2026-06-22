package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import org.junit.Assert.*
import org.junit.Test

class JunctionCostTest {

    // --- Graduated turn-angle penalty ---

    @Test
    fun `straight continuation has zero cost`() {
        val cost = JunctionCost.computeTurnCost(90.0, 90.0, riddenEdge())
        assertEquals(0.0, cost, 0.001)
    }

    @Test
    fun `gentle turn costs less than sharp turn`() {
        val gentle = JunctionCost.computeTurnCost(90.0, 110.0, riddenEdge())
        val moderate = JunctionCost.computeTurnCost(90.0, 180.0, riddenEdge())
        val sharp = JunctionCost.computeTurnCost(90.0, 240.0, riddenEdge())

        assertTrue("gentle ($gentle) < moderate ($moderate)", gentle < moderate)
        assertTrue("moderate ($moderate) < sharp ($sharp)", moderate < sharp)
    }

    @Test
    fun `U-turn has maximum angle penalty`() {
        val cost = JunctionCost.computeTurnCost(90.0, 270.0, riddenEdge())
        assertEquals(1.0, cost, 0.001)
    }

    @Test
    fun `penalty increases monotonically with turn angle`() {
        val costs = (0..180 step 10).map { angle ->
            val exitBearing = (90.0 + angle) % 360
            JunctionCost.computeTurnCost(90.0, exitBearing, riddenEdge(avgStopCount = 0.0))
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
        val cost350to10 = JunctionCost.computeTurnCost(350.0, 10.0, riddenEdge(avgStopCount = 0.0))
        val cost90to110 = JunctionCost.computeTurnCost(90.0, 110.0, riddenEdge(avgStopCount = 0.0))
        assertEquals("20 deg turn via wrap equals 20 deg turn without", cost90to110, cost350to10, 0.001)
    }

    // --- Left-turn surcharge ---

    @Test
    fun `left turn costs more than equivalent right turn on unridden junction`() {
        val rightCost = JunctionCost.computeTurnCost(90.0, 180.0, unriddenEdge())
        val leftCost = JunctionCost.computeTurnCost(90.0, 0.0, unriddenEdge())

        assertEquals("both are 90 deg turns", 90.0 / 180.0, rightCost, 0.001)
        assertTrue("left ($leftCost) > right ($rightCost)", leftCost > rightCost)
    }

    @Test
    fun `left turn surcharge scales with surcharge weight`() {
        val low = JunctionCost.computeTurnCost(
            90.0, 0.0, unriddenEdge(), JunctionCostConfig(leftTurnSurchargeWeight = 0.2),
        )
        val high = JunctionCost.computeTurnCost(
            90.0, 0.0, unriddenEdge(), JunctionCostConfig(leftTurnSurchargeWeight = 0.8),
        )
        assertTrue("higher weight ($high) > lower weight ($low)", high > low)
    }

    // --- Stop-modulated left-turn waiver ---

    @Test
    fun `left turn surcharge waived when approach edge has zero stops`() {
        val rightCost = JunctionCost.computeTurnCost(90.0, 180.0, riddenEdge(avgStopCount = 0.0))
        val leftCost = JunctionCost.computeTurnCost(90.0, 0.0, riddenEdge(avgStopCount = 0.0))

        assertEquals("left equals right when zero stops", rightCost, leftCost, 0.001)
    }

    @Test
    fun `left turn surcharge reduced when approach edge has low stops`() {
        val fullSurcharge = JunctionCost.computeTurnCost(90.0, 0.0, unriddenEdge())
        val reduced = JunctionCost.computeTurnCost(90.0, 0.0, riddenEdge(avgStopCount = 0.2))
        val rightCost = JunctionCost.computeTurnCost(90.0, 180.0, riddenEdge())

        assertTrue("reduced ($reduced) < full ($fullSurcharge)", reduced < fullSurcharge)
        assertTrue("reduced ($reduced) > right ($rightCost)", reduced > rightCost)
    }

    @Test
    fun `left turn surcharge full when approach edge has high stops`() {
        val highStopCost = JunctionCost.computeTurnCost(90.0, 0.0, riddenEdge(avgStopCount = 2.0))
        val unriddenCost = JunctionCost.computeTurnCost(90.0, 0.0, unriddenEdge())

        assertEquals("high stops equals unridden penalty", unriddenCost, highStopCost, 0.001)
    }

    @Test
    fun `custom lowStopThreshold changes waiver boundary`() {
        val config = JunctionCostConfig(lowStopThreshold = 1.0)
        val halfWay = JunctionCost.computeTurnCost(90.0, 0.0, riddenEdge(avgStopCount = 0.5), config)
        val rightCost = JunctionCost.computeTurnCost(90.0, 180.0, riddenEdge(), config)
        val fullCost = JunctionCost.computeTurnCost(90.0, 0.0, unriddenEdge(), config)

        assertTrue("half-way ($halfWay) > right ($rightCost)", halfWay > rightCost)
        assertTrue("half-way ($halfWay) < full ($fullCost)", halfWay < fullCost)
    }

    // --- Un-ridden junctions ---

    @Test
    fun `un-ridden junction receives full geometric penalty`() {
        val unriddenCost = JunctionCost.computeTurnCost(90.0, 0.0, unriddenEdge())
        val riddenHighStop = JunctionCost.computeTurnCost(90.0, 0.0, riddenEdge(avgStopCount = 2.0))

        assertEquals("unridden equals ridden-high-stop", riddenHighStop, unriddenCost, 0.001)
    }

    @Test
    fun `traversed edge with null avgStopCount gets full penalty`() {
        val nullStopCost = JunctionCost.computeTurnCost(90.0, 0.0, riddenEdge(avgStopCount = null))
        val unriddenCost = JunctionCost.computeTurnCost(90.0, 0.0, unriddenEdge())

        assertEquals("null avgStopCount equals unridden penalty", unriddenCost, nullStopCost, 0.001)
    }

    // --- Right turns are unaffected by stop data ---

    @Test
    fun `right turn cost is the same regardless of stop data`() {
        val unridden = JunctionCost.computeTurnCost(90.0, 180.0, unriddenEdge())
        val zeroStops = JunctionCost.computeTurnCost(90.0, 180.0, riddenEdge(avgStopCount = 0.0))
        val highStops = JunctionCost.computeTurnCost(90.0, 180.0, riddenEdge(avgStopCount = 5.0))

        assertEquals(unridden, zeroStops, 0.001)
        assertEquals(unridden, highStops, 0.001)
    }

    // --- Helpers ---

    private fun riddenEdge(avgStopCount: Double? = null) = edge(
        isTraversed = true,
        avgStopCount = avgStopCount,
    )

    private fun unriddenEdge() = edge(
        isTraversed = false,
        avgStopCount = null,
    )

    private fun edge(
        isTraversed: Boolean = true,
        avgStopCount: Double? = null,
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
        avgStopCount = avgStopCount,
    )
}
