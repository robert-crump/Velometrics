package com.velometrics.app.domain.model

import java.time.Instant

data class CyclingSession(
    val id: Long = 0,
    val fileName: String,
    val fileSha1: String,
    val sessionStart: Instant,
    val sessionEnd: Instant,
    val totalDurationSec: Int,
    val pauseDurationSec: Int,
    val netDurationSec: Int,
    val distanceKm: Double,
    val averagePower: Int?,
    val normalizedPower: Int?,
    val fatBurnedGrams: Double?,
    val carbsBurnedGrams: Double?,
    val powerZoneDistribution: Map<String, Int>?,
    val speedHistogram: Map<String, Int>,
    val intervalCount: Int,
    val intervalTotalTimeSec: Int,
    val gpsQualityPercent: Double,
    val powerQualityPercent: Double?,
    val hasPower: Boolean,
    val gpsTrack: String? = null,
    val fatEfficiencyHistogram: Map<String, Int>? = null,
    val fatEfficiencyScore: Int? = null,
    val sprintCount: Int = 0,
    val sprintHistogram: Map<String, Int>? = null,
    val avgHeartRate: Int? = null,
    val elevationGainM: Double? = null,
    val hrZoneDistribution: Map<String, Int>? = null,
    // Bucket index (as String) -> % of first-half baseline efficiency factor. A bucket with more
    // than half its samples excluded (power < 25% FTP) is dropped entirely, i.e. absent from this
    // map — the chart renders that as a gap, not a zero.
    val cardiacDriftBuckets: Map<String, Double>? = null,
    val cardiacDriftPercent: Double? = null,
    // Rule-based classification (#169): Zone 2 / Intervals / Race / Recovery / Endurance,
    // computed at import time by RideClassifier and backfilled onto older rides by
    // RideClassificationService.reclassifyAll().
    val tag: String? = null,
    // Seconds spent with power below CyclingConstants.RECOVERY_TIME_BELOW_FTP_FRACTION (60%) of
    // FTP, power-having rides only. Computed once at import time from raw per-second power (no
    // backfill path for pre-existing rides, same as fatEfficiencyScore/cardiacDriftPercent) — the
    // metric the Recovery tag-comparison narrative leads with.
    val timeBelowSixtyPercentFtpSec: Int? = null,
    // Coverage flag for HR data quality (#178), mirroring hasPower's coverage-threshold pattern.
    // Informational only -- does not gate per-interval HRR computation in IntervalDetector.
    val hasHR: Boolean = false
)
