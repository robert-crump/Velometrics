package com.cyclegraph.app.data.graphimport

import com.cyclegraph.app.domain.model.Poi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PoiJsonImporter @Inject constructor() {

    private val gson = Gson()

    fun import(inputStream: InputStream): List<Poi> {
        val reader = InputStreamReader(inputStream)
        val type = object : TypeToken<List<RawPoi>>() {}.type
        val rawList: List<RawPoi> = gson.fromJson(reader, type)
        return rawList.map { p ->
            Poi(
                poiId = p.poi_id,
                name = p.name,
                category = p.category,
                cuisine = p.cuisine,
                lat = p.lat,
                lon = p.lon,
                openingHours = p.opening_hours
            )
        }
    }

    private data class RawPoi(
        val poi_id: String,
        val name: String,
        val category: String,
        val cuisine: String?,
        val lat: Double,
        val lon: Double,
        val opening_hours: String?
    )
}
