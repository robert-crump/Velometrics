package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.RideRevealCandidate
import com.velometrics.app.domain.model.RideRevealFamily
import com.velometrics.app.domain.model.RideRevealScope

/**
 * The whole Ride Reveal winner rule, in one place. A candidate wins by, in order: [SCOPE_ORDER]
 * (all-time beats this-year), then rank (1st beats 2nd beats 3rd), then [FAMILY_ORDER]. Orders are
 * explicit lists, not enum declaration order, so reordering the enums cannot change the headline.
 * With no candidates at all, the plain-stats [FALLBACK_HEADLINE] wins.
 */
object RideRevealResolver {

    /** Best-first. */
    val SCOPE_ORDER: List<RideRevealScope> = listOf(RideRevealScope.ALL_TIME, RideRevealScope.THIS_YEAR)

    /** Best-first; only a tie-break for equal scope and rank. Must list every [RideRevealFamily]. */
    val FAMILY_ORDER: List<RideRevealFamily> = listOf(
        RideRevealFamily.RIDE_MILESTONE,
        RideRevealFamily.INTERVAL_ACHIEVEMENT,
        RideRevealFamily.POWER_CURVE_BEST_EFFORT
    )

    const val FALLBACK_HEADLINE = "Nice ride!"

    private val comparator: Comparator<RideRevealCandidate> =
        compareBy<RideRevealCandidate>(
            { SCOPE_ORDER.indexOf(it.priority.scope) },
            { it.priority.rank },
            { FAMILY_ORDER.indexOf(it.priority.family) }
        )

    /** The best of [candidates], or the plain-stats fallback headline when there are none. */
    fun resolve(candidates: List<RideRevealCandidate>): String =
        candidates.minWithOrNull(comparator)?.headline ?: FALLBACK_HEADLINE
}
