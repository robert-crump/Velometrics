package com.velometrics.app.domain.model

/** Which personal-best pool a [RideRevealCandidate] ranked in. Ordering lives in [com.velometrics.app.domain.service.RideRevealResolver]. */
enum class RideRevealScope { ALL_TIME, THIS_YEAR }

/** Which category of achievement produced a [RideRevealCandidate]. Tie-break order lives in [com.velometrics.app.domain.service.RideRevealResolver]. */
enum class RideRevealFamily { RIDE_MILESTONE, POWER_CURVE_BEST_EFFORT, INTERVAL_ACHIEVEMENT }

/** What a [RideRevealCandidate] ranked as: pool, rank (1 = best) and source family. Compared only by [com.velometrics.app.domain.service.RideRevealResolver]. */
data class RideRevealPriority(
    val scope: RideRevealScope,
    val rank: Int,
    val family: RideRevealFamily
)

/** One competitor for the Ride Reveal hero slot: a headline to show, plus the priority used to pick a winner. */
data class RideRevealCandidate(
    val headline: String,
    val priority: RideRevealPriority
)
