package com.velometrics.app.data.repository

import com.velometrics.app.data.local.dao.RepeatedIntervalDao
import com.velometrics.app.data.local.entity.RepeatedIntervalEntity
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepeatedIntervalRepositoryImpl @Inject constructor(
    private val dao: RepeatedIntervalDao,
    private val intervalRepository: IntervalRepository,
    private val mapGraphRepository: MapGraphRepository
) : RepeatedIntervalRepository {

    companion object { private const val TAG = "RepeatedIntervalRepo" }

    private val gson = Gson()
    private val listLongType = object : TypeToken<List<Long>>() {}.type
    private val edgeRefListType = object : TypeToken<List<List<Long>>>() {}.type

    override fun getAllRepeatedIntervals(): Flow<List<RepeatedInterval>> {
        return combine(
            dao.getAll(),
            intervalRepository.getAllIntervals()
        ) { entities, intervals ->
            val intervalMap = intervals.associateBy { it.id }
            if (entities.isEmpty()) return@combine emptyList()
            val allEdgeRefs = entities.flatMap { parseEdgeRefs(it.edges) }.distinct()
            val edgeMap = mapGraphRepository.getEdgesByNodePairs(allEdgeRefs).associateBy { it.fromNode to it.toNode }
            entities.mapNotNull { entity -> entityToDomain(entity, intervalMap, edgeMap) }
        }
    }

    override fun getRepeatedIntervalById(id: Long): Flow<RepeatedInterval?> =
        dao.getByIdFlow(id).flatMapLatest { entity ->
            if (entity == null) flowOf(null)
            else intervalRepository.getAllIntervals().map { intervals ->
                val intervalMap = intervals.associateBy { it.id }
                val edgeRefs = parseEdgeRefs(entity.edges)
                val edgeMap = mapGraphRepository.getEdgesByNodePairs(edgeRefs).associateBy { it.fromNode to it.toNode }
                entityToDomain(entity, intervalMap, edgeMap)
            }
        }

    override suspend fun getAllRepeatedIntervalsList(): List<RepeatedInterval> {
        val entities = dao.getAllList()
        val intervalMap = intervalRepository.getAllIntervals().first().associateBy { it.id }
        val allEdgeRefs = entities.flatMap { parseEdgeRefs(it.edges) }.distinct()
        val edgeMap = mapGraphRepository.getEdgesByNodePairs(allEdgeRefs).associateBy { it.fromNode to it.toNode }
        return entities.mapNotNull { entityToDomain(it, intervalMap, edgeMap) }
    }

    override suspend fun saveRepeatedInterval(interval: RepeatedInterval): Long {
        val createdAt = if (interval.id != 0L) {
            dao.getById(interval.id)?.createdAt ?: System.currentTimeMillis()
        } else {
            System.currentTimeMillis()
        }
        return dao.insert(domainToEntity(interval, createdAt))
    }

    override suspend fun renameRepeatedInterval(id: Long, newName: String) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(name = newName))
    }

    override suspend fun deleteRepeatedIntervalsByIds(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }

    private fun entityToDomain(
        entity: RepeatedIntervalEntity,
        intervalMap: Map<Long, IntervalSession>,
        edgeMap: Map<Pair<Long, Long>, MapEdge>
    ): RepeatedInterval? {
        val intervalIds = parseLongList(entity.intervalIds)
        val intervals = intervalIds.mapNotNull { intervalMap[it] }
        if (intervals.isEmpty()) return null

        val edgeRefs = parseEdgeRefs(entity.edges)
        val edges = edgeRefs.mapNotNull { (fromNode, toNode) -> edgeMap[fromNode to toNode] }
        if (edges.isEmpty()) return null

        return RepeatedInterval(
            id = entity.id,
            name = entity.name,
            intervals = intervals,
            edges = edges,
            startLat = entity.startLat,
            startLon = entity.startLon,
            endLat = entity.endLat,
            endLon = entity.endLon,
            distanceM = entity.distanceM
        )
    }

    private fun domainToEntity(interval: RepeatedInterval, createdAt: Long): RepeatedIntervalEntity {
        val intervalIds = interval.intervals.map { it.id }.sorted()
        val edgeRefs = interval.edges.map { listOf(it.fromNode, it.toNode) }
        return RepeatedIntervalEntity(
            id = interval.id,
            name = interval.name,
            intervalIds = gson.toJson(intervalIds),
            edges = gson.toJson(edgeRefs),
            startLat = interval.startLat,
            startLon = interval.startLon,
            endLat = interval.endLat,
            endLon = interval.endLon,
            distanceM = interval.distanceM,
            createdAt = createdAt
        )
    }

    private fun parseLongList(json: String): List<Long> {
        return try {
            gson.fromJson(json, listLongType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse interval IDs from JSON", e)
            emptyList()
        }
    }

    private fun parseEdgeRefs(json: String): List<Pair<Long, Long>> {
        return try {
            val raw: List<List<Long>> = gson.fromJson(json, edgeRefListType)
            raw.mapNotNull { if (it.size >= 2) it[0] to it[1] else null }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse edge references from JSON", e)
            emptyList()
        }
    }
}
