package com.velometrics.app.domain.model

/**
 * A [RepeatedInterval]'s id + name — enough to both label an interval row in [IntervalSession]
 * lists and navigate to that repeated interval's own detail screen (#183), without pulling in
 * the full [RepeatedInterval] (its reps, edges, geometry) just to render a name.
 */
data class RepeatedIntervalRef(val repeatedIntervalId: Long, val name: String)
