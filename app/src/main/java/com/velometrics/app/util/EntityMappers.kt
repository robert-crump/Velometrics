package com.velometrics.app.util

import com.velometrics.app.data.local.dao.CyclingSessionSummaryEntity
import com.velometrics.app.data.local.dao.SessionMetricSampleEntity
import com.velometrics.app.data.local.entity.*
import com.velometrics.app.domain.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant

@PublishedApi
internal val gson = Gson()

/** Deserializes this JSON string as [T]. */
inline fun <reified T> String.parseJson(): T = gson.fromJson(this, object : TypeToken<T>() {}.type)

/** Deserializes this JSON string as [T], or `null` if the string itself is `null`. */
inline fun <reified T> String?.parseJsonOrNull(): T? =
    this?.let { gson.fromJson(it, object : TypeToken<T>() {}.type) }

/** Serializes this value to a JSON string. */
inline fun <reified T> T.toJsonString(): String = gson.toJson(this)

// CyclingSession mappers
fun CyclingSessionEntity.toDomain(): CyclingSession {
    val powerZoneDist: Map<String, Int>? = powerZoneDistribution.parseJsonOrNull()
    val speedHist: Map<String, Int> = speedHistogram.parseJson()
    val fatEffHist: Map<String, Int>? = fatEfficiencyHistogram.parseJsonOrNull()
    val sprintHist: Map<String, Int>? = sprintHistogram.parseJsonOrNull()
    val hrZoneDist: Map<String, Int>? = hrZoneDistribution.parseJsonOrNull()
    val cardiacDrift: Map<String, Double>? = cardiacDriftBuckets.parseJsonOrNull()

    return CyclingSession(
        id = id,
        fileName = fileName,
        fileSha1 = fileSha1,
        sessionStart = Instant.ofEpochMilli(sessionStart),
        sessionEnd = Instant.ofEpochMilli(sessionEnd),
        totalDurationSec = totalDurationSec,
        pauseDurationSec = pauseDurationSec,
        netDurationSec = netDurationSec,
        distanceKm = distanceKm,
        averagePower = averagePower,
        normalizedPower = normalizedPower,
        fatBurnedGrams = fatBurnedGrams,
        carbsBurnedGrams = carbsBurnedGrams,
        powerZoneDistribution = powerZoneDist,
        speedHistogram = speedHist,
        intervalCount = intervalCount,
        intervalTotalTimeSec = intervalTotalTimeSec,
        gpsQualityPercent = gpsQualityPercent,
        powerQualityPercent = powerQualityPercent,
        hasPower = hasPower,
        gpsTrack = gpsTrack,
        fatEfficiencyHistogram = fatEffHist,
        fatEfficiencyScore = fatEfficiencyScore,
        sprintCount = sprintCount,
        sprintHistogram = sprintHist,
        avgHeartRate = avgHeartRate,
        elevationGainM = elevationGainM,
        hrZoneDistribution = hrZoneDist,
        cardiacDriftBuckets = cardiacDrift,
        cardiacDriftPercent = cardiacDriftPercent,
        tag = tag,
        timeBelowSixtyPercentFtpSec = timeBelowSixtyPercentFtpSec
    )
}

fun CyclingSession.toEntity(): CyclingSessionEntity {
    return CyclingSessionEntity(
        id = id,
        fileName = fileName,
        fileSha1 = fileSha1,
        sessionStart = sessionStart.toEpochMilli(),
        sessionEnd = sessionEnd.toEpochMilli(),
        totalDurationSec = totalDurationSec,
        pauseDurationSec = pauseDurationSec,
        netDurationSec = netDurationSec,
        distanceKm = distanceKm,
        averagePower = averagePower,
        normalizedPower = normalizedPower,
        fatBurnedGrams = fatBurnedGrams,
        carbsBurnedGrams = carbsBurnedGrams,
        powerZoneDistribution = powerZoneDistribution?.toJsonString(),
        speedHistogram = speedHistogram.toJsonString(),
        intervalCount = intervalCount,
        intervalTotalTimeSec = intervalTotalTimeSec,
        gpsQualityPercent = gpsQualityPercent,
        powerQualityPercent = powerQualityPercent,
        hasPower = hasPower,
        gpsTrack = gpsTrack,
        fatEfficiencyHistogram = fatEfficiencyHistogram?.toJsonString(),
        fatEfficiencyScore = fatEfficiencyScore,
        sprintCount = sprintCount,
        sprintHistogram = sprintHistogram?.toJsonString(),
        avgHeartRate = avgHeartRate,
        elevationGainM = elevationGainM,
        hrZoneDistribution = hrZoneDistribution?.toJsonString(),
        cardiacDriftBuckets = cardiacDriftBuckets?.toJsonString(),
        cardiacDriftPercent = cardiacDriftPercent,
        tag = tag,
        timeBelowSixtyPercentFtpSec = timeBelowSixtyPercentFtpSec
    )
}

fun SessionMetricSampleEntity.toDomain(): SessionMetricSample {
    return SessionMetricSample(
        id = id,
        netDurationSec = netDurationSec,
        distanceKm = distanceKm,
        averagePower = averagePower,
        normalizedPower = normalizedPower,
        fatEfficiencyScore = fatEfficiencyScore,
        avgHeartRate = avgHeartRate,
        elevationGainM = elevationGainM,
        fatBurnedGrams = fatBurnedGrams,
        carbsBurnedGrams = carbsBurnedGrams,
        cardiacDriftPercent = cardiacDriftPercent,
        hasPower = hasPower,
        intervalCount = intervalCount,
        timeBelowSixtyPercentFtpSec = timeBelowSixtyPercentFtpSec
    )
}

fun CyclingSessionSummaryEntity.toDomain(): CyclingSessionSummary {
    return CyclingSessionSummary(
        id = id,
        sessionStart = Instant.ofEpochMilli(sessionStart),
        distanceKm = distanceKm,
        netDurationSec = netDurationSec,
        averagePower = averagePower,
        hasPower = hasPower,
        tag = tag
    )
}

// IntervalSession mappers
fun IntervalSessionEntity.toDomain(): IntervalSession {
    return IntervalSession(
        id = id,
        cyclingSessionId = cyclingSessionId,
        startTimestamp = Instant.ofEpochMilli(startTimestamp),
        durationSec = durationSec,
        durationNormalizedSec = durationNormalizedSec,
        distanceM = distanceM,
        avgPower = avgPower,
        avgSpeedKmh = avgSpeedKmh,
        avgSpeedNormalizedKmh = avgSpeedNormalizedKmh,
        direction = direction,
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon,
        gpsTrack = gpsTrack
    )
}

fun IntervalSession.toEntity(): IntervalSessionEntity {
    return IntervalSessionEntity(
        id = id,
        cyclingSessionId = cyclingSessionId,
        startTimestamp = startTimestamp.toEpochMilli(),
        durationSec = durationSec,
        durationNormalizedSec = durationNormalizedSec,
        distanceM = distanceM,
        avgPower = avgPower,
        avgSpeedKmh = avgSpeedKmh,
        avgSpeedNormalizedKmh = avgSpeedNormalizedKmh,
        direction = direction,
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon,
        gpsTrack = gpsTrack
    )
}
