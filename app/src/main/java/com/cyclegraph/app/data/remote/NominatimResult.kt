package com.cyclegraph.app.data.remote

import com.google.gson.annotations.SerializedName

data class NominatimResult(
    val lat: String,
    val lon: String,
    @SerializedName("display_name") val displayName: String
)
