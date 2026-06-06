package com.velometrics.app.domain.service

import com.velometrics.app.data.heatmap.HeatmapCell

fun interface HeatmapService {
    suspend fun getOrUpdateHeatmap(): List<HeatmapCell>
}
