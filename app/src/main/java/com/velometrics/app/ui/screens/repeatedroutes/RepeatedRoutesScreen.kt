package com.velometrics.app.ui.screens.repeatedroutes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.domain.model.RepeatedRoute
import com.velometrics.app.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatedRoutesScreen(
    onNavigateToRouteDetail: (Long) -> Unit = {},
    onNavigateToNavigation: () -> Unit = {},
    viewModel: RepeatedRoutesViewModel = hiltViewModel()
) {
    val routes by viewModel.routes.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var filterMenuExpanded by remember { mutableStateOf(false) }

    val sortOptions = listOf(
        "Distance ↑" to RouteSortOrder.DISTANCE_ASC,
        "Distance ↓" to RouteSortOrder.DISTANCE_DESC,
        "Frequency ↑" to RouteSortOrder.FREQUENCY_ASC,
        "Frequency ↓" to RouteSortOrder.FREQUENCY_DESC,
        "Name ↑" to RouteSortOrder.NAME_ASC,
        "Name ↓" to RouteSortOrder.NAME_DESC,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Repeated Routes") },
                actions = {
                    Box {
                        IconButton(onClick = { filterMenuExpanded = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = filterMenuExpanded,
                            onDismissRequest = { filterMenuExpanded = false }
                        ) {
                            sortOptions.forEach { (label, order) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            label,
                                            fontWeight = if (sortOrder == order) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        viewModel.setSortOrder(order)
                                        filterMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isLoading) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (routes.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillParentMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "No repeated routes yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Import ≥3 sessions on the same route to get started",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(routes, key = { it.id }) { route ->
                        RepeatedRouteCard(
                            route = route,
                            onClick = { onNavigateToRouteDetail(route.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RepeatedRouteCard(
    route: RepeatedRoute,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(route.name, style = MaterialTheme.typography.titleMedium)

            val sessions = route.sessions
            val count = sessions.size
            val avgDist = if (count > 0) sessions.sumOf { it.distanceKm } / count else 0.0
            val avgDuration = if (count > 0) sessions.sumOf { it.netDurationSec } / count else 0

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatChip(label = "Times", value = "$count")
                StatChip(label = "Avg dist", value = FormatUtils.formatDistance(avgDist))
                StatChip(label = "Avg time", value = FormatUtils.formatDuration(avgDuration))
            }

            val latest = sessions.maxByOrNull { it.sessionStart }
            if (latest != null) {
                Text(
                    "Last ridden: ${FormatUtils.formatDate(latest.sessionStart)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
