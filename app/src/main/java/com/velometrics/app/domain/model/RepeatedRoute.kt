package com.velometrics.app.domain.model

data class RepeatedRoute(
    val id: Long,
    val name: String,
    val sessions: List<CyclingSession>,
    val representativeTrack: List<List<Double>>?  // median-length session's gpsTrack
)
