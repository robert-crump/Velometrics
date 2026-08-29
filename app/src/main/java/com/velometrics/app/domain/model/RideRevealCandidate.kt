package com.velometrics.app.domain.model

/** Which personal-best pool a [RideRevealCandidate] ranked in. Ordered best-first: all-time beats this-year. */
enum class RideRevealScope { ALL_TIME, THIS_YEAR }

/**
 * Which category of achievement produced a [RideRevealCandidate]. Ordered best-first (used only as
 * a tie-break when [RideRevealPriority.scope] and [RideRevealPriority.rank] are equal): a ride-level
 * milestone beats a power-curve best-effort, and the guaranteed fallback is always last.
 */
enum class RideRevealFamily { RIDE_MILESTONE, POWER_CURVE_BEST_EFFORT, FALLBACK }

/**
 * Priority key for a [RideRevealCandidate], compared best-first: [scope], then [rank] (1st beats
 * 2nd beats 3rd), then [family]. [RideRevealResolver] picks the candidate with the lowest
 * (best) priority among everything registered for a ride.
 */
data class RideRevealPriority(
    val scope: RideRevealScope,
    val rank: Int,
    val family: RideRevealFamily
) : Comparable<RideRevealPriority> {
    override fun compareTo(other: RideRevealPriority): Int {
        if (scope != other.scope) return scope.compareTo(other.scope)
        if (rank != other.rank) return rank.compareTo(other.rank)
        return family.compareTo(other.family)
    }

    companion object {
        /**
         * Priority for the always-eligible, plain-stats fallback. [rank] is set to lose any
         * same-scope comparison against a real achievement (which is always rank 1-3), and
         * [family] itself already sorts last regardless — this is just belt-and-suspenders.
         */
        val FALLBACK = RideRevealPriority(RideRevealScope.THIS_YEAR, Int.MAX_VALUE, RideRevealFamily.FALLBACK)
    }
}

/** One competitor for the Ride Reveal hero slot: a headline to show, plus the priority used to pick a winner. */
data class RideRevealCandidate(
    val headline: String,
    val priority: RideRevealPriority
)
