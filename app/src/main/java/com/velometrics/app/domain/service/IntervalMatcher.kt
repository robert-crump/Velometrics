package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import com.velometrics.app.util.JsonSafeParser
import com.velometrics.app.util.PolylineDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assigns newly-detected raw [IntervalSession]s to existing [RepeatedInterval] archetypes (#26),
 * using the same length + GPS-point-overlap similarity test [IntervalClusteringService] uses to
 * cluster intervals in the first place — via [IntervalSimilarity] — so "matches" means one
 * consistent thing across grouping and assignment. When an interval qualifies against more than
 * one archetype, it is assigned only to the longest one ([RepeatedInterval.distanceM]).
 */
@Singleton
class IntervalMatcher @Inject constructor(
    private val repeatedIntervalRepository: RepeatedIntervalRepository,
    private val intervalRepository: IntervalRepository
) {

    companion object {
        private const val TAG = "IntervalMatcher"
    }

    /**
     * Matches [intervals] against the persisted archetypes and appends each match to its
     * archetype's [RepeatedInterval.intervals], persisting the updated archetypes. Also computes
     * and persists each newly-matched interval's [IntervalAchievementEvaluator] snapshot (#185),
     * ranked against its archetype's now-complete rep list. Returns [intervals] unchanged — the
     * archetype assignment lives on the archetype, not the raw interval.
     */
    suspend fun matchToRepeatedIntervals(intervals: List<IntervalSession>): List<IntervalSession> {
        if (intervals.isEmpty()) return intervals

        val archetypes = repeatedIntervalRepository.getAllRepeatedIntervalsList()
        if (archetypes.isEmpty()) return intervals

        matchIntervals(intervals, archetypes).entries
            .mapNotNull { (interval, archetype) -> archetype?.let { it to interval } }
            .groupBy({ (archetype, _) -> archetype }, { (_, interval) -> interval })
            .forEach { (archetype, matched) ->
                val updatedReps = archetype.intervals + matched
                matched.forEach { interval ->
                    val achievement = IntervalAchievementEvaluator.evaluate(interval, updatedReps)
                    if (achievement != null) {
                        intervalRepository.updateInterval(
                            interval.copy(achievementRank = achievement.rank, achievementScope = achievement.scope)
                        )
                    }
                }
                repeatedIntervalRepository.saveRepeatedInterval(archetype.copy(intervals = updatedReps))
            }

        return intervals
    }

    /**
     * Pure matching function: each interval maps to the [RepeatedInterval] it qualifies against
     * with the greatest [RepeatedInterval.distanceM] (the "longer one" tie-break rule), or `null`
     * if it qualifies against none. Qualification mirrors [IntervalClusteringService.runClustering]'s
     * pairing test: length delta within tolerance AND sufficient GPS-point overlap.
     */
    fun matchIntervals(
        intervals: List<IntervalSession>,
        archetypes: List<RepeatedInterval>
    ): Map<IntervalSession, RepeatedInterval?> {
        if (archetypes.isEmpty()) return intervals.associateWith { null }

        val preparedArchetypes = archetypes.mapNotNull { archetype ->
            val points = archetypeTrack(archetype)
            if (points.size < 2) null
            else archetype to IntervalSimilarity.PreparedTrack(points, archetype.distanceM)
        }

        return intervals.associateWith { interval ->
            val points = parseGpsTrack(interval.gpsTrack)
            if (points.size < 2) return@associateWith null

            val prepared = IntervalSimilarity.PreparedTrack(points, interval.distanceM)
            preparedArchetypes
                .filter { (_, archetypeTrack) -> IntervalSimilarity.qualifies(prepared, archetypeTrack) }
                .maxByOrNull { (archetype, _) -> archetype.distanceM }
                ?.first
        }
    }

    /** Decodes an archetype's matched road-graph edges into a single `[lat, lon]` point sequence. */
    private fun archetypeTrack(archetype: RepeatedInterval): List<List<Double>> {
        return archetype.edges.flatMap { edge ->
            PolylineDecoder.decode(edge.geometryEncoded).map { listOf(it.latitude, it.longitude) }
        }
    }

    private fun parseGpsTrack(gpsTrackJson: String): List<List<Double>> =
        JsonSafeParser.parseOrDefault<List<List<Double>>>(gpsTrackJson, TAG, "Failed to parse GPS track JSON", emptyList())
}
