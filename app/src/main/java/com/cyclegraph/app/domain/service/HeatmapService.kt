package com.cyclegraph.app.domain.service

import android.content.Context
import com.cyclegraph.app.data.heatmap.HeatmapCell
import com.cyclegraph.app.data.heatmap.HeatmapGrid
import com.cyclegraph.app.data.heatmap.HeatmapGrid.Companion.CELL_SIZE
import com.cyclegraph.app.data.local.dao.CyclingSessionDao
import com.cyclegraph.app.util.GpsTrackParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor

@Singleton
class HeatmapService @Inject constructor(
    private val sessionDao: CyclingSessionDao,
    @ApplicationContext private val context: Context
) {
    /**
     * Returns heatmap cells from the incremental grid cache, rebuilding only
     * for sessions that have been added since the last call.
     *
     * Must be called on a coroutine — all heavy work runs on Dispatchers.IO.
     */
    suspend fun getOrUpdateHeatmap(): List<HeatmapCell> = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val cached = HeatmapGrid.load(cacheDir) ?: HeatmapGrid.empty()

        val allIdAndTracks = sessionDao.getAllIdsAndTracks()
        val newSessions = allIdAndTracks.filter { it.id !in cached.sessionIds }

        if (newSessions.isEmpty()) {
            return@withContext cached.toCells()
        }

        // Merge new session points into the existing cell counts
        val mergedCounts = cached.cellCounts.toMutableMap()
        for (session in newSessions) {
            val points = GpsTrackParser.parse(session.gpsTrack)
            for (point in points) {
                val key = cellKey(point.latitude, point.longitude)
                mergedCounts[key] = (mergedCounts[key] ?: 0) + 1
            }
        }

        val updatedGrid = HeatmapGrid(
            cellCounts = mergedCounts,
            sessionIds = cached.sessionIds + newSessions.map { it.id }.toSet()
        )
        updatedGrid.save(cacheDir)
        updatedGrid.toCells()
    }

    private fun cellKey(lat: Double, lon: Double): String {
        val latBin = floor(lat / CELL_SIZE).toLong()
        val lonBin = floor(lon / CELL_SIZE).toLong()
        return "${latBin}_${lonBin}"
    }
}
