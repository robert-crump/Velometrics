package com.velometrics.app.domain.service

import com.velometrics.app.domain.service.DegradationPolicy.RelaxationTier
import com.velometrics.app.domain.service.DegradationPolicy.EvaluationOutcome
import org.junit.Assert.*
import org.junit.Test

class DegradationPolicyTest {

    // --- Tier ordering ---

    @Test
    fun `tier progression follows cone then reach then separation then band`() {
        assertEquals(RelaxationTier.WIDEN_CONE, DegradationPolicy.nextTier(RelaxationTier.NONE))
        assertEquals(RelaxationTier.EXTEND_REACH, DegradationPolicy.nextTier(RelaxationTier.WIDEN_CONE))
        assertEquals(RelaxationTier.SHRINK_SEPARATION, DegradationPolicy.nextTier(RelaxationTier.EXTEND_REACH))
        assertEquals(RelaxationTier.WIDEN_BAND, DegradationPolicy.nextTier(RelaxationTier.SHRINK_SEPARATION))
        assertNull(DegradationPolicy.nextTier(RelaxationTier.WIDEN_BAND))
    }

    // --- Tier params: each tier turns one more skeleton lever on ---

    @Test
    fun `NONE tier uses base skeleton constraints`() {
        val params = DegradationPolicy.tierParams(RelaxationTier.NONE)

        assertEquals(0.0, params.headingConeCosine, 1e-9)
        assertEquals(1.0 / 3.0, params.reachFraction, 1e-9)
        assertEquals(2000.0, params.separationM, 1e-9)
        assertEquals(0.15, params.distanceBandFraction, 1e-9)
    }

    @Test
    fun `WIDEN_CONE tier only widens the heading cone`() {
        val params = DegradationPolicy.tierParams(RelaxationTier.WIDEN_CONE)

        assertEquals(-0.5, params.headingConeCosine, 1e-9)
        assertEquals(1.0 / 3.0, params.reachFraction, 1e-9)
        assertEquals(2000.0, params.separationM, 1e-9)
        assertEquals(0.15, params.distanceBandFraction, 1e-9)
    }

    @Test
    fun `EXTEND_REACH tier widens cone and extends reach`() {
        val params = DegradationPolicy.tierParams(RelaxationTier.EXTEND_REACH)

        assertEquals(-0.5, params.headingConeCosine, 1e-9)
        assertEquals(0.5, params.reachFraction, 1e-9)
        assertEquals(2000.0, params.separationM, 1e-9)
        assertEquals(0.15, params.distanceBandFraction, 1e-9)
    }

    @Test
    fun `SHRINK_SEPARATION tier widens cone, extends reach, and shrinks separation`() {
        val params = DegradationPolicy.tierParams(RelaxationTier.SHRINK_SEPARATION)

        assertEquals(-0.5, params.headingConeCosine, 1e-9)
        assertEquals(0.5, params.reachFraction, 1e-9)
        assertEquals(1000.0, params.separationM, 1e-9)
        assertEquals(0.15, params.distanceBandFraction, 1e-9)
    }

    @Test
    fun `WIDEN_BAND tier widens every lever including the distance band`() {
        val params = DegradationPolicy.tierParams(RelaxationTier.WIDEN_BAND)

        assertEquals(-0.5, params.headingConeCosine, 1e-9)
        assertEquals(0.5, params.reachFraction, 1e-9)
        assertEquals(1000.0, params.separationM, 1e-9)
        assertEquals(0.30, params.distanceBandFraction, 1e-9)
    }

    @Test
    fun `each tier is cumulatively more relaxed than the previous`() {
        val tiers = listOf(
            RelaxationTier.NONE,
            RelaxationTier.WIDEN_CONE,
            RelaxationTier.EXTEND_REACH,
            RelaxationTier.SHRINK_SEPARATION,
            RelaxationTier.WIDEN_BAND,
        )
        val params = tiers.map { DegradationPolicy.tierParams(it) }

        for (i in 1 until params.size) {
            val prev = params[i - 1]
            val curr = params[i]
            assertTrue(
                "Tier ${tiers[i]} cone should be <= ${tiers[i - 1]} (wider cone = lower cosine)",
                curr.headingConeCosine <= prev.headingConeCosine,
            )
            assertTrue(
                "Tier ${tiers[i]} reach should be >= ${tiers[i - 1]}",
                curr.reachFraction >= prev.reachFraction,
            )
            assertTrue(
                "Tier ${tiers[i]} separation should be <= ${tiers[i - 1]}",
                curr.separationM <= prev.separationM,
            )
            assertTrue(
                "Tier ${tiers[i]} band should be >= ${tiers[i - 1]}",
                curr.distanceBandFraction >= prev.distanceBandFraction,
            )
        }
    }

