package com.velometrics.app.util

import com.velometrics.app.data.local.dao.CyclingSessionSummaryEntity
import com.velometrics.app.data.local.entity.*
import com.velometrics.app.domain.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant

private val gson = Gson()

fun CorridorEntity.toDomain(): Corridor {
    val listType = object : TypeToken<List<List<Long>>>() {}.type
    val edges: List<List<Long>> = gson.fromJson(edgeList, listType)
    return Corridor(
        id = id,
        entryNode = entryNode,
        exitNode = exitNode,
        lengthM = lengthM,
        pedalReward = pedalReward,
        gravityReward = gravityReward,
        exitHazardScore = exitHazardScore,
        centroidLat = centroidLat,
        centroidLon = centroidLon,
        edgeList = edges.map { Pair(it[0], it[1]) },
        popularity = popularity,
        groupId = groupId,
    )
}

// CyclingSession mappers
fun CyclingSessionEntity.toDomain(): CyclingSession {
    val mapType = object : TypeToken<Map<String, Int>>() {}.type
    val powerZoneDist: Map<String, Int>? = powerZoneDistribution?.let { gson.fromJson(it, mapType) }
    val speedHist: Map<String, Int> = gson.fromJson(speedHistogram, mapType)
    val fatEffHist: Map<String, Int>? = fatEfficiencyHistogram?.let { gson.fromJson(it, mapType) }
    val sprintHist: Map<String, Int>? = sprintHistogram?.let { gson.fromJson(it, mapType) }
    val hrZoneDist: Map<String, Int>? = hrZoneDistribution?.let { gson.fromJson(it, mapType) }

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
        hrZoneDistribution = hrZoneDist
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
        powerZoneDistribution = powerZoneDistribution?.let { gson.toJson(it) },
        speedHistogram = gson.toJson(speedHistogram),
        intervalCount = intervalCount,
        intervalTotalTimeSec = intervalTotalTimeSec,
        gpsQualityPercent = gpsQualityPercent,
        powerQualityPercent = powerQualityPercent,
        hasPower = hasPower,
        gpsTrack = gpsTrack,
        fatEfficiencyHistogram = fatEfficiencyHistogram?.let { gson.toJson(it) },
        fatEfficiencyScore = fatEfficiencyScore,
        sprintCount = sprintCount,
        sprintHistogram = sprintHistogram?.let { gson.toJson(it) },
        avgHeartRate = avgHeartRate,
        elevationGainM = elevationGainM,
        hrZoneDistribution = hrZoneDistribution?.let { gson.toJson(it) }
    )
}

fun CyclingSessionSummaryEntity.toDomain(): CyclingSessionSummary {
    return CyclingSessionSummary(
        id = id,
        sessionStart = Instant.ofEpochMilli(sessionStart),
        distanceKm = distanceKm,
        netDurationSec = netDurationSec,
        averagePower = averagePower,
        hasPower = hasPower
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
