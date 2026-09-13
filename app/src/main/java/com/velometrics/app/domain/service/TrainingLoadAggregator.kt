package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.DailyTrainingLoadPoint
import com.velometrics.app.domain.model.TrainingLoadUiState
import com.velometrics.app.util.CyclingConstants
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure computation of [TrainingLoadUiState] (#190) — a CTL/ATL/TSB fitness-fatigue trend, in the
 * style of TrainingPeaks' Performance Management Chart / Strava's Fitness & Freshness — from raw
 * sessions plus the rider's current FTP, mirroring [AllTimeStatsAggregator]'s split from its
 * cache so the reactive computation is shared across ViewModel instances.
 *
 * FTP is applied as a single current value across all of history (no per-ride FTP snapshot is
 * stored anywhere in this app), so changing FTP in Settings reshapes the entire historical
 * chart on the next recompute — this is a deliberate simplification (#190 grill-me), not a bug.
 */
object TrainingLoadAggregator {

    fun buildUiState(sessions: List<CyclingSession>, ftp: Int): TrainingLoadUiState {
        if (sessions.isEmpty()) {
            return TrainingLoadUiState(isLoading = false, hasAnySessions = false)
        }

        val loadByDay = sessions
            .groupingBy { it.sessionStart.atZone(ZoneId.systemDefault()).toLocalDate() }
            .fold(0.0) { acc, session -> acc + rideLoad(session, ftp) }

        val firstDay = loadByDay.keys.min()
        val today = LocalDate.now(ZoneId.systemDefault())

        val points = mutableListOf<DailyTrainingLoadPoint>()
        var ctl = 0.0
        var atl = 0.0
        var date = firstDay
        while (!date.isAfter(today)) {
            val load = loadByDay[date] ?: 0.0
            // Form going into today, i.e. before today's own load is folded into ctl/atl below.
            val tsb = ctl - atl
            ctl += (load - ctl) / CyclingConstants.CTL_TIME_CONSTANT_DAYS
            atl += (load - atl) / CyclingConstants.ATL_TIME_CONSTANT_DAYS
            points.add(DailyTrainingLoadPoint(date = date, load = load, ctl = ctl, atl = atl, tsb = tsb))
            date = date.plusDays(1)
        }

        val last = points.last()
        return TrainingLoadUiState(
            isLoading = false,
            hasAnySessions = true,
            currentCtl = last.ctl,
            currentAtl = last.atl,
            currentTsb = last.tsb,
            chartPoints = points.takeLast(CyclingConstants.TRAINING_LOAD_CHART_WINDOW_DAYS)
        )
    }

    /**
     * A single ride's training-load contribution: power-based TSS when available, an HR-zone
     * weighted approximation when there's HR but no power, otherwise 0 (the ride still
     * participates in day-bucketing, it just contributes no load).
     */
    private fun rideLoad(session: CyclingSession, ftp: Int): Double {
        val normalizedPower = session.normalizedPower
        if (session.hasPower && normalizedPower != null && ftp > 0) {
            val intensityFactor = normalizedPower.toDouble() / ftp
            return intensityFactor * intensityFactor * (session.netDurationSec / 3600.0) * 100.0
        }

        val hrZones = session.hrZoneDistribution
        if (!session.hasPower && session.hasHR && hrZones != null && hrZones.isNotEmpty()) {
            return hrLoad(hrZones, session.netDurationSec)
        }

        return 0.0
    }

    /**
     * hrZoneDistribution stores raw per-datapoint sample counts (see
     * SessionMetricsCalculator.computeHrZones), not seconds directly — derive seconds-per-sample
     * from netDurationSec/totalSamples rather than assuming a 1Hz recording rate, even though
     * that's what FIT imports normally produce.
     */
    private fun hrLoad(hrZones: Map<String, Int>, netDurationSec: Int): Double {
        val totalSamples = hrZones.values.sum()
        if (totalSamples <= 0) return 0.0
        val secondsPerSample = netDurationSec.toDouble() / totalSamples

        val weightedMinutes = hrZones.entries.sumOf { (zone, count) ->
            val multiplier = CyclingConstants.HR_LOAD_ZONE_MULTIPLIERS[zone] ?: 0.0
            (count * secondsPerSample / 60.0) * multiplier
        }
        return weightedMinutes * CyclingConstants.HR_LOAD_CALIBRATION_CONSTANT
    }
}
