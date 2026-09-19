package com.velometrics.app.fakes

import com.velometrics.app.domain.model.CyclingSession
import java.time.Instant

fun testSession(
    id: Long,
    start: Instant,
    distanceKm: Double = 30.0,
    elevationGainM: Double? = 250.0
): CyclingSession = CyclingSession(
    id = id,
    fileName = "ride_$id.fit",
    fileSha1 = "sha1_$id",
    sessionStart = start,
    sessionEnd = start.plusSeconds(3600),
    totalDurationSec = 3600,
    pauseDurationSec = 0,
    netDurationSec = 3600,
    distanceKm = distanceKm,
    averagePower = null,
    normalizedPower = null,
    fatBurnedGrams = null,
    carbsBurnedGrams = null,
    powerZoneDistribution = null,
    speedHistogram = emptyMap(),
    intervalCount = 0,
    intervalTotalTimeSec = 0,
    gpsQualityPercent = 95.0,
    powerQualityPercent = null,
    hasPower = false,
    gpsTrack = null,
    elevationGainM = elevationGainM
)
