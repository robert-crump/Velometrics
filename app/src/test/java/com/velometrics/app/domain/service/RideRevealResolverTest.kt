package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.RideRevealCandidate
import com.velometrics.app.domain.model.RideRevealFamily
import com.velometrics.app.domain.model.RideRevealPriority
import com.velometrics.app.domain.model.RideRevealScope
import org.junit.Assert.assertEquals
import org.junit.Test

class RideRevealResolverTest {

    private fun candidate(
        headline: String,
        scope: RideRevealScope,
        rank: Int,
        family: RideRevealFamily
    ) = RideRevealCandidate(headline, RideRevealPriority(scope, rank, family))

    @Test
    fun `no candidates resolves to the plain-stats fallback`() {
        assertEquals(RideRevealResolver.FALLBACK_HEADLINE, RideRevealResolver.resolve(emptyList()))
    }

    @Test
    fun `any real achievement beats the fallback`() {
        val achievement = candidate("3rd fastest this year", RideRevealScope.THIS_YEAR, 3, RideRevealFamily.POWER_CURVE_BEST_EFFORT)

        assertEquals("3rd fastest this year", RideRevealResolver.resolve(listOf(achievement)))
    }

    @Test
    fun `all-time beats this-year regardless of rank`() {
        val allTime3rd = candidate("3rd all-time", RideRevealScope.ALL_TIME, 3, RideRevealFamily.RIDE_MILESTONE)
        val thisYear1st = candidate("1st this year", RideRevealScope.THIS_YEAR, 1, RideRevealFamily.RIDE_MILESTONE)

        assertEquals("3rd all-time", RideRevealResolver.resolve(listOf(thisYear1st, allTime3rd)))
    }

    @Test
    fun `within the same scope, lower rank wins`() {
        val rank1 = candidate("1st", RideRevealScope.ALL_TIME, 1, RideRevealFamily.POWER_CURVE_BEST_EFFORT)
        val rank2 = candidate("2nd", RideRevealScope.ALL_TIME, 2, RideRevealFamily.RIDE_MILESTONE)

        assertEquals("1st", RideRevealResolver.resolve(listOf(rank2, rank1)))
    }

    @Test
    fun `at equal scope and rank the family order decides, milestone then interval then power curve`() {
        val byFamily = RideRevealFamily.entries.associateWith {
            candidate(it.name, RideRevealScope.ALL_TIME, 1, it)
        }

        assertEquals(
            "RIDE_MILESTONE",
            RideRevealResolver.resolve(byFamily.values.toList().reversed())
        )
        assertEquals(
            "INTERVAL_ACHIEVEMENT",
            RideRevealResolver.resolve(
                listOf(byFamily.getValue(RideRevealFamily.POWER_CURVE_BEST_EFFORT), byFamily.getValue(RideRevealFamily.INTERVAL_ACHIEVEMENT))
            )
        )
    }

    @Test
    fun `winner comes from the explicit orders, not enum declaration order`() {
        // Every family and scope must be listed, so a new enum constant can't silently rank last (indexOf = -1 would rank it first).
        assertEquals(RideRevealFamily.entries.toSet(), RideRevealResolver.FAMILY_ORDER.toSet())
        assertEquals(RideRevealFamily.entries.size, RideRevealResolver.FAMILY_ORDER.size)
        assertEquals(RideRevealScope.entries.toSet(), RideRevealResolver.SCOPE_ORDER.toSet())
        assertEquals(RideRevealScope.entries.size, RideRevealResolver.SCOPE_ORDER.size)

        // The declared enum order is POWER_CURVE before INTERVAL; the resolver's list says the opposite, and wins.
        assertEquals(
            RideRevealFamily.RIDE_MILESTONE,
            RideRevealResolver.FAMILY_ORDER.first()
        )
        val expectedWinner = RideRevealResolver.FAMILY_ORDER.first()
        val candidates = RideRevealResolver.FAMILY_ORDER.reversed().map {
            candidate(it.name, RideRevealScope.THIS_YEAR, 2, it)
        }
        assertEquals(expectedWinner.name, RideRevealResolver.resolve(candidates))
    }
}
