package com.cyclegraph.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cyclegraph.app.util.CyclingConstants
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToInfo: () -> Unit = {},
    onNavigateToHomeAddress: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val recalcState by viewModel.recalcState.collectAsState()
    val currentFtp by viewModel.ftp.collectAsState(initial = CyclingConstants.DEFAULT_FTP)
    val currentHomeLat by viewModel.homeLat.collectAsState(initial = CyclingConstants.HOME_LAT)
    val currentHomeLon by viewModel.homeLon.collectAsState(initial = CyclingConstants.HOME_LON)
    val pendingFtp by viewModel.pendingFtp.collectAsState()

    // Local edit state for FTP text field
    var ftpFieldValue by remember(currentFtp) { mutableStateOf(currentFtp.toString()) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showFtpInfo by remember { mutableStateOf(false) }

    // FTP info dialog
    if (showFtpInfo) {
        AlertDialog(
            onDismissRequest = { showFtpInfo = false },
            title = { Text("What is FTP?") },
            text = {
                Text(
                    "FTP (Functional Threshold Power) is the average power you can sustain for one hour. " +
                    "It defines power zones, sprint detection (≥${(CyclingConstants.SPRINT_THRESHOLD_FACTOR * 100).roundToInt()}% FTP), " +
                    "and interval detection (≥${(CyclingConstants.INTERVAL_THRESHOLD_FACTOR * 100).roundToInt()}% FTP). " +
                    "Requires a power meter."
                )
            },
            confirmButton = {
                TextButton(onClick = { showFtpInfo = false }) { Text("OK") }
            }
        )
    }

    // Confirmation dialog
    pendingFtp?.let { newFtp ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelFtpChange() },
            title = { Text("Change FTP?") },
            text = {
                Text(
                    "New FTP = $newFtp W will be used for all future file imports.\n\n" +
                    "Existing session data (power zones, fat efficiency, sprints) remains based on " +
                    "FTP = $currentFtp W and cannot be updated without re-importing those files."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmFtpChange() }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelFtpChange() }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = {
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Info") },
                                onClick = {
                                    showOverflowMenu = false
                                    onNavigateToInfo()
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Power settings
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Power Settings",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = ftpFieldValue,
                            onValueChange = { ftpFieldValue = it },
                            label = { Text("FTP (W)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showFtpInfo = true }) {
                            Icon(Icons.Default.Info, contentDescription = "About FTP")
                        }
                        Button(
                            onClick = {
                                val parsed = ftpFieldValue.trim().toIntOrNull()
                                if (parsed != null && parsed > 0 && parsed != currentFtp) {
                                    viewModel.requestFtpChange(parsed)
                                }
                            }
                        ) {
                            Text("Save")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Home location
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Home Location",
                            style = MaterialTheme.typography.titleMedium
                        )
                        IconButton(onClick = onNavigateToHomeAddress) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit home location")
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Latitude: ${"%.5f".format(currentHomeLat)}")
                    Text("Longitude: ${"%.5f".format(currentHomeLon)}")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Recalculate session stats
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Recalculate Session Stats",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Re-runs session comparisons. Power zone histograms and sprint data " +
                            "require re-importing FIT files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { viewModel.recalculateAllStats() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = recalcState !is RecalcState.Running
                    ) {
                        Text("Recalculate")
                    }

                    when (recalcState) {
                        is RecalcState.Running -> {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "Recalculating...",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        is RecalcState.Done -> {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Done",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
