package com.cyclegraph.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cyclegraph.app.domain.model.Poi

@Entity(tableName = "pois")
data class PoiEntity(
    @PrimaryKey @ColumnInfo(name = "poi_id") val poiId: String,
    val name: String?,
    val category: String?,
    val cuisine: String?,
    val lat: Double?,
    val lon: Double?,
    @ColumnInfo(name = "opening_hours") val openingHours: String?
)

fun PoiEntity.toDomain(): Poi? {
    val lat = lat ?: return null
    val lon = lon ?: return null
    return Poi(
        poiId = poiId,
        name = name ?: "",
        category = category ?: "",
        cuisine = cuisine,
        lat = lat,
        lon = lon,
        openingHours = openingHours
    )
}
