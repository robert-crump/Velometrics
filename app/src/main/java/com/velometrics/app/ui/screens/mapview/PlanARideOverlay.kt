package com.velometrics.app.ui.screens.mapview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.velometrics.app.domain.service.RankedCandidate
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.FormatUtils
import kotlin.math.roundToInt

const val PLAN_A_RIDE_TRACK_ID_PREFIX = "plan-a-ride-candidate-"

@Composable
fun PlanARideCard(
    candidates: List<RankedCandidate>,
    selectedIndex: Int?,
    message: String?,
    isLoading: Boolean,
    onSelectCandidate: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Plan a ride", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                }
            }

            when {
                isLoading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Generating routes…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                candidates.isNotEmpty() -> {
                    candidates.forEachIndexed { index, candidate ->
                        val isSelected = index == selectedIndex
                        CandidateRow(
                            candidate = candidate,
                            color = CyclingConstants.PLAN_A_RIDE_TRACK_COLORS[
                                index % CyclingConstants.PLAN_A_RIDE_TRACK_COLORS.size
                            ],
                            isSelected = isSelected,
                            onClick = { onSelectCandidate(index) },
                        )
                    }
                }
                message != null -> {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: RankedCandidate,
    color: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val distanceKm = candidate.refinedRoute.actualDistanceM / 1000.0
    val deviation = candidate.distanceDeviationPercent.roundToInt()
    val deviationText = if (deviation != 0) " (%+d%%)".format(deviation) else ""

    val borderStroke = if (isSelected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        border = borderStroke,
        colors = if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = FormatUtils.formatDistance(distanceKm) + deviationText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Flow: %.0f".format(candidate.refinedRoute.flowScore),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Discovery: %.0f".format(candidate.refinedRoute.discoveryScore),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
