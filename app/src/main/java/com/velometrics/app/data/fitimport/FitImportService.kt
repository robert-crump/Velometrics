package com.velometrics.app.data.fitimport

import android.util.Log
import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.domain.service.IntervalDetector
import com.velometrics.app.domain.service.IntervalMatcher
import com.velometrics.app.domain.service.SprintDetector
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.GeoUtils
import com.garmin.fit.Decode
import com.garmin.fit.Event
import com.garmin.fit.EventMesgListener
import com.garmin.fit.EventType
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesgListener
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlin.math.atan2
import kotlin.math.sqrt

@Singleton
class FitImportService @Inject constructor(
    private val sessionRepository: CyclingSessionRepository,
    private val metricsCalculator: SessionMetricsCalculator,
    private val intervalDetector: IntervalDetector,
    private val intervalMatcher: IntervalMatcher,
    private val intervalRepository: IntervalRepository,
    private val sprintDetector: SprintDetector,
    private val userSettingsRepository: UserSettingsRepository
) {

    companion object {
        private const val TAG = "FitImportService"
    }

    suspend fun importFile(fileName: String, bytes: ByteArray, forceImport: Boolean = false): ImportResult {
        return try {
            // Read user FTP setting for this import
            val ftp = userSettingsRepository.ftp.first()
            val maxHr = userSettingsRepository.maxHr.first()

            // 1. SHA-1 hash and duplicate check
            val fileSha1 = sha1Hex(bytes)
            if (sessionRepository.existsBySha1(fileSha1)) {
                return ImportResult.AlreadyImported(fileName)
            }

            // 2. Parse FIT file
            val parseResult = parseFitFile(bytes)

            if (parseResult.rawDatapoints.isEmpty()) {
                return ImportResult.Error("No GPS data found in $fileName")
            }

            // 3. Build datapoint list (records with valid lat AND lon already filtered in parse)
            var datapoints = parseResult.rawDatapoints

            Log.d(TAG, "$fileName: ${parseResult.totalRecordCount} total records, ${datapoints.size} with GPS")

            // 4. GPS quality filter
            datapoints = filterGpsQuality(datapoints)

            Log.d(TAG, "$fileName: ${datapoints.size} points after GPS filter")

            if (datapoints.isEmpty()) {
                return ImportResult.Error("All GPS data filtered out for $fileName")
            }

            // 4b. Small-file guard (fewer than MINIMUM_GPS_DATAPOINTS after quality filter)
            if (datapoints.size < CyclingConstants.MINIMUM_GPS_DATAPOINTS && !forceImport) {
                return ImportResult.SmallFile(fileName, datapoints.size)
            }

            // 5. Compute vectors/angles
            datapoints = computeVectorsAndAngles(datapoints)

            // 6. Determine hasPower
            val originalPowerCount = datapoints.count { it.power != null }
            val hasPower = originalPowerCount >= (datapoints.size * CyclingConstants.POWER_DATA_COVERAGE_THRESHOLD)

            Log.d(TAG, "$fileName: hasPower=$hasPower (${originalPowerCount}/${datapoints.size} = ${
                if (datapoints.isNotEmpty()) (originalPowerCount * 100 / datapoints.size) else 0
            }%)")

            // 7. Interpolate power
            if (hasPower) {
                datapoints = interpolatePower(datapoints)
            }

            // 8. Compute metrics
            val session = metricsCalculator.compute(
                fileName = fileName,
                fileSha1 = fileSha1,
                datapoints = datapoints,
                hasPower = hasPower,
                timerEvents = parseResult.timerEvents,
                rawRecordCount = parseResult.totalRecordCount,
                originalPowerCount = originalPowerCount,
                ftp = ftp,
                maxHr = maxHr
            )

            Log.d(TAG, "$fileName: dist=${session.distanceKm}km, duration=${session.netDurationSec}s, " +
                    "avgPower=${session.averagePower}W, np=${session.normalizedPower}W")

            // 9. Persist
            val id = sessionRepository.insertSession(session)

            // 10. Interval detection & matching (power rides only)
            var intervalCount = 0
            var intervalTotalSec = 0
            var sprintCount = 0
            var sprintHistogram: Map<String, Int>? = null
            if (hasPower) {
                val intervals = intervalDetector.detect(datapoints, id, ftp)
                if (intervals.isNotEmpty()) {
                    val insertedIds = intervalRepository.insertIntervals(intervals)
                    val persisted = intervals.zip(insertedIds) { interval, insertedId -> interval.copy(id = insertedId) }
                    intervalMatcher.matchToRepeatedIntervals(persisted)
                    intervalCount = intervals.size
                    intervalTotalSec = intervals.sumOf { it.durationSec }
                    sessionRepository.updateIntervalStats(id, intervalCount, intervalTotalSec)
                }
                Log.d(TAG, "$fileName: detected $intervalCount intervals (${intervalTotalSec}s total)")

                val sprints = sprintDetector.detect(datapoints, ftp)
                sprintCount = sprints.size
                if (sprints.isNotEmpty()) {
                    sprintHistogram = sprintDetector.buildHistogram(sprints)
                }
                Log.d(TAG, "$fileName: detected $sprintCount sprints")
            }

            // Update sprint data on the session
            if (sprintCount > 0) {
                val updatedSession = sessionRepository.getSessionById(id)
                if (updatedSession != null) {
                    sessionRepository.updateSession(
                        updatedSession.copy(sprintCount = sprintCount, sprintHistogram = sprintHistogram)
                    )
                }
            }

            val summary = buildString {
                append("%.1f km".format(session.distanceKm))
                append(" | ${formatDuration(session.netDurationSec)}")
                if (session.hasPower && session.averagePower != null) {
                    append(" | ${session.averagePower} W")
                }
                if (intervalCount > 0) {
                    append(" | $intervalCount intervals")
                }
            }

            ImportResult.Success(id, summary)

        } catch (e: FitRuntimeException) {
            Log.e(TAG, "FIT parse error for $fileName", e)
            ImportResult.Error("FIT parse error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Import error for $fileName", e)
            ImportResult.Error("Import error: ${e.message}")
        }
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class ParseResult(
        val rawDatapoints: List<Datapoint>,
        val timerEvents: List<TimerEvent>,
        val totalRecordCount: Int
    )

    private fun parseFitFile(bytes: ByteArray): ParseResult {
        val datapoints = mutableListOf<Datapoint>()
        val timerEvents = mutableListOf<TimerEvent>()
        var totalRecordCount = 0

        val decode = Decode()
        val broadcaster = MesgBroadcaster(decode)

        broadcaster.addListener(RecordMesgListener { mesg ->
            totalRecordCount++

            val posLat: Int? = mesg.getPositionLat()
            val posLon: Int? = mesg.getPositionLong()

            if (posLat == null || posLon == null) return@RecordMesgListener

            val lat = GeoUtils.semicirclesToDeg(posLat.toLong())
            val lon = GeoUtils.semicirclesToDeg(posLon.toLong())

            val speedMps: Float? = mesg.getSpeed()
            val speedKmh = speedMps?.let { it * CyclingConstants.MTS_PER_SEC_TO_KMH }

            val power: Int? = mesg.getPower()
            val heartRate: Int? = mesg.getHeartRate()?.toInt()
            val altitude: Double? = (mesg.getEnhancedAltitude() ?: mesg.getAltitude())?.toDouble()

            val fitTimestamp = mesg.getTimestamp() ?: return@RecordMesgListener
            val timestamp = fitTimestamp.date.toInstant()

            datapoints.add(
                Datapoint(
                    lat = lat,
                    lon = lon,
                    speedKmh = speedKmh?.toDouble(),
                    power = power,
                    timestamp = timestamp,
                    heartRate = heartRate,
                    altitude = altitude
                )
            )
        })

        broadcaster.addListener(EventMesgListener { mesg ->
            if (mesg.event == Event.TIMER) {
                val fitTimestamp = mesg.getTimestamp() ?: return@EventMesgListener
                val timestamp = fitTimestamp.date.toInstant()

                val eventTypeStr = when (mesg.eventType) {
                    EventType.START -> "start"
                    EventType.STOP -> "stop"
                    EventType.STOP_ALL -> "stop_all"
                    else -> return@EventMesgListener
                }

                timerEvents.add(TimerEvent(timestamp, eventTypeStr))
            }
        })

        decode.read(ByteArrayInputStream(bytes), broadcaster, broadcaster)

        return ParseResult(datapoints, timerEvents, totalRecordCount)
    }

    private fun filterGpsQuality(datapoints: List<Datapoint>): List<Datapoint> {
        if (datapoints.isEmpty()) return emptyList()

        val filtered = mutableListOf<Datapoint>()
        var discardSpeed = 0
        var discardPower = 0
        var discardZero = 0
        var discardLeap = 0
        var discardImplied = 0

        var lastValid: Datapoint? = null

        for (dp in datapoints) {
            // Discard zero lat/lon
            if (dp.lat == 0.0 || dp.lon == 0.0) {
                discardZero++
                continue
            }

            // Discard unrealistic speed
            if (dp.speedKmh != null && dp.speedKmh > CyclingConstants.MAX_REALISTIC_SPEED_KMH) {
                discardSpeed++
                continue
            }

            // Discard unrealistic power
            if (dp.power != null && dp.power > CyclingConstants.MAX_REALISTIC_POWER) {
                discardPower++
                continue
            }

            // Check against previous valid point
            if (lastValid != null) {
                val distM = GeoUtils.haversineDistance(lastValid.lat, lastValid.lon, dp.lat, dp.lon)
                val elapsedSec = Duration.between(lastValid.timestamp, dp.timestamp).seconds.toDouble()

                // Discard GPS leap: distance > 500m AND elapsed < 5s
                if (distM > CyclingConstants.GPS_LEAP_MAX_DISTANCE_M &&
                    elapsedSec < CyclingConstants.GPS_LEAP_MAX_TIME_SEC) {
                    discardLeap++
                    continue
                }

                // Discard if implied speed > 120 km/h
                if (elapsedSec > 0) {
                    val impliedSpeedKmh = (distM / elapsedSec) * CyclingConstants.MTS_PER_SEC_TO_KMH
                    if (impliedSpeedKmh > CyclingConstants.GPS_IMPLIED_MAX_SPEED_KMH) {
                        discardImplied++
                        continue
                    }
                }
            }

            filtered.add(dp)
            lastValid = dp
        }

        Log.d(TAG, "GPS filter: kept=${filtered.size}, discardZero=$discardZero, " +
                "discardSpeed=$discardSpeed, discardPower=$discardPower, " +
                "discardLeap=$discardLeap, discardImplied=$discardImplied")

        return filtered
    }

    private fun computeVectorsAndAngles(datapoints: List<Datapoint>): List<Datapoint> {
        if (datapoints.size < 2) return datapoints

        val result = mutableListOf(datapoints.first())

        for (i in 1 until datapoints.size) {
            val prev = datapoints[i - 1]
            val curr = datapoints[i]

            val dxMeters = (curr.lon - prev.lon) * GeoUtils.metersPerDegLon(curr.lat)
            val dyMeters = (curr.lat - prev.lat) * GeoUtils.METERS_PER_DEG_LAT
            val displacement = sqrt(dxMeters * dxMeters + dyMeters * dyMeters)

            if (displacement < 0.5) {
                result.add(curr)
                continue
            }

            val angleRad = atan2(dxMeters, dyMeters)
            var angleDeg = Math.toDegrees(angleRad)
            if (angleDeg < 0) angleDeg += 360.0

            result.add(
                curr.copy(
                    vectorX = dxMeters.toFloat(),
                    vectorY = dyMeters.toFloat(),
                    angleDeg = angleDeg
                )
            )
        }

        return result
    }

    private fun interpolatePower(datapoints: List<Datapoint>): List<Datapoint> {
        if (datapoints.isEmpty()) return datapoints

        val result = datapoints.toMutableList()

        // Find indices with known power
        val knownIndices = mutableListOf<Int>()
        for (i in result.indices) {
            if (result[i].power != null) {
                knownIndices.add(i)
            }
        }

        if (knownIndices.isEmpty()) return result

        // Forward-fill start gap (before first known value)
        val firstKnown = knownIndices.first()
        val firstPower = result[firstKnown].power!!
        for (i in 0 until firstKnown) {
            result[i] = result[i].copy(power = firstPower)
        }

        // Backward-fill end gap (after last known value)
        val lastKnown = knownIndices.last()
        val lastPower = result[lastKnown].power!!
        for (i in (lastKnown + 1) until result.size) {
            result[i] = result[i].copy(power = lastPower)
        }

        // Linear interpolation between known values
        for (k in 0 until knownIndices.size - 1) {
            val startIdx = knownIndices[k]
            val endIdx = knownIndices[k + 1]

            if (endIdx - startIdx <= 1) continue

            val startPower = result[startIdx].power!!.toDouble()
            val endPower = result[endIdx].power!!.toDouble()
            val span = endIdx - startIdx

            for (i in (startIdx + 1) until endIdx) {
                val fraction = (i - startIdx).toDouble() / span
                val interpolated = (startPower + fraction * (endPower - startPower)).toInt()
                result[i] = result[i].copy(power = interpolated)
            }
        }

        return result
    }

    private fun formatDuration(totalSec: Int): String {
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
