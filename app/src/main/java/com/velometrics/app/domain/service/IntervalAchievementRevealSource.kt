package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.AchievementScope
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideRevealCandidate
import com.velometrics.app.domain.model.RideRevealFamily
import com.velometrics.app.domain.model.RideRevealPriority
import com.velometrics.app.domain.model.RideRevealScope
import com.velometrics.app.domain.repository.IntervalRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Lets a repeated-interval personal best (the [com.velometrics.app.domain.model.IntervalAchievement]
 * snapshot persisted at import by [IntervalMatcher], #185) compete for the hero slot. Relies on the
 * import having matched intervals before the reveal is evaluated.
 */
@Singleton
class IntervalAchievementRevealSource @Inject constructor(
    private val intervalRepository: IntervalRepository
) : RevealCandidateSource {

    override suspend fun candidates(session: CyclingSession): List<RideRevealCandidate> =
        intervalRepository.getIntervalsForSession(session.id).first().mapNotNull { interval ->
            val rank = interval.achievementRank ?: return@mapNotNull null
            val scope = when (interval.achievementScope ?: return@mapNotNull null) {
                AchievementScope.ALL_TIME -> RideRevealScope.ALL_TIME
                AchievementScope.THIS_YEAR -> RideRevealScope.THIS_YEAR
            }
            RideRevealCandidate(
                headline = headline(rank, scope),
                priority = RideRevealPriority(scope, rank, RideRevealFamily.INTERVAL_ACHIEVEMENT)
            )
        }

    private fun headline(rank: Int, scope: RideRevealScope): String {
        val ordinal = when (rank) {
            1 -> "fastest"
            2 -> "2nd-fastest"
            else -> "3rd-fastest"
        }
        val scopeText = if (scope == RideRevealScope.ALL_TIME) "ever" else "this year"
        return "Your $ordinal repeat of a favourite interval $scopeText!"
    }
}
