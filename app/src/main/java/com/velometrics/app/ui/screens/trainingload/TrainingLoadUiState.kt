package com.velometrics.app.ui.screens.trainingload

import java.time.LocalDate

// One calendar day's training-load contribution plus the rolling CTL/ATL/TSB values as of that
// day. [tsb] is "form going into [date]" — computed from the *previous* day's ctl/atl, before
// [date]'s own [load] is folded into ctl/atl below it in the series.
data class DailyTrainingLoadPoint(
    val date: LocalDate,
    val load: Double,
    val ctl: Double,
    val atl: Double,
    val tsb: Double
)

data class TrainingLoadUiState(
    val isLoading: Boolean = true,
    val hasAnySessions: Boolean = false,
    val currentCtl: Double = 0.0,
    val currentAtl: Double = 0.0,
    val currentTsb: Double = 0.0,
    // Windowed to CyclingConstants.TRAINING_LOAD_CHART_WINDOW_DAYS for charting; currentCtl/
    // currentAtl/currentTsb above are always derived from the full unwindowed series.
    val chartPoints: List<DailyTrainingLoadPoint> = emptyList()
)
