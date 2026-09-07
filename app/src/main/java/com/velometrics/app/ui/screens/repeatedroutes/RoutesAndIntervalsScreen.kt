package com.velometrics.app.ui.screens.repeatedroutes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.ui.components.RefreshableList
import com.velometrics.app.ui.components.RepeatedEntryCard
import com.velometrics.app.ui.components.RepeatedEntrySortOrder
import com.velometrics.app.ui.screens.repeatedintervals.RepeatedIntervalsViewModel
import com.velometrics.app.util.FormatUtils

/** Shared by both tabs below — routes and intervals sort along the same three dimensions. */
private val sortDimensions = listOf(
    Triple("Distance", RepeatedEntrySortOrder.DISTANCE_DESC, RepeatedEntrySortOrder.DISTANCE_ASC),
    Triple("Frequency", RepeatedEntrySortOrder.FREQUENCY_DESC, RepeatedEntrySortOrder.FREQUENCY_ASC),
    Triple("Name", RepeatedEntrySortOrder.NAME_ASC, RepeatedEntrySortOrder.NAME_DESC),
)

/**
 * Hosts both the Repeated Routes and Repeated Intervals tabs (via [RoutesSubTab]) behind one
 * top bar and segmented-button switcher — two related clustering results sharing one screen
 * rather than two near-identical screens. Named for both tabs it owns; the Routes/Intervals
 * ViewModels themselves stay separate since the underlying entities and clustering services differ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesAndIntervalsScreen(
    onNavigateToRouteDetail: (Long) -> Unit = {},
    onNavigateToIntervalDetail: (Long) -> Unit = {},
    viewModel: RepeatedRoutesViewModel = hiltViewModel(),
    intervalsViewModel: RepeatedIntervalsViewModel = hiltViewModel()
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    var filterMenuExpanded by remember { mutableStateOf(false) }

    val routeSortOrder by viewModel.sortOrder.collectAsState()
    val intervalSortOrder by intervalsViewModel.sortOrder.collectAsState()

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
                                SortDropdownItems(sortDimensions, routeSortOrder) { newOrder ->
                                    viewModel.setSortOrder(newOrder)
                                    filterMenuExpanded = false
                                }
                            } else {
                                SortDropdownItems(sortDimensions, intervalSortOrder) { newOrder ->
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
                    val mediumShape = MaterialTheme.shapes.medium
                    val shape = when (index) {
                        0 -> mediumShape.copy(topEnd = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
                        count - 1 -> mediumShape.copy(topStart = CornerSize(0.dp), bottomStart = CornerSize(0.dp))
                        else -> RectangleShape
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
        val sessions = route.sessions
        val count = sessions.size
        val avgDist = if (count > 0) sessions.sumOf { it.distanceKm } / count else 0.0
        val avgDuration = if (count > 0) sessions.sumOf { it.netDurationSec } / count else 0

        RepeatedEntryCard(
            name = route.name,
            stats = listOf(
                "${count}x",
                FormatUtils.formatDistance(avgDist),
                FormatUtils.formatDuration(avgDuration)
            ),
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
        val intervals = interval.intervals
        val count = intervals.size
        val avgDuration = if (count > 0) intervals.sumOf { it.durationNormalizedSec } / count else 0
        val avgPower = if (count > 0) intervals.sumOf { it.avgPower } / count else 0

        RepeatedEntryCard(
            name = interval.name,
            stats = listOf(
                "${count}x",
                FormatUtils.formatDistance(interval.distanceM / 1000.0),
                FormatUtils.formatDuration(avgDuration),
                FormatUtils.formatPower(avgPower)
            ),
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
