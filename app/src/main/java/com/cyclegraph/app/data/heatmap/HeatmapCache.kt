package com.cyclegraph.app.data.heatmap

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class HeatmapCell(val lat: Double, val lon: Double, val count: Int)

/**
 * Spatial grid cache for the heatmap. GPS points are binned into ~55m cells
 * (0.0005° resolution) and aggregated by visit count, so the stored data is
 * far smaller than the raw per-session point sets.
 *
 * cellCounts: map from "${latBin}_${lonBin}" → visit count
 * sessionIds: set of session IDs whose points are already included
 */
data class HeatmapGrid(
    val cellCounts: Map<String, Int>,
    val sessionIds: Set<Long>
) {
    companion object {
        const val CELL_SIZE = 0.0005   // ~55 m per cell
        private const val CACHE_FILE = "heatmap_grid.json"
        private val gson = Gson()

        fun load(cacheDir: File): HeatmapGrid? {
            val file = File(cacheDir, CACHE_FILE)
            if (!file.exists()) return null
            return try {
                val type = object : TypeToken<HeatmapGridJson>() {}.type
                val json: HeatmapGridJson = gson.fromJson(file.readText(), type)
                HeatmapGrid(json.cellCounts, json.sessionIds.toSet())
            } catch (_: Exception) {
                null
            }
        }

        fun empty() = HeatmapGrid(emptyMap(), emptySet())
    }

    fun save(cacheDir: File) {
        try {
            val file = File(cacheDir, CACHE_FILE)
            file.writeText(gson.toJson(HeatmapGridJson(cellCounts, sessionIds.toList())))
        } catch (_: Exception) {}
    }

    fun toCells(): List<HeatmapCell> {
        return cellCounts.mapNotNull { (key, count) ->
            val parts = key.split("_")
            if (parts.size != 2) return@mapNotNull null
            val latBin = parts[0].toLongOrNull() ?: return@mapNotNull null
            val lonBin = parts[1].toLongOrNull() ?: return@mapNotNull null
            HeatmapCell(
                lat = latBin * CELL_SIZE + CELL_SIZE / 2,
                lon = lonBin * CELL_SIZE + CELL_SIZE / 2,
                count = count
            )
        }
    }
}

// Serialisation-only class to avoid Gson struggling with Set<Long>
private data class HeatmapGridJson(
    val cellCounts: Map<String, Int>,
    val sessionIds: List<Long>
)
