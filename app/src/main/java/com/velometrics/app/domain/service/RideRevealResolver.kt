package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.RideRevealCandidate

/**
 * Picks the single best [RideRevealCandidate] to show in the Ride Reveal hero slot, among
 * everything registered for a ride. Achievement sources (ride-level milestones, power-curve
 * best-efforts) each register zero or one candidate; the always-eligible Tier 2 plain-stats
 * fallback registers exactly one, guaranteeing this never runs on an empty list in practice.
 */
object RideRevealResolver {

    /** [candidates] must be non-empty — callers always include the guaranteed fallback candidate. */
    fun resolve(candidates: List<RideRevealCandidate>): RideRevealCandidate {
        require(candidates.isNotEmpty()) {
            "RideRevealResolver.resolve requires at least one candidate"
        }
        return candidates.reduce { best, candidate ->
            if (candidate.priority < best.priority) candidate else best
        }
    }
}
