package com.velometrics.app.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.velometrics.app.ui.navigation.Screen
import com.velometrics.app.ui.navigation.bottomNavItems

@Composable
fun BottomNavBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Detail screens aren't tabs themselves; highlight the tab they were opened from.
    val highlightedRoute = when (currentRoute) {
        Screen.SessionDetail.route -> Screen.Home.route
        Screen.RepeatedRouteDetail.route -> Screen.RoutePlanner.route
        Screen.RepeatedIntervalDetail.route -> Screen.RoutePlanner.route
        Screen.Info.route -> Screen.Settings.route
        Screen.HomeAddress.route -> Screen.Settings.route
        else -> currentRoute
    }

    NavigationBar {
        bottomNavItems.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = screen.title) },
                label = { Text(screen.title) },
                selected = highlightedRoute == screen.route,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}
