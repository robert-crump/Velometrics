package com.velometrics.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Sort dimensions shared by the repeated-routes and repeated-intervals lists — both cluster
 * types are sorted the same three ways (distance, frequency of occurrence, name), each with an
 * asc/desc variant. Was two structurally-identical enums (`RouteSortOrder`,
 * `RepeatedIntervalSortOrder`) before being unified.
 */
enum class RepeatedEntrySortOrder {
    DISTANCE_ASC, DISTANCE_DESC,
    FREQUENCY_ASC, FREQUENCY_DESC,
    NAME_ASC, NAME_DESC
}

/**
 * Card shell shared by the Repeated Routes and Repeated Intervals list rows: a title plus a row
 * of plain stat values, at MD3's elevated-card tier (1dp). Callers supply the already-formatted
 * stats for their entity (e.g. "3x", "12.4 km", "24m 10s") since the two entities don't show the
 * same set of stats.
 */
@Composable
fun RepeatedEntryCard(
    name: String,
    stats: List<String>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                stats.forEach { StatValue(it) }
            }
        }
    }
}

@Composable
private fun StatValue(value: String) {
    Text(value, style = MaterialTheme.typography.bodyMedium)
}
