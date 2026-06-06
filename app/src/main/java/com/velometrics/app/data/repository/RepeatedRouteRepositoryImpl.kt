package com.velometrics.app.data.repository

import com.velometrics.app.data.local.dao.RepeatedRouteDao
import com.velometrics.app.data.local.entity.RepeatedRouteEntity
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RepeatedRoute
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.RepeatedRouteRepository
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepeatedRouteRepositoryImpl @Inject constructor(
    private val dao: RepeatedRouteDao,
    private val sessionRepository: CyclingSessionRepository
) : RepeatedRouteRepository {

    companion object { private const val TAG = "RepeatedRouteRepo" }

    private val gson = Gson()
    private val listLongType = object : TypeToken<List<Long>>() {}.type

    override fun getAllRoutes(): Flow<List<RepeatedRoute>> {
        return combine(
            dao.getAllRoutes(),
            sessionRepository.getAllSessions()
        ) { entities, sessions ->
            val sessionMap = sessions.associateBy { it.id }
            entities.mapNotNull { entity -> entityToDomain(entity, sessionMap) }
        }
    }

    override fun getRouteById(id: Long): Flow<RepeatedRoute?> =
        dao.getByIdFlow(id).flatMapLatest { entity ->
            if (entity == null) flowOf(null)
            else {
                val ids = parseIds(entity.sessionIds)
                sessionRepository.getSessionsByIds(ids).map { sessions ->
                    val sessionMap = sessions.associateBy { it.id }
                    entityToDomain(entity, sessionMap)
                }
            }
        }

    override suspend fun getAllRoutesList(): List<RepeatedRoute> {
        val entities = dao.getAllRoutesList()
        val allIds = entities.flatMap { parseIds(it.sessionIds) }.distinct()
        val sessionMap = sessionRepository.getSessionsByIdsList(allIds).associateBy { it.id }
        return entities.mapNotNull { entityToDomain(it, sessionMap) }
    }

    override suspend fun saveRoute(route: RepeatedRoute): Long {
        val createdAt = if (route.id != 0L) {
            dao.getById(route.id)?.createdAt ?: System.currentTimeMillis()
        } else {
            System.currentTimeMillis()
        }
        val entity = domainToEntity(route, createdAt)
        return dao.insert(entity)
    }

    override suspend fun renameRoute(id: Long, newName: String) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(name = newName))
    }

    override suspend fun deleteRoutesByIds(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }

    private fun entityToDomain(entity: RepeatedRouteEntity, sessionMap: Map<Long, CyclingSession>): RepeatedRoute? {
        val ids = parseIds(entity.sessionIds)
        val sessions = ids.mapNotNull { sessionMap[it] }
        if (sessions.isEmpty()) return null

        // Representative track: median-length session's GPS track
        val sortedByLength = sessions
            .filter { it.gpsTrack != null }
            .sortedBy { it.gpsTrack!!.length }
        val representative = if (sortedByLength.isEmpty()) null else {
            val medianIdx = sortedByLength.size / 2
            val trackJson = sortedByLength[medianIdx].gpsTrack
            parseGpsTrack(trackJson)
        }

        return RepeatedRoute(
            id = entity.id,
            name = entity.name,
            sessions = sessions,
            representativeTrack = representative
        )
    }

    private fun domainToEntity(route: RepeatedRoute, createdAt: Long): RepeatedRouteEntity {
        val ids = route.sessions.map { it.id }.sorted()
        return RepeatedRouteEntity(
            id = route.id,
            name = route.name,
            sessionIds = gson.toJson(ids),
            createdAt = createdAt
        )
    }

    private fun parseIds(json: String): List<Long> {
        return try {
            gson.fromJson(json, listLongType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse session IDs from JSON", e)
            emptyList()
        }
    }

    private fun parseGpsTrack(json: String?): List<List<Double>>? {
        if (json == null) return null
        return try {
            val type = object : TypeToken<List<List<Double>>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GPS track JSON", e)
            null
        }
    }
}
