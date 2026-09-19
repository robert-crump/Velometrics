package com.velometrics.app.util

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The single JSON module: owns the app's only [Gson] instance and the safe-parse helpers used
 * for every persisted JSON shape (zone histograms, track encodings, id lists).
 */
object Json {

    @PublishedApi
    internal val gson = Gson()

    fun toJson(value: Any?): String = gson.toJson(value)

    /** Parses [json] as [T], logging [errorMessage] under [tag] and returning [default] on failure. */
    inline fun <reified T> parseOrDefault(json: String, tag: String, errorMessage: String, default: T): T {
        return try {
            val result: T? = gson.fromJson(json, object : TypeToken<T>() {}.type)
            result ?: default
        } catch (e: Exception) {
            Log.e(tag, errorMessage, e)
            default
        }
    }
}
