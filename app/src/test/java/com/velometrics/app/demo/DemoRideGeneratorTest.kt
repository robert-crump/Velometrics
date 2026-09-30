package com.velometrics.app.demo

import com.garmin.fit.Decode
import java.io.ByteArrayInputStream
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #219: shape and determinism of the README demo rides, independent of the app's import. */
class DemoRideGeneratorTest {

    private val rides = DemoRideTestSupport.rides
    private val today = DemoRideTestSupport.TODAY

    @Test
    fun `same seed and day produce byte-identical files`() {
        val again = DemoRideGenerator.generate(today, DemoRideTestSupport::openAsset)
        assertEquals(rides.size, again.size)
        rides.zip(again).forEach { (a, b) ->
            assertEquals(a.fileName, b.fileName)
            assertArrayEquals(a.fileName, a.fitBytes, b.fitBytes)
        }
    }

    @Test
    fun `a different master seed produces different rides`() {
        val other = DemoRideGenerator.generate(today, DemoRideTestSupport::openAsset, masterSeed = 7L)
        assertFalse(rides.last().fitBytes.contentEquals(other.last().fitBytes))
    }

    @Test
    fun `thirty rides over ten weeks, three a week, ending on the run date`() {
        assertEquals(30, rides.size)
        val dates = rides.map { it.start.atZone(DemoRideGenerator.ZONE).toLocalDate() }
        assertEquals(dates.sorted(), dates)
        assertEquals(today, dates.last())
        assertTrue(ChronoUnit.DAYS.between(dates.first(), today) in 63L..70L)
        dates.groupBy { ChronoUnit.WEEKS.between(it, today.plusDays(1)) }.values.forEach { assertEquals(3, it.size) }
        assertEquals(rides.size, rides.map { it.fileName }.toSet().size)
    }

    @Test
    fun `loop mix favours the aachen interval session and ends on the hero loop`() {
        val counts = rides.groupingBy { it.loop }.eachCount()
        assertTrue(counts.getValue(DemoLoop.AACHEN) in 8..10)
        DemoLoop.entries.forEach { assertTrue(it.id, counts.getValue(it) >= 3) }
        assertEquals(DemoLoop.SUED_LIMBURG, rides.last().loop)
    }

    @Test
    fun `every file passes FIT integrity checks`() {
        rides.forEach { assertTrue(it.fileName, Decode().checkFileIntegrity(ByteArrayInputStream(it.fitBytes))) }
    }

    @Test
    fun `every ride has power and heart rate on every moving record`() {
        rides.forEach { ride ->
            val moving = ride.samples.filter { it.speedMps > 0 }
            assertTrue(ride.fileName, moving.all { it.heartRate in 60..185 })
            assertTrue(ride.fileName, moving.count { it.power > 0 } > moving.size * 0.8)
            assertTrue(ride.fileName, ride.samples.all { it.power <= 1400 && it.speedMps <= DemoTrack.MAX_SPEED_MPS + 1e-9 })
        }
    }

    @Test
    fun `hero ride is long, with stops and sprints`() {
        val hero = rides.last()
        val movingSec = hero.samples.last().epochSec - hero.samples.first().epochSec -
            hero.plan.stops.sumOf { it.durationSec }
        assertTrue(movingSec >= 3600)
        assertTrue(hero.plan.stops.isNotEmpty())
        assertEquals(hero.plan.stops.size, hero.timerEvents.count { it.type == DemoTimerEventType.STOP })
        assertTrue(hero.plan.sprints.isNotEmpty())
    }

    @Test
    fun `sprints hit 800 to 1100 W for 8 to 15 seconds`() {
        rides.flatMap { it.plan.sprints }.forEach {
            assertTrue(it.durationSec in 8..15)
            assertTrue(it.peakPower in 800..1100)
        }
        rides.forEach { assertTrue(it.fileName, it.plan.sprints.size in 1..3) }
    }

    @Test
    fun `gpx assets carry only track geometry`() {
        DemoLoop.entries.forEach { loop ->
            val text = DemoRideTestSupport.openAsset(loop.asset).bufferedReader().use { it.readText() }
            listOf("<time", "<metadata", "<wpt", "<name", "komoot", "<link", "<type").forEach { tag ->
                assertFalse("${loop.asset} contains $tag", text.contains(tag, ignoreCase = true))
            }
            val tags = Regex("""<([a-zA-Z]+)""").findAll(text).map { it.groupValues[1] }.toSet()
            assertEquals(setOf("gpx", "trk", "trkseg", "trkpt", "ele"), tags)
        }
    }
}
