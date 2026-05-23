package com.cyclegraph.app.domain.service

import com.cyclegraph.app.data.heatmap.HeatmapCell

fun interface HeatmapService {
    suspend fun getOrUpdateHeatmap(): List<HeatmapCell>
}
