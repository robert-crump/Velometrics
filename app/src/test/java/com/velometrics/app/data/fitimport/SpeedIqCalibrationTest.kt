package com.velometrics.app.data.fitimport

import com.velometrics.app.domain.model.BrakingEvent
import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.domain.model.SpeedIq
import com.velometrics.app.domain.service.BrakingDetector
import com.velometrics.app.util.CyclingConstants
import io.mockk.mockk
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * #231: Speed IQ against the rider's real rides. The `.fit` files carry exact GPS tracks (home
 * included), so they live in the gitignored [FIXTURE_DIR] and are never committed; without them
 * every test here is skipped, so CI and fresh clones stay green.
 *
 * Optional `rider.properties` in the same folder sets `massKg` (rider + bike + kit) and `ftp`, as the
 * app's settings would at import; otherwise the import defaults apply.
 *
 * Writes `speed-iq-report.txt` next to the rides: every candidate event per ride, not just the top
 * ones, for the rider to check against what they remember of those spots.
 */
class SpeedIqCalibrationTest {

    private class Ride(val file: File, val prepared: PreparedRide, val speedIq: SpeedIq?)

    /** The import's stream up to the Speed IQ step: parse, GPS filter, power interpolation, timer pauses. */
    private class PreparedRide(val datapoints: List<Datapoint>, val hasPower: Boolean)

    companion object {
        private val FIXTURE_DIR = File("src/test/fixtures-local")
        private const val DESCENT_RIDE = "2026-10-03"
        private const val LONG_RIDE = "2026-09-05"
        private const val EVENING_RIDE = "2026-08-09"
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        private var cached: List<Ride>? = null
    }

    private val fitFiles: List<File>
        get() = FIXTURE_DIR.listFiles { f -> f.extension.equals("fit", ignoreCase = true) }.orEmpty().sortedBy { it.name }

    private val riderProperties: Properties
        get() = Properties().apply { File(FIXTURE_DIR, "rider.properties").takeIf { it.isFile }?.reader()?.use { load(it) } }

    private val massKg get() = riderProperties.getProperty("massKg")?.toDouble() ?: CyclingConstants.SPEED_IQ_DEFAULT_SYSTEM_MASS_KG
    private val ftp get() = riderProperties.getProperty("ftp")?.toInt() ?: CyclingConstants.DEFAULT_FTP

    private val rides: List<Ride> get() = cached ?: fitFiles.map(::analyze).also { cached = it; writeReport(it) }

    @Before
    fun requireFixtures() {
        assumeTrue("No .fit files in ${FIXTURE_DIR.absolutePath}; see #231", fitFiles.isNotEmpty())
    }

    @Test
    fun `every ride gets Speed IQ with elevation`() {
        rides.forEach {
            assertTrue(it.file.name, it.prepared.hasPower)
            assertNotNull(it.file.name, it.speedIq)
            assertTrue(it.file.name, it.speedIq!!.hasElevation)
        }
    }

    // Rider-confirmed spots (#231), pinned by ride and km only so no location ends up in git.

    @Test
    fun `the full stop after the fast descent is a top event worth 40 to 80 s of braking`() {
        val stop = event(DESCENT_RIDE, 18.53)
        assertTop(DESCENT_RIDE, stop)
        assertTrue("$stop", stop.penaltySec in 40.0..80.0)
        assertTrue("$stop", stop.peakKmh >= 50 && stop.lowKmh < CyclingConstants.SPEED_IQ_STANDING_KMH)
        assertTrue("$stop", stop.standingSec > 0)
    }

    @Test
    fun `the red light a few hundred metres after the descent is its own top event`() {
        val light = event(DESCENT_RIDE, 18.84)
        assertTop(DESCENT_RIDE, light)
        assertTrue("$light", light.standingSec in 25.0..40.0)
        assertTrue("$light", light.brakingEnergyJ > 0)
    }

    @Test
    fun `the same junction taken without stopping is still a top event, without standing`() {
        val junction = event(LONG_RIDE, 23.89)
        assertTop(LONG_RIDE, junction)
        assertTrue("$junction", junction.peakKmh >= 50 && junction.lowKmh > 10)
        assertEquals(0.0, junction.standingSec, 0.0)
    }

    @Test
    fun `hard braking before a T-junction at the foot of a descent is the long ride's biggest event`() {
        val junction = event(LONG_RIDE, 65.23)
        assertEquals(junction, ride(LONG_RIDE).speedIq!!.topEvents.maxBy { it.lostSec })
        assertTrue("$junction", junction.peakKmh >= 50)
    }

