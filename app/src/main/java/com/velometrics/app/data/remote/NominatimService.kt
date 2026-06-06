package com.velometrics.app.data.remote

import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

interface NominatimService {
    @Headers("User-Agent: CycleGraph/1.0 (contact: app@cyclegraph.com)")
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 5
    ): List<NominatimResult>
}
