package com.velometrics.app.domain.model

/**
 * The five auto-classification tags [RideClassifier][com.velometrics.app.domain.service.RideClassifier]
 * assigns to every ride (#169). [label] is the exact string persisted on
 * [CyclingSession.tag] and shown in the Session Detail chip — never [name], so a future
 * enum-constant rename can't silently change what's already stored in the database.
 */
enum class RideTag(val label: String) {
    INTERVALS("Intervals"),
    RACE("Race"),
    ZONE_2("Zone 2"),
    RECOVERY("Recovery"),
    ENDURANCE("Endurance")
}