    @Test
    fun `stopping at a traffic light at the bottom of a hill counts braking and standing`() {
        val light = event(EVENING_RIDE, 0.71)
        assertTop(EVENING_RIDE, light)
        assertTrue("$light", light.penaltySec > 0 && light.standingSec > 0)
    }

    private fun ride(prefix: String): Ride {
        assumeTrue("No $prefix ride in ${FIXTURE_DIR.absolutePath}", fitFiles.any { it.name.startsWith(prefix) })
        return rides.first { it.file.name.startsWith(prefix) }
    }

    /** The ride's event nearest [km], at most 300 m away. */
    private fun event(prefix: String, km: Double): BrakingEvent {
        val events = ride(prefix).speedIq!!.topEvents
        return events.minBy { abs(it.km - km) }.also { assertTrue("$prefix: no event near km $km", abs(it.km - km) < 0.3) }
    }

    private fun assertTop(prefix: String, event: BrakingEvent) {
        val top = ride(prefix).speedIq!!.topEvents.sortedByDescending { it.lostSec }.take(CyclingConstants.SPEED_IQ_TOP_EVENTS)
        assertTrue("$prefix: $event not in top ${CyclingConstants.SPEED_IQ_TOP_EVENTS}", event in top)
    }

    // Mirrors FitImportServiceImpl.importFile steps 2-7 and SessionMetricsCalculator step 19.
    private fun analyze(file: File): Ride {
        val parse = importService().parseFitFile(file.readBytes())
        var datapoints = GpsQualityFilter.filter(parse.rawDatapoints).kept
        val hasPower = datapoints.count { it.power != null } >= datapoints.size * CyclingConstants.POWER_DATA_COVERAGE_THRESHOLD
        if (hasPower) datapoints = importService().interpolatePower(datapoints)
        val pauses = SessionMetricsCalculator().pauseIntervals(parse.timerEvents)
        val speedIq = if (hasPower) {
            BrakingDetector.analyze(datapoints, pauses, massKg, null, ftp, topEventCount = Int.MAX_VALUE)
        } else null
        return Ride(file, PreparedRide(datapoints, hasPower), speedIq)
    }

    private fun importService() = FitImportServiceImpl(
        mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk()
    )

    private fun writeReport(rides: List<Ride>) {
        val report = buildString {
            appendLine("Speed IQ candidate events (#231)  mass=%.1f kg  FTP=%d W  veto above %.0f W".format(
                massKg, ftp, ftp * CyclingConstants.SPEED_IQ_PEDAL_VETO_FTP_FRACTION))
            rides.forEach { ride ->
                appendLine()
                appendLine(ride.file.name)
                val iq = ride.speedIq
                if (iq == null) { appendLine("  no Speed IQ (hasPower=${ride.prepared.hasPower})"); return@forEach }
                val datapoints = ride.prepared.datapoints
                val cumulativeKm = HrDistanceSeriesBuilder.cumulativeMeters(datapoints).map { it / 1000.0 }
                appendLine("  %.1f km  P=%d W  events=%d  braking=%.0f s  standing=%.0f s (in timer %.0f s)".format(
                    cumulativeKm.last(), iq.referencePowerW, iq.eventCount, iq.brakingPenaltySec, iq.standingSec, iq.standingInTimerSec))
                val top = iq.topEvents.sortedByDescending { it.lostSec }.take(CyclingConstants.SPEED_IQ_TOP_EVENTS).toSet()
                appendLine("  top  time      km     peak->low km/h   E_brake  penalty  standing  lost    lat,lon")
                iq.topEvents.forEach { e ->
                    appendLine("  %-3s  %s  %5.2f  %5.1f -> %5.1f   %5.1f kJ  %5.1f s  %6.0f s  %5.1f s  %.5f,%.5f".format(
                        if (e in top) "*" else "", clockTime(e, datapoints.map { it.timestamp }, cumulativeKm),
                        e.km, e.peakKmh, e.lowKmh, e.brakingEnergyJ / 1000, e.penaltySec, e.standingSec, e.lostSec, e.lat, e.lon))
                }
            }
        }
        File(FIXTURE_DIR, "speed-iq-report.txt").writeText(report)
        println(report)
    }

    /** Local clock time of the record nearest the event's km. */
    private fun clockTime(event: BrakingEvent, timestamps: List<Instant>, cumulativeKm: List<Double>): String =
        TIME.format(timestamps[cumulativeKm.indices.minBy { abs(cumulativeKm[it] - event.km) }])
}
