package com.velometrics.app.domain.service

import com.velometrics.app.domain.service.DegradationPolicy.RelaxationTier
import com.velometrics.app.domain.service.DegradationPolicy.EvaluationOutcome
import org.junit.Assert.*
import org.junit.Test

class DegradationPolicyTest {

    // --- Tier ordering ---

    @Test
    fun `tier progression follows widen-band then ease-discovery then ease-penalties`() {
        assertEquals(RelaxationTier.WIDEN_BAND, DegradationPolicy.nextTier(RelaxationTier.NONE))
        assertEquals(RelaxationTier.EASE_DISCOVERY, DegradationPolicy.nextTier(RelaxationTier.WIDEN_BAND))
        assertEquals(RelaxationTier.EASE_PENALTIES, DegradationPolicy.nextTier(RelaxationTier.EASE_DISCOVERY))
        assertNull(DegradationPolicy.nextTier(RelaxationTier.EASE_PENALTIES))
    }

    // --- Tier params ---

    @Test
    fun `NONE tier uses base parameters`() {
        val params = DegradationPolicy.tierParams(RelaxationTier.NONE)

        assertEquals(0.15, params.distanceBandFraction, 1e-9)
        assertEquals(0.3, params.exploreExploitBalance, 1e-9)
        assertEquals(2.0, params.reusePenaltyWeight, 1e-9)
    }

    @Test
    fun `WIDEN_BAND tier only widens distance band`() {
        val params = DegradationPolicy.tierParams(RelaxationTier.WIDEN_BAND)

        assertEquals(0.30, params.distanceBandFraction, 1e-9)
        assertEquals(0.3, params.exploreExploitBalance, 1e-9)
        assertEquals(2.0, params.reusePenaltyWeight, 1e-9)
    }

    @Test
    fun `EASE_DISCOVERY tier widens band and eases explore balance`() {
        val params = DegradationPolicy.tierParams(RelaxationTier.EASE_DISCOVERY)

        assertEquals(0.30, params.distanceBandFraction, 1e-9)
        assertEquals(0.6, params.exploreExploitBalance, 1e-9)
        assertEquals(2.0, params.reusePenaltyWeight, 1e-9)
    }

    @Test
    fun `EASE_PENALTIES tier widens band, eases explore, and reduces reuse penalty`() {
        val params = DegradationPolicy.tierParams(RelaxationTier.EASE_PENALTIES)

        assertEquals(0.30, params.distanceBandFraction, 1e-9)
        assertEquals(0.6, params.exploreExploitBalance, 1e-9)
        assertEquals(0.5, params.reusePenaltyWeight, 1e-9)
    }

    @Test
    fun `each tier is cumulatively more relaxed than the previous`() {
        val tiers = listOf(
            RelaxationTier.NONE,
            RelaxationTier.WIDEN_BAND,
            RelaxationTier.EASE_DISCOVERY,
            RelaxationTier.EASE_PENALTIES,
        )
        val params = tiers.map { DegradationPolicy.tierParams(it) }

        for (i in 1 until params.size) {
            val prev = params[i - 1]
            val curr = params[i]
            assertTrue(
                "Tier ${tiers[i]} band should be >= ${tiers[i - 1]}",
                curr.distanceBandFraction >= prev.distanceBandFraction,
            )
            assertTrue(
                "Tier ${tiers[i]} explore should be >= ${tiers[i - 1]}",
                curr.exploreExploitBalance >= prev.exploreExploitBalance,
            )
            assertTrue(
                "Tier ${tiers[i]} reuse penalty should be <= ${tiers[i - 1]}",
                curr.reusePenaltyWeight <= prev.reusePenaltyWeight,
            )
        }
    }

    @Test
    fun `tierParams respects custom base values`() {
        val params = DegradationPolicy.tierParams(
            tier = RelaxationTier.NONE,
            baseExploreExploitBalance = 0.5,
            baseReusePenaltyWeight = 3.0,
        )

        assertEquals(0.5, params.exploreExploitBalance, 1e-9)
        assertEquals(3.0, params.reusePenaltyWeight, 1e-9)
    }

