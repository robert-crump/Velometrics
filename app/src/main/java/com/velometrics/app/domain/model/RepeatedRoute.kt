package com.velometrics.app.domain.model

data class RepeatedRoute(
    val id: Long,
    val name: String,
    val isCustomName: Boolean,  // false while `name` is the auto-generated "Repeated Route N" default
    val sessions: List<CyclingSession>,
    val representativeTrack: List<List<Double>>?  // median-length session's gpsTrack
)
