package com.velometrics.app.ui.screens.trainingload

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.ui.components.LoadingBox
import com.velometrics.app.ui.components.MetricCell
import com.velometrics.app.ui.components.NotFoundBox
import com.velometrics.app.ui.components.TrainingLoadChart
import kotlin.math.roundToInt

// Form/TSB sign bands, matching this app's existing convention (CardiacDriftChart's bandColors)
// of local hex constants for good/neutral/bad signal coloring rather than a theme token.
// Placeholder thresholds pending a product look (#190 grill-me) — cheap to retune, no state
// depends on the exact cut points.
private val TsbFreshColor = Color(0xFF4CAF50)   // green
private val TsbNeutralColor = Color(0xFFFFA726) // orange
private val TsbFatiguedColor = Color(0xFFEF5350) // red

private fun tsbColor(tsb: Double): Color = when {
    tsb >= 5 -> TsbFreshColor
    tsb >= -10 -> TsbNeutralColor
    else -> TsbFatiguedColor
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingLoadScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: TrainingLoadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Training Load") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                LoadingBox(modifier = Modifier.padding(padding))
            }
            !uiState.hasAnySessions -> {
                NotFoundBox(
                    text = "No rides recorded yet",
                    modifier = Modifier.padding(padding),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    ReadoutCard(
                        ctl = uiState.currentCtl,
                        atl = uiState.currentAtl,
                        tsb = uiState.currentTsb
                    )
                    TrainingLoadChart(points = uiState.chartPoints)
                    ExplainerCard()
                }
            }
        }
    }
}

@Composable
private fun ReadoutCard(ctl: Double, atl: Double, tsb: Double) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MetricCell(label = "Fitness", value = "${ctl.roundToInt()}")
            }
            Column(modifier = Modifier.weight(1f)) {
                MetricCell(label = "Fatigue", value = "${atl.roundToInt()}")
            }
            Column(modifier = Modifier.weight(1f)) {
                val signed = if (tsb >= 0) "+${tsb.roundToInt()}" else "${tsb.roundToInt()}"
                MetricCell(label = "Form", value = signed, valueColor = tsbColor(tsb))
            }
        }
    }
}

@Composable
private fun ExplainerCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("How this is calculated", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Fitness (CTL) and Fatigue (ATL) are rolling averages of a daily training-load " +
                "score, weighted toward recent rides — Fitness over the last 42 days, Fatigue " +
                "over the last 7. Form (TSB) is Fitness minus Fatigue: positive means you're " +
                "fresh, negative means you're carrying fatigue from recent training.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("What raises it:", style = MaterialTheme.typography.labelMedium)
            Text(
                "Harder and more frequent rides raise both Fitness and Fatigue — Fatigue " +
                "responds faster, Fitness builds more slowly and holds longer. Rest days let " +
                "Fatigue fall while Fitness holds, which is what pushes Form positive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Limitations:", style = MaterialTheme.typography.labelMedium)
            Text(
                "Rides with power use a standard Training Stress Score off the FTP in force on the " +
                "ride's date (see your FTP history in Settings); " +
                "rides with only heart rate use an approximate heart-rate-zone-based score; " +
                "rides with neither don't contribute load. Retesting only affects rides from " +
                "the new FTP's effective date onward.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
