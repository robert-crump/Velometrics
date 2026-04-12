package com.cyclegraph.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.cyclegraph.app.ui.screens.dashboard.HomeScreen
import com.cyclegraph.app.ui.screens.homeaddress.HomeAddressScreen
import com.cyclegraph.app.ui.screens.info.InfoScreen
import com.cyclegraph.app.ui.screens.mapview.MapViewScreen
import com.cyclegraph.app.ui.screens.navigation.NavigationScreen
import com.cyclegraph.app.ui.screens.repeatedroute.RepeatedRouteDetailScreen
import com.cyclegraph.app.ui.screens.routeplanner.RoutePlannerScreen
import com.cyclegraph.app.ui.screens.sessiondetail.SessionDetailScreen
import com.cyclegraph.app.ui.screens.settings.SettingsScreen

@Composable
fun CycleGraphNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier,
        enterTransition = { fadeIn(initialAlpha = 0f, animationSpec = androidx.compose.animation.core.tween(150)) },
        exitTransition = { fadeOut(targetAlpha = 0f, animationSpec = androidx.compose.animation.core.tween(150)) }
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onSessionClick = { sessionId ->
                    navController.navigate(Screen.SessionDetail.createRoute(sessionId))
                }
            )
        }

        composable(
            route = Screen.SessionDetail.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
        ) {
            SessionDetailScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.MapView.route) {
            MapViewScreen()
        }

        composable(Screen.RoutePlanner.route) {
            RoutePlannerScreen(
                onNavigateToRouteDetail = { routeId ->
                    navController.navigate(Screen.RepeatedRouteDetail.createRoute(routeId))
                },
                onNavigateToNavigation = {
                    navController.navigate(Screen.Navigation.route)
                }
            )
        }

        composable(
            route = Screen.RepeatedRouteDetail.route,
            arguments = listOf(navArgument("routeId") { type = NavType.LongType })
        ) {
            RepeatedRouteDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSession = { sessionId ->
                    navController.navigate(Screen.SessionDetail.createRoute(sessionId))
                }
            )
        }

        composable(Screen.Navigation.route) {
            NavigationScreen(
                onNavigateToHome = {
                    navController.popBackStack(Screen.Home.route, false)
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateToInfo = { navController.navigate(Screen.Info.route) },
                onNavigateToHomeAddress = { navController.navigate(Screen.HomeAddress.route) }
            )
        }

        composable(Screen.Info.route) {
            InfoScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.HomeAddress.route) {
            HomeAddressScreen(onBack = { navController.popBackStack() })
        }
    }
}
