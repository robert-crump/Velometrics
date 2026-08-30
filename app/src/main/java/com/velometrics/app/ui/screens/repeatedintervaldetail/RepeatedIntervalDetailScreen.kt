package com.velometrics.app.ui.screens.repeatedintervaldetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.ui.components.EditableTopBarActions
import com.velometrics.app.ui.components.EditableTopBarTitle
import com.velometrics.app.ui.components.LoadingBox
import com.velometrics.app.ui.components.MetricCell
import com.velometrics.app.ui.components.NotFoundBox
import com.velometrics.app.ui.components.PullUpDrawer
import com.velometrics.app.ui.components.TrackMapWithDrawer
import com.velometrics.app.ui.components.rememberEditableTopBarTitleState
import com.velometrics.app.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatedIntervalDetailScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToSession: (Long) -> Unit = {},
    viewModel: RepeatedIntervalDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val titleState = rememberEditableTopBarTitleState(uiState.repeatedInterval?.name)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    EditableTopBarTitle(
                        state = titleState,
                        displayName = uiState.repeatedInterval?.name ?: "Interval",
                        onRename = { viewModel.rename(it) }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    EditableTopBarActions(
                        state = titleState,
                        currentName = uiState.repeatedInterval?.name,
                        renameContentDescription = "Rename interval",
                        onRename = { viewModel.rename(it) }
                    )
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingBox()
            return@Scaffold
        }

        val repeatedInterval = uiState.repeatedInterval
        if (repeatedInterval == null) {
            NotFoundBox(text = "Interval not found", modifier = Modifier.padding(padding))
            return@Scaffold
        }

        var drawerFraction by remember { mutableStateOf(0.5f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Full-screen map background (interactive)
            TrackMapWithDrawer(
                points = uiState.trackPoints,
                drawerFraction = drawerFraction,
                trackId = "repeated-interval-detail"
            )

            // Pull-up drawer with all statistics; opens at 50%
            PullUpDrawer(
                initialFraction = 0.5f,
                onFractionSnapped = { drawerFraction = it }
            ) {
                RepeatedIntervalSummaryGrid(
                    timesCount = repeatedInterval.intervals.size,
                    distanceM = repeatedInterval.distanceM,
                    avgDurationSec = uiState.avgDurationSec,
                    avgSpeedKmh = uiState.avgSpeedKmh,
                    avgPowerW = uiState.avgPowerW
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("History", style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        repeatedInterval.intervals.sortedByDescending { it.startTimestamp }.forEach { interval ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToSession(interval.cyclingSessionId) }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    FormatUtils.formatDate(interval.startTimestamp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    FormatUtils.formatDuration(interval.durationNormalizedSec),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun RepeatedIntervalSummaryGrid(
    timesCount: Int,
    distanceM: Double,
    avgDurationSec: Int,
    avgSpeedKmh: Double,
    avgPowerW: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                MetricCell(label = "Times", value = "$timesCount")
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(label = "Distance", value = FormatUtils.formatDistance(distanceM / 1000.0))
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(label = "Avg duration", value = FormatUtils.formatDuration(avgDurationSec))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                MetricCell(label = "Avg speed", value = FormatUtils.formatSpeed(avgSpeedKmh))
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(label = "Avg power", value = FormatUtils.formatPower(avgPowerW))
            }
        }
    }
}
