package com.cyclegraph.app.data.local.converter

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant

class Converters {
    private val gson = Gson()

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
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toMapStringInt(value: String?): Map<String, Int>? {
        if (value == null) return null
        val type = object : TypeToken<Map<String, Int>>() {}.type
        return gson.fromJson(value, type)
    }

    @TypeConverter
    fun fromListListDouble(value: List<List<Double>>?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toListListDouble(value: String?): List<List<Double>>? {
        if (value == null) return null
        val type = object : TypeToken<List<List<Double>>>() {}.type
        return gson.fromJson(value, type)
    }
}
