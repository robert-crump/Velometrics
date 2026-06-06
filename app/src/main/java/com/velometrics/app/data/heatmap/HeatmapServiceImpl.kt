package com.velometrics.app.data.heatmap

import android.content.Context
import com.velometrics.app.data.local.dao.CyclingSessionDao
import com.velometrics.app.domain.service.HeatmapService
import com.velometrics.app.util.GpsTrackParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor

@Singleton
class HeatmapServiceImpl @Inject constructor(
    private val sessionDao: CyclingSessionDao,
    @ApplicationContext private val context: Context
) : HeatmapService {

    override suspend fun getOrUpdateHeatmap(): List<HeatmapCell> = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val cached = HeatmapGrid.load(cacheDir) ?: HeatmapGrid.empty()

        val allIdAndTracks = sessionDao.getAllIdsAndTracks()
        val newSessions = allIdAndTracks.filter { it.id !in cached.sessionIds }

        if (newSessions.isEmpty()) {
            return@withContext cached.toCells()
        }

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
        val latBin = floor(lat / HeatmapGrid.CELL_SIZE).toLong()
        val lonBin = floor(lon / HeatmapGrid.CELL_SIZE).toLong()
        return "${latBin}_${lonBin}"
    }
}
