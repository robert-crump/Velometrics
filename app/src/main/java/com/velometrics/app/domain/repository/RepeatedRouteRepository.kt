package com.velometrics.app.domain.repository

import com.velometrics.app.domain.model.RepeatedRoute
import kotlinx.coroutines.flow.Flow

interface RepeatedRouteRepository {
    fun getAllRoutes(): Flow<List<RepeatedRoute>>
    fun getRouteById(id: Long): Flow<RepeatedRoute?>
    suspend fun getAllRoutesList(): List<RepeatedRoute>
    suspend fun saveRoute(route: RepeatedRoute): Long
    suspend fun renameRoute(id: Long, newName: String)
    suspend fun deleteRoutesByIds(ids: List<Long>)
    suspend fun deleteAll()
}