    @Test
    fun `tierParams respects custom config`() {
        val config = DegradationConfig(
            widenedDistanceBandFraction = 0.40,
            easedExploreExploitBalance = 0.8,
            easedReusePenaltyWeight = 0.2,
        )
        val params = DegradationPolicy.tierParams(RelaxationTier.EASE_PENALTIES, config = config)

        assertEquals(0.40, params.distanceBandFraction, 1e-9)
        assertEquals(0.8, params.exploreExploitBalance, 1e-9)
        assertEquals(0.2, params.reusePenaltyWeight, 1e-9)
    }

    // --- Actual-vs-requested reporting ---

    @Test
    fun `reportCandidates carries actual and requested distance per candidate`() {
        val reports = DegradationPolicy.reportCandidates(
            listOf(48000.0, 52000.0, 50500.0),
            requestedDistanceM = 50000.0,
        )

        assertEquals(3, reports.size)
        assertEquals(48000.0, reports[0].actualDistanceM, 1e-9)
        assertEquals(50000.0, reports[0].requestedDistanceM, 1e-9)
    }

    @Test
    fun `deviation is positive when actual exceeds requested`() {
        val reports = DegradationPolicy.reportCandidates(
            listOf(55000.0),
            requestedDistanceM = 50000.0,
        )

        assertEquals(10.0, reports[0].distanceDeviationPercent, 1e-9)
    }

    @Test
    fun `deviation is negative when actual is below requested`() {
        val reports = DegradationPolicy.reportCandidates(
            listOf(45000.0),
            requestedDistanceM = 50000.0,
        )

        assertEquals(-10.0, reports[0].distanceDeviationPercent, 1e-9)
    }

    @Test
    fun `deviation is zero for exact match`() {
        val reports = DegradationPolicy.reportCandidates(
            listOf(50000.0),
            requestedDistanceM = 50000.0,
        )

        assertEquals(0.0, reports[0].distanceDeviationPercent, 1e-9)
    }

    @Test
    fun `deviation is zero when requested distance is zero`() {
        val reports = DegradationPolicy.reportCandidates(
            listOf(5000.0),
            requestedDistanceM = 0.0,
        )

        assertEquals(0.0, reports[0].distanceDeviationPercent, 1e-9)
    }

    // --- Evaluate ---

