package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.AchievementScope
import com.velometrics.app.domain.model.IntervalSession
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntervalAchievementEvaluatorTest {

    private fun interval(id: Long, avgSpeedKmh: Double, start: Instant = Instant.parse("2026-06-01T10:00:00Z")): IntervalSession {
        return IntervalSession(
            id = id,
            cyclingSessionId = id,
            startTimestamp = start,
            durationSec = 200,
            durationNormalizedSec = 200,
            distanceM = 1000.0,
            avgPower = 300,
            avgSpeedKmh = avgSpeedKmh,
            avgSpeedNormalizedKmh = avgSpeedKmh,
            direction = "out",
            startLat = 50.78, startLon = 6.08, endLat = 50.79, endLon = 6.08,
            gpsTrack = "[]"
        )
    }

    @Test
    fun `fewer than 3 total reps records no achievement even for the fastest rep`() {
        val a = interval(id = 1, avgSpeedKmh = 30.0)
        val b = interval(id = 2, avgSpeedKmh = 20.0)

        assertNull(IntervalAchievementEvaluator.evaluate(a, reps = listOf(a, b)))
    }

    @Test
    fun `exact boundary -- 3rd fastest of many qualifies, 4th fastest does not`() {
        val reps = (1..5).map { interval(id = it.toLong(), avgSpeedKmh = 50.0 - it) } // 49,48,47,46,45
        // reps[0]=49 (1st), reps[1]=48 (2nd), reps[2]=47 (3rd), reps[3]=46 (4th), reps[4]=45 (5th)

        val third = IntervalAchievementEvaluator.evaluate(reps[2], reps)
        assertEquals(3, third?.rank)
        assertEquals(AchievementScope.ALL_TIME, third?.scope)

        val fourth = IntervalAchievementEvaluator.evaluate(reps[3], reps)
        assertNull(fourth)
    }

    @Test
    fun `ties share the same rank -- two reps tied for fastest both rank 1st`() {
        val a = interval(id = 1, avgSpeedKmh = 40.0)
        val b = interval(id = 2, avgSpeedKmh = 40.0)
        val c = interval(id = 3, avgSpeedKmh = 30.0)
        val reps = listOf(a, b, c)

        assertEquals(1, IntervalAchievementEvaluator.evaluate(a, reps)?.rank)
        assertEquals(1, IntervalAchievementEvaluator.evaluate(b, reps)?.rank)
        // c is beaten by both a and b (strictly greater), so it ranks 3rd, not 2nd.
        assertEquals(3, IntervalAchievementEvaluator.evaluate(c, reps)?.rank)
    }

    @Test
    fun `qualifying in both scopes is recorded as ALL_TIME`() {
        // Only 3 total reps, all from this year -- the new rep is top-3 in both scopes at once.
        val older = interval(id = 1, avgSpeedKmh = 25.0, start = Instant.parse("2026-01-01T00:00:00Z"))
        val newer = interval(id = 2, avgSpeedKmh = 35.0, start = Instant.parse("2026-06-01T00:00:00Z"))
        val newest = interval(id = 3, avgSpeedKmh = 45.0, start = Instant.parse("2026-07-01T00:00:00Z"))
        val reps = listOf(older, newer, newest)

        val achievement = IntervalAchievementEvaluator.evaluate(newest, reps)

        assertEquals(1, achievement?.rank)
        assertEquals(AchievementScope.ALL_TIME, achievement?.scope)
        assertNull(achievement?.year)
    }

    @Test
    fun `a rep that only qualifies THIS_YEAR (not all-time) is recorded with that scope and year`() {
        val fastOldRep1 = interval(id = 1, avgSpeedKmh = 60.0, start = Instant.parse("2024-01-01T00:00:00Z"))
        val fastOldRep2 = interval(id = 2, avgSpeedKmh = 55.0, start = Instant.parse("2024-06-01T00:00:00Z"))
        val fastOldRep3 = interval(id = 3, avgSpeedKmh = 50.0, start = Instant.parse("2024-09-01T00:00:00Z"))
        val thisYearRep = interval(id = 4, avgSpeedKmh = 30.0, start = Instant.parse("2026-06-01T00:00:00Z"))
        val reps = listOf(fastOldRep1, fastOldRep2, fastOldRep3, thisYearRep)

        val achievement = IntervalAchievementEvaluator.evaluate(thisYearRep, reps)

        assertEquals(1, achievement?.rank) // 1st among this year's (sole) rep
        assertEquals(AchievementScope.THIS_YEAR, achievement?.scope)
        assertEquals(2026, achievement?.year)
    }

    @Test
    fun `a rep beaten too many times in both scopes gets no achievement`() {
        val reps = (1..6).map { interval(id = it.toLong(), avgSpeedKmh = 100.0 - it) }
        val slowest = reps.last()

        assertNull(IntervalAchievementEvaluator.evaluate(slowest, reps))
    }
}
