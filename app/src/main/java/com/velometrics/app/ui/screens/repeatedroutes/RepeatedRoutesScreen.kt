package com.velometrics.app.ui.screens.repeatedroutes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.model.RepeatedRoute
import com.velometrics.app.ui.components.RefreshableList
import com.velometrics.app.ui.screens.repeatedintervals.RepeatedIntervalSortOrder
import com.velometrics.app.ui.screens.repeatedintervals.RepeatedIntervalsViewModel
import com.velometrics.app.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatedRoutesScreen(
    onNavigateToRouteDetail: (Long) -> Unit = {},
    onNavigateToIntervalDetail: (Long) -> Unit = {},
    viewModel: RepeatedRoutesViewModel = hiltViewModel(),
    intervalsViewModel: RepeatedIntervalsViewModel = hiltViewModel()
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    var filterMenuExpanded by remember { mutableStateOf(false) }

    val routeSortOrder by viewModel.sortOrder.collectAsState()
    val intervalSortOrder by intervalsViewModel.sortOrder.collectAsState()

    val routeSortDimensions = listOf(
        Triple("Distance", RouteSortOrder.DISTANCE_DESC, RouteSortOrder.DISTANCE_ASC),
        Triple("Frequency", RouteSortOrder.FREQUENCY_DESC, RouteSortOrder.FREQUENCY_ASC),
        Triple("Name", RouteSortOrder.NAME_ASC, RouteSortOrder.NAME_DESC),
    )
    val intervalSortDimensions = listOf(
        Triple("Distance", RepeatedIntervalSortOrder.DISTANCE_DESC, RepeatedIntervalSortOrder.DISTANCE_ASC),
        Triple("Frequency", RepeatedIntervalSortOrder.FREQUENCY_DESC, RepeatedIntervalSortOrder.FREQUENCY_ASC),
        Triple("Name", RepeatedIntervalSortOrder.NAME_ASC, RepeatedIntervalSortOrder.NAME_DESC),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedTab == RoutesSubTab.ROUTES) "Repeated Routes" else "Repeated Intervals") },
                actions = {
                    Box {
                        IconButton(onClick = { filterMenuExpanded = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = filterMenuExpanded,
                            onDismissRequest = { filterMenuExpanded = false }
                        ) {
                            if (selectedTab == RoutesSubTab.ROUTES) {
                                SortDropdownItems(routeSortDimensions, routeSortOrder) { newOrder ->
                                    viewModel.setSortOrder(newOrder)
                                    filterMenuExpanded = false
                                }
                            } else {
                                SortDropdownItems(intervalSortDimensions, intervalSortOrder) { newOrder ->
                                    intervalsViewModel.setSortOrder(newOrder)
                                    filterMenuExpanded = false
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                RoutesSubTab.entries.forEachIndexed { index, tab ->
                    val count = RoutesSubTab.entries.size
                    val shape = when (index) {
                        0 -> RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                        count - 1 -> RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
                        else -> RoundedCornerShape(0.dp)
                    }
                    SegmentedButton(
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        shape = shape,
                        icon = {},
                        label = { Text(tab.label) }
                    )
                }
            }

            when (selectedTab) {
                RoutesSubTab.ROUTES -> RepeatedRoutesContent(
                    viewModel = viewModel,
                    onNavigateToRouteDetail = onNavigateToRouteDetail
                )
                RoutesSubTab.INTERVALS -> RepeatedIntervalsContent(
                    viewModel = intervalsViewModel,
                    onNavigateToIntervalDetail = onNavigateToIntervalDetail
                )
            }
        }
    }
}

@Composable
private fun RepeatedRoutesContent(
    viewModel: RepeatedRoutesViewModel,
    onNavigateToRouteDetail: (Long) -> Unit
) {
    val routes by viewModel.routes.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    RefreshableList(
        entries = routes,
        isLoading = isLoading,
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
        key = { it.id },
        emptyTitle = "No repeated routes yet",
        emptySubtitle = "Import ≥3 sessions on the same route to get started"
    ) { route ->
        RepeatedRouteCard(
            route = route,
            onClick = { onNavigateToRouteDetail(route.id) }
        )
    }
}

@Composable
private fun RepeatedIntervalsContent(
    viewModel: RepeatedIntervalsViewModel,
    onNavigateToIntervalDetail: (Long) -> Unit
) {
    val repeatedIntervals by viewModel.repeatedIntervals.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    RefreshableList(
        entries = repeatedIntervals,
        isLoading = isLoading,
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
        key = { it.id },
        emptyTitle = "No repeated intervals yet",
        emptySubtitle = "Pull to refresh after importing sessions with intervals"
    ) { interval ->
        RepeatedIntervalCard(
            repeatedInterval = interval,
            onClick = { onNavigateToIntervalDetail(interval.id) }
        )
    }
}

/**
 * Enum-sort dropdown items shared by the Routes/Intervals sort menus: each dimension toggles
 * between its default and alt (usually desc/asc) order when re-selected while already active.
 */
@Composable
private fun <T : Enum<T>> SortDropdownItems(
    dimensions: List<Triple<String, T, T>>,
    currentOrder: T,
    onSelect: (T) -> Unit
) {
    dimensions.forEach { (label, defaultOrder, altOrder) ->
        val isActive = currentOrder == defaultOrder || currentOrder == altOrder
        val arrow = if (isActive) {
            if (currentOrder.name.endsWith("_ASC")) " ↑" else " ↓"
        } else ""
        DropdownMenuItem(
            text = {
                Text(
                    "$label$arrow",
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )
            },
            onClick = {
                val newOrder = if (currentOrder == defaultOrder) altOrder else defaultOrder
                onSelect(newOrder)
            }
        )
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
                StatValue("${count}x")
                StatValue(FormatUtils.formatDistance(avgDist))
                StatValue(FormatUtils.formatDuration(avgDuration))
            }
        }
    }
}

@Composable
private fun RepeatedIntervalCard(
    repeatedInterval: RepeatedInterval,
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
            Text(repeatedInterval.name, style = MaterialTheme.typography.titleMedium)

            val intervals = repeatedInterval.intervals
            val count = intervals.size
            val avgDuration = if (count > 0) intervals.sumOf { it.durationNormalizedSec } / count else 0
            val avgPower = if (count > 0) intervals.sumOf { it.avgPower } / count else 0

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatValue("${count}x")
                StatValue(FormatUtils.formatDistance(repeatedInterval.distanceM / 1000.0))
                StatValue(FormatUtils.formatDuration(avgDuration))
                StatValue(FormatUtils.formatPower(avgPower))
            }
        }
    }
}

@Composable
private fun StatValue(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.bodyMedium
    )
}