    @Test
    fun `3 candidates at NONE tier is Sufficient`() {
        val outcome = DegradationPolicy.evaluate(
            listOf(48000.0, 50000.0, 52000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.NONE,
        )

        assertTrue(outcome is EvaluationOutcome.Sufficient)
        val sufficient = outcome as EvaluationOutcome.Sufficient
        assertEquals(3, sufficient.candidates.size)
        assertEquals(RelaxationTier.NONE, sufficient.appliedTier)
    }

    @Test
    fun `more than minDesired candidates is Sufficient`() {
        val outcome = DegradationPolicy.evaluate(
            listOf(48000.0, 49000.0, 50000.0, 51000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.WIDEN_BAND,
        )

        assertTrue(outcome is EvaluationOutcome.Sufficient)
    }

    @Test
    fun `2 candidates at NONE tier triggers NeedsRelaxation to WIDEN_BAND`() {
        val outcome = DegradationPolicy.evaluate(
            listOf(48000.0, 52000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.NONE,
            config = DegradationConfig(minDesiredCandidates = 3),
        )

        assertTrue(outcome is EvaluationOutcome.NeedsRelaxation)
        val relaxation = outcome as EvaluationOutcome.NeedsRelaxation
        assertEquals(2, relaxation.candidates.size)
        assertEquals(RelaxationTier.WIDEN_BAND, relaxation.nextTier)
    }

    @Test
    fun `0 candidates at NONE tier triggers NeedsRelaxation`() {
        val outcome = DegradationPolicy.evaluate(
            emptyList(),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.NONE,
        )

        assertTrue(outcome is EvaluationOutcome.NeedsRelaxation)
        assertEquals(RelaxationTier.WIDEN_BAND, (outcome as EvaluationOutcome.NeedsRelaxation).nextTier)
    }

    @Test
    fun `1 candidate at WIDEN_BAND triggers NeedsRelaxation to EASE_DISCOVERY`() {
        val outcome = DegradationPolicy.evaluate(
            listOf(50000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.WIDEN_BAND,
            config = DegradationConfig(minDesiredCandidates = 3),
        )

        assertTrue(outcome is EvaluationOutcome.NeedsRelaxation)
        assertEquals(
            RelaxationTier.EASE_DISCOVERY,
            (outcome as EvaluationOutcome.NeedsRelaxation).nextTier,
        )
    }

    @Test
    fun `2 candidates at EASE_PENALTIES is Sufficient (accepts partial)`() {
        val outcome = DegradationPolicy.evaluate(
            listOf(48000.0, 52000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.EASE_PENALTIES,
        )

        assertTrue(outcome is EvaluationOutcome.Sufficient)
        val sufficient = outcome as EvaluationOutcome.Sufficient
        assertEquals(2, sufficient.candidates.size)
        assertEquals(RelaxationTier.EASE_PENALTIES, sufficient.appliedTier)
    }

    @Test
    fun `1 candidate at loosest tier is Sufficient rather than padded to 3`() {
        val outcome = DegradationPolicy.evaluate(
            listOf(51000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.EASE_PENALTIES,
        )

        assertTrue(outcome is EvaluationOutcome.Sufficient)
        assertEquals(1, (outcome as EvaluationOutcome.Sufficient).candidates.size)
    }

    @Test
    fun `0 candidates at loosest tier is HardFailure`() {
        val outcome = DegradationPolicy.evaluate(
            emptyList(),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.EASE_PENALTIES,
        )

        assertTrue(outcome is EvaluationOutcome.HardFailure)
    }

    @Test
    fun `Sufficient outcome carries actual-vs-requested per candidate`() {
        val outcome = DegradationPolicy.evaluate(
            listOf(47500.0, 50000.0, 53000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.NONE,
        )

        val sufficient = outcome as EvaluationOutcome.Sufficient
        assertEquals(50000.0, sufficient.candidates[0].requestedDistanceM, 1e-9)
        assertEquals(47500.0, sufficient.candidates[0].actualDistanceM, 1e-9)
        assertEquals(-5.0, sufficient.candidates[0].distanceDeviationPercent, 1e-9)
    }

    @Test
    fun `custom minDesiredCandidates is respected`() {
        val config = DegradationConfig(minDesiredCandidates = 2)

        val outcome = DegradationPolicy.evaluate(
            listOf(48000.0, 52000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.NONE,
            config = config,
        )

        assertTrue(outcome is EvaluationOutcome.Sufficient)
    }

    // --- Failure reason contract ---

    @Test
    fun `failure reason cites unreachable distance when max reachable is known`() {
        val reason = DegradationPolicy.failureReason(
            requestedDistanceM = 150_000.0,
            maxReachableDistanceM = 80_000.0,
        )

        assertTrue(reason.contains("150 km"))
        assertTrue(reason.contains("80 km"))
        assertTrue(reason.contains("shorter distance"))
    }

    @Test
    fun `failure reason cites home location when max reachable is null`() {
        val reason = DegradationPolicy.failureReason(
            requestedDistanceM = 50_000.0,
            maxReachableDistanceM = null,
        )

        assertTrue(reason.contains("home"))
        assertTrue(reason.contains("cycling roads"))
    }

    @Test
    fun `failure reason cites home location when requested is within reachable`() {
        val reason = DegradationPolicy.failureReason(
            requestedDistanceM = 50_000.0,
            maxReachableDistanceM = 80_000.0,
        )

        assertTrue(reason.contains("home"))
    }

    @Test
    fun `HardFailure carries specific reason from evaluate`() {
        val outcome = DegradationPolicy.evaluate(
            emptyList(),
            requestedDistanceM = 200_000.0,
            currentTier = RelaxationTier.EASE_PENALTIES,
            maxReachableDistanceM = 80_000.0,
        )

        val failure = outcome as EvaluationOutcome.HardFailure
        assertTrue(failure.reason.contains("200 km"))
        assertTrue(failure.reason.contains("80 km"))
    }
}
