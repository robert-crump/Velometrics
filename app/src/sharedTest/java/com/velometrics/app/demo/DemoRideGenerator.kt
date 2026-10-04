package com.velometrics.app.demo

import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Random

/** One generated demo ride: the FIT file plus the samples it was encoded from (for tests). */
class DemoRide(
    val index: Int,
    val loop: DemoLoop,
    val start: Instant,
    val fileName: String,
    val plan: DemoRidePlan,
    val samples: List<DemoSample>,
    val timerEvents: List<DemoTimerEvent>,
    val fitBytes: ByteArray
)

/**
 * Deterministic README demo data (#219): ~10 weeks of synthetic rides on four public loops around
 * Aachen, ending on [generate]'s `today`, three rides a week, all with power and heart rate at a
 * fixed 260 W FTP. Aachen is the interval session, Herzogenrath a threshold/tempo ride, Mechelen
 * climbs, Süd-Limburg the long endurance ride — and the most recent ride (the "hero" ride the
 * README screenshots open) is always a Süd-Limburg loop.
 *
 * Every ride draws from its own seed derived from [MASTER_SEED] and its index, so the same seed
 * and `today` always yield byte-identical FIT files. Nothing binary is checked in; the loops are
 * read through [openAsset] (classpath resources in JVM tests, instrumentation assets on device).
 */
object DemoRideGenerator {
    const val MASTER_SEED = 219L
    const val FTP = DemoRideModel.FTP
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    /** Loops per week, oldest week first: 9 Aachen, 7 Herzogenrath, 7 Mechelen, 7 Süd-Limburg. */
    private val WEEKS: List<List<DemoLoop>> = run {
        val a = DemoLoop.AACHEN
        val h = DemoLoop.HERZOGENRATH
        val m = DemoLoop.MECHELEN
        val s = DemoLoop.SUED_LIMBURG
        listOf(
            listOf(a, h, m), listOf(a, m, s), listOf(a, h, s), listOf(h, m, s), listOf(a, h, m),
            listOf(a, m, s), listOf(a, h, s), listOf(a, h, m), listOf(a, m, s), listOf(a, h, s)
        )
    }

    /** Day of each weekly slot relative to the week's last ride day. */
    private val SLOT_DAY_OFFSETS = listOf(-5L, -3L, 0L)
    private val SLOT_START_TIMES = listOf(LocalTime.of(17, 30), LocalTime.of(17, 45), LocalTime.of(9, 0))
    private val HERO_START_TIME = LocalTime.of(14, 0)

    fun generate(
        today: LocalDate,
        openAsset: (String) -> InputStream,
        masterSeed: Long = MASTER_SEED
    ): List<DemoRide> {
        val tracks = DemoLoop.entries.associateWith { loop ->
            openAsset(loop.asset).use { DemoTrack.from(DemoTrack.parseGpx(it)) }
        }
        val schedule = WEEKS.flatMapIndexed { week, loops ->
            loops.mapIndexed { slot, loop -> Triple(week, slot, loop) }
        }
        return schedule.mapIndexed { index, (week, slot, loop) ->
            val rng = Random(rideSeed(masterSeed, index))
            val date = today.minusWeeks((WEEKS.size - 1 - week).toLong()).plusDays(SLOT_DAY_OFFSETS[slot])
            val isHero = index == schedule.lastIndex
            // The hero is a hot afternoon ride (#222), so its drift advice says "start riding earlier".
            val slotStart = if (isHero) HERO_START_TIME else SLOT_START_TIMES[slot]
            val start = date.atTime(slotStart.plusMinutes(rng.nextInt(40).toLong()))
                .atZone(ZONE).toInstant()
            val track = tracks.getValue(loop)
            val plan = DemoRideModel.plan(track, loop, start, index / schedule.lastIndex.toDouble(), isHero, rng)
            val result = DemoRideModel.simulate(track, loop, plan, rng)
            DemoRide(
                index = index,
                loop = loop,
                start = start,
                fileName = "demo_${date}_${loop.id}.fit",
                plan = plan,
                samples = result.samples,
                timerEvents = result.timerEvents,
                fitBytes = DemoFitWriter.encode(result.samples, result.timerEvents)
            )
        }
    }

    private fun rideSeed(masterSeed: Long, index: Int): Long =
        masterSeed * 1_000_003L + index * -0x61c8864680b583ebL
}
