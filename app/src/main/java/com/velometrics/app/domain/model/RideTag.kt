package com.velometrics.app.domain.model

/**
 * The auto-classification tags [RideClassifier][com.velometrics.app.domain.service.RideClassifier]
 * can assign to a ride (#169, thresholds tuned in #170). [label] is the exact string persisted on
 * [CyclingSession.tag] and shown in the Session Detail label — never [name], so a future
 * enum-constant rename can't silently change what's already stored in the database.
 *
 * There is deliberately no catch-all/fallback tag (#170): a ride that matches none of these gets
 * `tag = null` and shows no label, rather than a forced "Endurance" default. Race and Endurance
 * were both removed as categories in #170's threshold-tuning pass.
 */
enum class RideTag(val label: String) {
    INTERVALS("Intervals"),
    ZONE_2("Zone 2"),
    RECOVERY("Recovery")
}
