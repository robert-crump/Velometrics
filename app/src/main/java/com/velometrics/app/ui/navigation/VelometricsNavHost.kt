package com.velometrics.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.velometrics.app.ui.screens.home.HomeScreen
import com.velometrics.app.ui.screens.homeaddress.HomeAddressScreen
import com.velometrics.app.ui.screens.info.InfoScreen
import com.velometrics.app.ui.screens.mapview.MapViewScreen
import com.velometrics.app.ui.screens.repeatedintervaldetail.RepeatedIntervalDetailScreen
import com.velometrics.app.ui.screens.repeatedroutedetail.RepeatedRouteDetailScreen
import com.velometrics.app.ui.screens.repeatedroutes.RepeatedRoutesScreen
import com.velometrics.app.ui.screens.sessiondetail.SessionDetailScreen
import com.velometrics.app.ui.screens.settings.SettingsScreen

@Composable
fun VelometricsNavHost(
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
            RepeatedRoutesScreen(
                onNavigateToRouteDetail = { routeId ->
                    navController.navigate(Screen.RepeatedRouteDetail.createRoute(routeId))
                },
                onNavigateToIntervalDetail = { intervalId ->
                    navController.navigate(Screen.RepeatedIntervalDetail.createRoute(intervalId))
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

        composable(
            route = Screen.RepeatedIntervalDetail.route,
            arguments = listOf(navArgument("intervalId") { type = NavType.LongType })
        ) {
            RepeatedIntervalDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSession = { sessionId ->
                    navController.navigate(Screen.SessionDetail.createRoute(sessionId))
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
