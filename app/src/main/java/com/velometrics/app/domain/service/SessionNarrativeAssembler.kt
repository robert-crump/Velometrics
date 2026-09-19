package com.velometrics.app.domain.service

import com.velometrics.app.data.cache.RepeatedRoutesCache
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RepeatedRoute
import com.velometrics.app.util.CyclingConstants.ROUTE_CLUSTER_MIN_GROUP_SIZE
import com.velometrics.app.util.median
import java.util.Locale
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

/** The Session Detail Repeated Route recap (#217); tapping it opens the route's detail screen. */
data class RouteRecap(val routeId: Long, val headline: String, val text: String)

/**
 * Single entry point for the Session Detail tag recap (#214): owns the tag-scoped comparison pool,
 * the interval fetch and the narrative generation, so the "comparison was computed with this same
 * tag" invariant holds by construction instead of by two adjacent call sites staying in sync.
 */
class SessionNarrativeAssembler @Inject constructor(
    private val sessionRepository: CyclingSessionRepository,
    private val intervalRepository: IntervalRepository,
    private val sessionComparator: SessionComparator,
    private val repeatedRoutesCache: RepeatedRoutesCache
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

    /**
     * Repeated Route recap (#217): null unless [sessionId] belongs to a qualifying route. Reactive
     * on the routes cache so a recluster (e.g. after pull-to-refresh) updates the block.
     */
    fun observeRouteRecap(sessionId: Long): Flow<RouteRecap?> =
        repeatedRoutesCache.routes
            .map { routes ->
                routes.firstOrNull { r -> r.sessions.any { it.id == sessionId } }
                    ?.let { buildRouteRecap(sessionId, it) }
            }
            .distinctUntilChanged()

    companion object {
        /** Compares against all *other* rides on the route; null if it doesn't qualify or no stat renders. */
        fun buildRouteRecap(sessionId: Long, route: RepeatedRoute): RouteRecap? {
            if (route.sessions.size < ROUTE_CLUSTER_MIN_GROUP_SIZE) return null
            val current = route.sessions.firstOrNull { it.id == sessionId } ?: return null
            val others = route.sessions.filter { it.id != sessionId }

            fun speed(s: CyclingSession): Double? =
                if (s.netDurationSec > 0) s.distanceKm / s.netDurationSec * 3600 else null

            val parts = listOfNotNull(
                stat("Avg. speed", speed(current), others.mapNotNull(::speed), " km/h", "%.1f"),
                stat("Avg. power", current.averagePower?.toDouble(), others.mapNotNull { it.averagePower?.toDouble() }, " W", "%.0f"),
                stat("Avg. heart rate", current.avgHeartRate?.toDouble(), others.mapNotNull { it.avgHeartRate?.toDouble() }, " bpm", "%.0f")
            )
            if (parts.isEmpty()) return null
            val text = parts.mapIndexed { i, (value, median) ->
                if (i == 0) "$value (vs. $median in a typical ride on this route)" else "$value (vs. $median)"
            }.joinToString(". ", postfix = ".")
            val headline = if (route.isCustomName) "Vs. other ${route.name} rides" else "Vs. other rides on this route"
            return RouteRecap(route.id, headline, text)
        }

        private fun stat(label: String, value: Double?, others: List<Double>, unit: String, fmt: String): Pair<String, String>? {
            val cur = value ?: return null
            val med = others.median() ?: return null
            return "$label ${fmt.format(Locale.US, cur)}$unit" to "${fmt.format(Locale.US, med)}$unit"
        }
    }
}
