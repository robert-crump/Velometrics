package com.velometrics.app.data.local.converter

import com.velometrics.app.util.Json
import androidx.room.TypeConverter
import com.google.gson.reflect.TypeToken
import java.time.Instant

class Converters {

    @TypeConverter
    fun fromInstant(value: Instant?): Long? {
        return value?.toEpochMilli()
    }

    @TypeConverter
    fun toInstant(value: Long?): Instant? {
        return value?.let { Instant.ofEpochMilli(it) }
    }

    @TypeConverter
    fun fromMapStringInt(value: Map<String, Int>?): String? {
        return value?.let { Json.gson.toJson(it) }
    }

    @TypeConverter
    fun toMapStringInt(value: String?): Map<String, Int>? {
        if (value == null) return null
        val type = object : TypeToken<Map<String, Int>>() {}.type
        return Json.gson.fromJson(value, type)
    }

    @TypeConverter
    fun fromListListDouble(value: List<List<Double>>?): String? {
        return value?.let { Json.gson.toJson(it) }
    }

    @TypeConverter
    fun toListListDouble(value: String?): List<List<Double>>? {
        if (value == null) return null
        val type = object : TypeToken<List<List<Double>>>() {}.type
        return Json.gson.fromJson(value, type)
    }
}