    @Test
    fun `tierParams respects custom config`() {
        val config = DegradationConfig(
            widenedHeadingConeCosine = -0.8,
            extendedReachFraction = 0.6,
            shrunkSeparationM = 500.0,
            widenedDistanceBandFraction = 0.40,
        )
        val params = DegradationPolicy.tierParams(RelaxationTier.WIDEN_BAND, config = config)

        assertEquals(-0.8, params.headingConeCosine, 1e-9)
        assertEquals(0.6, params.reachFraction, 1e-9)
        assertEquals(500.0, params.separationM, 1e-9)
        assertEquals(0.40, params.distanceBandFraction, 1e-9)
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

    // --- Evaluate: acceptance judged on actual refined distance ---

    @Test
    fun `candidate inside the base band at NONE is Sufficient`() {
        // 50000 +/- 15% = [42500, 57500]; all three land inside.
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
    fun `out-of-band candidates are excluded from the accepted set`() {
        // Only 50000 is within [42500, 57500]; 40000 and 60000 are outside.
        val outcome = DegradationPolicy.evaluate(
            listOf(40000.0, 50000.0, 60000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.NONE,
        )

        val sufficient = outcome as EvaluationOutcome.Sufficient
        assertEquals(1, sufficient.candidates.size)
        assertEquals(50000.0, sufficient.candidates[0].actualDistanceM, 1e-9)
    }

    @Test
    fun `no in-band candidate at NONE triggers NeedsRelaxation to WIDEN_CONE`() {
        // 60000 is outside the base band [42500, 57500] but inside the widened band.
        val outcome = DegradationPolicy.evaluate(
            listOf(60000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.NONE,
        )

        assertTrue(outcome is EvaluationOutcome.NeedsRelaxation)
        val relaxation = outcome as EvaluationOutcome.NeedsRelaxation
        assertTrue("Out-of-band candidate is not accepted", relaxation.candidates.isEmpty())
        assertEquals(RelaxationTier.WIDEN_CONE, relaxation.nextTier)
    }

    @Test
    fun `a route that fails tier 0 is accepted once the band is widened at the loosest tier`() {
        val distances = listOf(62000.0) // outside base [42500,57500], inside widened [35000,65000]
        val requested = 50000.0

        val tier0 = DegradationPolicy.evaluate(distances, requested, RelaxationTier.NONE)
        assertTrue("Should not accept at the tightest tier", tier0 is EvaluationOutcome.NeedsRelaxation)

        val loosest = DegradationPolicy.evaluate(distances, requested, RelaxationTier.WIDEN_BAND)
        assertTrue("Widened band should accept the same route", loosest is EvaluationOutcome.Sufficient)
        val sufficient = loosest as EvaluationOutcome.Sufficient
        assertEquals(1, sufficient.candidates.size)
        assertEquals(RelaxationTier.WIDEN_BAND, sufficient.appliedTier)
    }

    @Test
    fun `insufficient in-band candidates relaxes through the tier chain`() {
        // Two in-band routes but three desired -> relax one step from NONE.
        val outcome = DegradationPolicy.evaluate(
            listOf(48000.0, 52000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.NONE,
            config = DegradationConfig(minDesiredCandidates = 3),
        )

        assertTrue(outcome is EvaluationOutcome.NeedsRelaxation)
        val relaxation = outcome as EvaluationOutcome.NeedsRelaxation
        assertEquals(2, relaxation.candidates.size)
        assertEquals(RelaxationTier.WIDEN_CONE, relaxation.nextTier)
    }

    @Test
    fun `partial in-band set at the loosest tier is accepted rather than padded`() {
        val outcome = DegradationPolicy.evaluate(
            listOf(51000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.WIDEN_BAND,
            config = DegradationConfig(minDesiredCandidates = 3),
        )

        assertTrue(outcome is EvaluationOutcome.Sufficient)
        assertEquals(1, (outcome as EvaluationOutcome.Sufficient).candidates.size)
    }

    @Test
    fun `zero in-band candidates at the loosest tier is a HardFailure`() {
        // 70000 is outside even the widened band [35000, 65000].
        val outcome = DegradationPolicy.evaluate(
            listOf(70000.0),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.WIDEN_BAND,
        )

        assertTrue(outcome is EvaluationOutcome.HardFailure)
    }

    @Test
    fun `empty candidate list at the loosest tier is a HardFailure`() {
        val outcome = DegradationPolicy.evaluate(
            emptyList(),
            requestedDistanceM = 50000.0,
            currentTier = RelaxationTier.WIDEN_BAND,
        )

        assertTrue(outcome is EvaluationOutcome.HardFailure)
    }

    @Test
    fun `Sufficient outcome carries actual-vs-requested per accepted candidate`() {
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
            currentTier = RelaxationTier.WIDEN_BAND,
            maxReachableDistanceM = 80_000.0,
        )

        val failure = outcome as EvaluationOutcome.HardFailure
        assertTrue(failure.reason.contains("200 km"))
        assertTrue(failure.reason.contains("80 km"))
    }
}
