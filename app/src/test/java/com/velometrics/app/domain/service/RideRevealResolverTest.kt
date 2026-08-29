package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.RideRevealCandidate
import com.velometrics.app.domain.model.RideRevealFamily
import com.velometrics.app.domain.model.RideRevealPriority
import com.velometrics.app.domain.model.RideRevealScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RideRevealResolverTest {

    private fun candidate(
        headline: String,
        scope: RideRevealScope,
        rank: Int,
        family: RideRevealFamily
    ) = RideRevealCandidate(headline, RideRevealPriority(scope, rank, family))

    @Test
    fun `single fallback candidate wins by itself`() {
        val fallback = RideRevealCandidate("Nice ride!", RideRevealPriority.FALLBACK)
        assertEquals(fallback, RideRevealResolver.resolve(listOf(fallback)))
    }

    @Test
    fun `any real achievement beats the fallback`() {
        val fallback = RideRevealCandidate("Nice ride!", RideRevealPriority.FALLBACK)
        val achievement = candidate("3rd fastest this year", RideRevealScope.THIS_YEAR, 3, RideRevealFamily.POWER_CURVE_BEST_EFFORT)

        assertEquals(achievement, RideRevealResolver.resolve(listOf(fallback, achievement)))
        assertEquals(achievement, RideRevealResolver.resolve(listOf(achievement, fallback)))
    }

    @Test
    fun `all-time beats this-year regardless of rank`() {
        val allTime3rd = candidate("3rd all-time", RideRevealScope.ALL_TIME, 3, RideRevealFamily.RIDE_MILESTONE)
        val thisYear1st = candidate("1st this year", RideRevealScope.THIS_YEAR, 1, RideRevealFamily.RIDE_MILESTONE)

        assertEquals(allTime3rd, RideRevealResolver.resolve(listOf(allTime3rd, thisYear1st)))
    }

    @Test
    fun `within the same scope, lower rank wins`() {
        val rank1 = candidate("1st", RideRevealScope.ALL_TIME, 1, RideRevealFamily.POWER_CURVE_BEST_EFFORT)
        val rank2 = candidate("2nd", RideRevealScope.ALL_TIME, 2, RideRevealFamily.RIDE_MILESTONE)

        assertEquals(rank1, RideRevealResolver.resolve(listOf(rank2, rank1)))
    }

    @Test
    fun `within the same scope and rank, ride-level milestone beats power-curve best-effort`() {
        val milestone = candidate("Longest ride", RideRevealScope.ALL_TIME, 1, RideRevealFamily.RIDE_MILESTONE)
        val powerCurve = candidate("Best 5min power", RideRevealScope.ALL_TIME, 1, RideRevealFamily.POWER_CURVE_BEST_EFFORT)

        assertEquals(milestone, RideRevealResolver.resolve(listOf(powerCurve, milestone)))
    }

    @Test
    fun `empty candidate list is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RideRevealResolver.resolve(emptyList())
        }
    }
}
