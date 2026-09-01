package com.velometrics.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.velometrics.app.data.dropbox.DropboxAuthRepository
import com.velometrics.app.ui.components.AppNavigationRail
import com.velometrics.app.ui.components.BottomNavBar
import com.velometrics.app.ui.navigation.VelometricsNavHost
import com.velometrics.app.ui.navigation.WindowWidthSizeClass
import com.velometrics.app.ui.theme.VelometricsTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** MD3 canonical layout: readable content stays capped even on very wide/large windows. */
private val MAX_CONTENT_WIDTH = 840.dp

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var dropboxAuthRepository: DropboxAuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VelometricsTheme {
                val navController = rememberNavController()
                val widthClass = WindowWidthSizeClass.fromWidth(LocalConfiguration.current.screenWidthDp)
                // Compact (phones): bottom nav bar, full-width content. Medium/expanded
                // (tablets, foldables unfolded, desktop windows): a nav rail instead, per MD3's
                // canonical adaptive layouts, with content width capped so text doesn't stretch
                // to an unreadable line length.
                val useRail = widthClass != WindowWidthSizeClass.Compact

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = { if (!useRail) BottomNavBar(navController = navController) }
                ) { innerPadding ->
                    if (useRail) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .consumeWindowInsets(innerPadding)
                        ) {
                            AppNavigationRail(navController = navController)
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                VelometricsNavHost(
                                    navController = navController,
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .widthIn(max = MAX_CONTENT_WIDTH)
                                )
                            }
                        }
                    } else {
                        VelometricsNavHost(
                            navController = navController,
                            modifier = Modifier
                                .padding(innerPadding)
                                .consumeWindowInsets(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dropboxAuthRepository.handleAuthResult()
    }
}
