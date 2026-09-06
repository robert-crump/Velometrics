package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.AchievementScope
import com.velometrics.app.domain.model.IntervalAchievement
import com.velometrics.app.domain.model.IntervalSession
import java.time.ZoneId

/**
 * Computes a snapshot [IntervalAchievement] for one newly-matched [IntervalSession] rep, ranked by
 * raw [IntervalSession.avgSpeedKmh] among all of its own [com.velometrics.app.domain.model.RepeatedInterval]
 * archetype's reps (#185) -- never against other archetypes or plain rides. Same count-greater-than
 * top-3 shape as [RankedMetricEvaluator], but scoped to an in-memory rep list rather than a
 * repository query, since [IntervalMatcher] already has the archetype's whole rep pool in hand
 * right after matching.
 *
 * Called once, at import time, right after [IntervalMatcher.matchToRepeatedIntervals] appends the
 * new rep to its archetype's [com.velometrics.app.domain.model.RepeatedInterval.intervals] -- the
 * result is persisted and never recomputed, so a later rep changing an earlier one's rank doesn't
 * retroactively update it (same precedent as ride-reveal milestones).
 */
object IntervalAchievementEvaluator {

    /** An archetype's debut reps can't claim "1st best ever" -- nothing to have beaten yet. */
    private const val MIN_REPS = 3

    /**
     * [reps] must be the archetype's full, already-updated rep list (including [interval] itself).
     * Returns `null` when there are fewer than [MIN_REPS] total reps, or when [interval] doesn't
     * rank top-3 in either scope. When it ranks top-3 in both, [AchievementScope.ALL_TIME] wins.
     */
    fun evaluate(interval: IntervalSession, reps: List<IntervalSession>): IntervalAchievement? {
        if (reps.size < MIN_REPS) return null

        rank(interval, reps)?.let { return IntervalAchievement(it, AchievementScope.ALL_TIME, null) }

        val year = interval.startTimestamp.atZone(ZoneId.systemDefault()).year
        val repsThisYear = reps.filter { it.startTimestamp.atZone(ZoneId.systemDefault()).year == year }
        rank(interval, repsThisYear)?.let { return IntervalAchievement(it, AchievementScope.THIS_YEAR, year) }

        return null
    }

    /** Count-greater-than rank of [interval] within [pool] (by raw avgSpeedKmh), or `null` past 3rd. */
    private fun rank(interval: IntervalSession, pool: List<IntervalSession>): Int? {
        val countGreater = pool.count { it.avgSpeedKmh > interval.avgSpeedKmh }
        val rank = countGreater + 1
        return if (rank <= 3) rank else null
    }
}
