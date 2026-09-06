package com.velometrics.app.domain.model

/**
 * Which pool of a [RepeatedInterval]'s own reps an [IntervalAchievement] ranked in. Unlike
 * [RideRevealScope] (which ranks a ride against every other ride), this scope only ever compares
 * reps of the same [RepeatedInterval] archetype against each other (#185).
 */
enum class AchievementScope { ALL_TIME, THIS_YEAR }

/**
 * A snapshot rank for one [IntervalSession] rep among its own [RepeatedInterval] archetype's reps,
 * computed once at import time by [com.velometrics.app.domain.service.IntervalAchievementEvaluator]
 * and never recomputed afterward (same precedent as ride-reveal milestones). [year] is only
 * meaningful for [AchievementScope.THIS_YEAR] -- it's the calendar year the rep itself happened in,
 * not wall-clock "now".
 */
data class IntervalAchievement(val rank: Int, val scope: AchievementScope, val year: Int?)
