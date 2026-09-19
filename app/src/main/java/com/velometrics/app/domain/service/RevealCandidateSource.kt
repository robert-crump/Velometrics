package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideRevealCandidate

/**
 * One family of Ride Reveal achievements. Bound `@IntoSet` in `RevealSourceModule`; adding a family
 * means a new implementation, one binding, and a [com.velometrics.app.domain.model.RideRevealFamily]
 * slot in [RideRevealResolver.FAMILY_ORDER].
 */
interface RevealCandidateSource {
    suspend fun candidates(session: CyclingSession): List<RideRevealCandidate>
}
