package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.IntervalRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject

/** The Session Detail tag recap (#214): [headline] is "Vs. other [TAG] rides", [text] the body. */
data class SessionNarrative(val headline: String, val text: String)

/**
 * Single entry point for the Session Detail tag recap (#214): owns the tag-scoped comparison pool,
 * the interval fetch and the narrative generation, so the "comparison was computed with this same
 * tag" invariant holds by construction instead of by two adjacent call sites staying in sync.
 */
class SessionNarrativeAssembler @Inject constructor(
    private val sessionRepository: CyclingSessionRepository,
    private val intervalRepository: IntervalRepository,
    private val sessionComparator: SessionComparator
) {
    /**
     * Null when the ride has no tag (the recap block is omitted) or the history lookup fails —
     * the recap is supplementary, so an error must not take Session Detail down with it.
     */
    suspend fun build(session: CyclingSession): SessionNarrative? {
        val tag = session.tag ?: return null
        return try {
            val comparison = sessionComparator.computeTagComparison(session, tag)
            val intervals = intervalRepository.getIntervalsForSession(session.id).first()
            SessionNarrative(
                headline = "Vs. other $tag rides",
                text = TagComparisonNarrative.generate(session, tag, comparison, intervals)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reactive variant: re-assembles when the session row's tag changes (e.g. backfilled later by
     * `RideClassificationService.reclassifyAll`), so the recap refreshes without leaving the screen.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(sessionId: Long): Flow<SessionNarrative?> =
        sessionRepository.getSessionsByIds(listOf(sessionId))
            .map { it.firstOrNull() }
            .distinctUntilChanged { old, new -> old?.tag == new?.tag }
            .mapLatest { session -> session?.let { build(it) } }
}
