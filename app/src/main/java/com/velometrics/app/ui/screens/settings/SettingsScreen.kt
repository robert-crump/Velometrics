package com.velometrics.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.util.CyclingConstants
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
    val homeDisplayName by viewModel.homeDisplayName.collectAsState(initial = "")
    val pendingFtp by viewModel.pendingFtp.collectAsState()
    val isDropboxConnected by viewModel.isDropboxConnected.collectAsState()
    val needsDropboxReauth by viewModel.needsDropboxReauth.collectAsState()
    val currentDropboxSyncFolder by viewModel.dropboxSyncFolder.collectAsState(
        initial = CyclingConstants.DEFAULT_DROPBOX_SYNC_FOLDER
    )

    var showFtpDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showRecalcDialog by remember { mutableStateOf(false) }

    // FTP edit dialog
    if (showFtpDialog) {
        var ftpInput by remember { mutableStateOf(currentFtp.toString()) }
        AlertDialog(
            onDismissRequest = { showFtpDialog = false },
            title = { Text("FTP (Functional Threshold Power)") },
            text = {
                Column {
                    OutlinedTextField(
                        value = ftpInput,
                        onValueChange = { ftpInput = it },
                        label = { Text("FTP (W)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "The average power you can sustain for one hour. " +
                            "Defines power zones, sprint detection (≥${(CyclingConstants.SPRINT_THRESHOLD_FACTOR * 100).roundToInt()}% FTP), " +
                            "and interval detection (≥${(CyclingConstants.INTERVAL_THRESHOLD_FACTOR * 100).roundToInt()}% FTP). " +
                            "Requires a power meter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = ftpInput.trim().toIntOrNull()
                    if (parsed != null && parsed > 0 && parsed != currentFtp) {
                        showFtpDialog = false
                        viewModel.requestFtpChange(parsed)
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showFtpDialog = false }) { Text("Cancel") }
            }
        )
    }

    // FTP change confirmation dialog
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

    // Dropbox folder edit dialog
    if (showFolderDialog) {
        var folderInput by remember { mutableStateOf(currentDropboxSyncFolder) }
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text("Dropbox sync folder") },
            text = {
                OutlinedTextField(
                    value = folderInput,
                    onValueChange = { folderInput = it },
                    label = { Text("Folder path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = folderInput.trim().trimEnd('/')
                    if (trimmed.isNotEmpty() && trimmed != currentDropboxSyncFolder) {
                        viewModel.saveDropboxSyncFolder(trimmed)
                    }
                    showFolderDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Recalculate confirmation dialog
    if (showRecalcDialog) {
        AlertDialog(
            onDismissRequest = { showRecalcDialog = false },
            title = { Text("Recalculate session stats?") },
            text = {
                Text(
                    "Re-runs session comparisons. Power zone histograms, sprint data, " +
                        "heart-rate, and elevation stats require re-importing FIT files."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRecalcDialog = false
                    viewModel.recalculateAllStats()
                }) { Text("Recalculate") }
            },
            dismissButton = {
                TextButton(onClick = { showRecalcDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Training ──
            SectionHeader("Training")

            SettingsRow(
                icon = Icons.Default.Bolt,
                title = "FTP",
                subtitle = "$currentFtp W",
                onClick = { showFtpDialog = true }
            )

            val homeSubtitle = if (homeDisplayName.isNotBlank()) {
                homeDisplayName
            } else {
                "${"%.5f".format(currentHomeLat)}, ${"%.5f".format(currentHomeLon)}"
            }
            SettingsRow(
                icon = Icons.Default.Home,
                title = "Home location",
                subtitle = homeSubtitle,
                onClick = onNavigateToHomeAddress,
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            // ── Data ──
            SectionHeader("Data")

            val dropboxSubtitle = when {
                needsDropboxReauth -> "Needs reauthorization"
                isDropboxConnected -> "Connected"
                else -> "Not connected"
            }
            SettingsRow(
                icon = Icons.Default.Cloud,
                title = "Dropbox",
                subtitle = dropboxSubtitle,
                subtitleColor = if (needsDropboxReauth) {
                    MaterialTheme.colorScheme.error
                } else null,
                onClick = {
                    if (needsDropboxReauth) viewModel.connectDropbox()
                },
                trailing = {
                    Switch(
                        checked = isDropboxConnected,
                        onCheckedChange = { checked ->
                            if (checked) viewModel.connectDropbox()
                            else viewModel.disconnectDropbox()
                        }
                    )
                }
            )

            SettingsRow(
                icon = Icons.Default.Folder,
                title = "Sync folder",
                subtitle = currentDropboxSyncFolder,
                onClick = { showFolderDialog = true }
            )

            val recalcSubtitle = when (recalcState) {
                is RecalcState.Running -> "Recalculating…"
                is RecalcState.Done -> "Done"
                else -> "Re-run session comparisons"
            }
            SettingsRow(
                icon = Icons.Default.Refresh,
                title = "Recalculate session stats",
                subtitle = recalcSubtitle,
                onClick = {
                    if (recalcState !is RecalcState.Running) showRecalcDialog = true
                }
            )

            // ── About ──
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            SettingsRow(
                icon = Icons.Default.Info,
                title = "About",
                onClick = onNavigateToInfo,
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    subtitleColor: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}
