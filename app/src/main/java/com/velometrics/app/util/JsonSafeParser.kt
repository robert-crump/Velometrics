package com.velometrics.app.util

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Consolidates the repo's "parse JSON, log and fall back to a default on failure" boilerplate
 * that was previously duplicated across the data layer's various `parseXyz` helpers.
 */
object JsonSafeParser {

    @PublishedApi
    internal val gson = Gson()

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
