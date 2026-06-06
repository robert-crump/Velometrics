package com.velometrics.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("dashboard", "Home", Icons.Default.Home)
    data object SessionDetail : Screen("session/{sessionId}", "Session Detail", Icons.Default.Info) {
        fun createRoute(sessionId: Long) = "session/$sessionId"
    }
    data object MapView : Screen("map", "Map", Icons.Default.Map)
    data object RoutePlanner : Screen("routes", "Routes", Icons.Default.Route)
    data object Navigation : Screen("navigation", "Navigate", Icons.Default.Navigation)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object RepeatedRouteDetail : Screen("repeated_route/{routeId}", "Route Detail", Icons.Default.Route) {
        fun createRoute(routeId: Long) = "repeated_route/$routeId"
    }
    data object Info : Screen("info", "Info", Icons.Default.Info)
    data object HomeAddress : Screen("home_address", "Home Location", Icons.Default.Home)
}

// Bottom navigation items (top-level destinations)
val bottomNavItems = listOf(
    Screen.Home,
    Screen.MapView,
    Screen.RoutePlanner,
    Screen.Navigation,
    Screen.Settings
)
