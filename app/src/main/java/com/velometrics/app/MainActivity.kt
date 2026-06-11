package com.velometrics.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.velometrics.app.data.dropbox.DropboxAuthRepository
import com.velometrics.app.ui.components.BottomNavBar
import com.velometrics.app.ui.intent.GpxIntentViewModel
import com.velometrics.app.ui.navigation.VelometricsNavHost
import com.velometrics.app.ui.navigation.Screen
import com.velometrics.app.ui.theme.VelometricsTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val gpxIntentViewModel: GpxIntentViewModel by viewModels()

    @Inject
    lateinit var dropboxAuthRepository: DropboxAuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractGpxUri(intent)?.let { gpxIntentViewModel.setPendingUri(it) }
        enableEdgeToEdge()
        setContent {
            VelometricsTheme {
                val navController = rememberNavController()
                val pendingUri by gpxIntentViewModel.pendingGpxUri.collectAsState()

                LaunchedEffect(pendingUri) {
                    if (pendingUri != null) {
                        navController.navigate(Screen.MapView.route) {
                            launchSingleTop = true
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = { BottomNavBar(navController = navController) }
                ) { innerPadding ->
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

    override fun onResume() {
        super.onResume()
        dropboxAuthRepository.handleAuthResult()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractGpxUri(intent)?.let { gpxIntentViewModel.setPendingUri(it) }
    }

    private fun extractGpxUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}
