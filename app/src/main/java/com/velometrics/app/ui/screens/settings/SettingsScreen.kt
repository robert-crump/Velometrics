package com.velometrics.app.ui.screens.settings

import com.velometrics.app.util.FormatUtils
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import com.velometrics.app.BuildConfig
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.filled.FavoriteBorder
import com.velometrics.app.ui.components.ConfirmDialog
import java.time.LocalDate
import com.velometrics.app.util.CyclingConstants
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToInfo: () -> Unit = {},
    onNavigateToHomeAddress: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val retagStatus by viewModel.retagStatus.collectAsState()
    val dumpStatus by viewModel.dumpStatus.collectAsState()
    val currentMaxHr by viewModel.maxHr.collectAsState(initial = CyclingConstants.DEFAULT_MAX_HR)
    val currentFtp by viewModel.currentFtp.collectAsState(initial = CyclingConstants.DEFAULT_FTP)
    val ftpEntries by viewModel.ftpEntries.collectAsState(initial = emptyList())
    val currentHomeLat by viewModel.homeLat.collectAsState(initial = CyclingConstants.HOME_LAT)
    val currentHomeLon by viewModel.homeLon.collectAsState(initial = CyclingConstants.HOME_LON)
    val homeDisplayName by viewModel.homeDisplayName.collectAsState(initial = "")
    val pendingMaxHr by viewModel.pendingMaxHr.collectAsState()
    val isDropboxConnected by viewModel.isDropboxConnected.collectAsState()
    val needsDropboxReauth by viewModel.needsDropboxReauth.collectAsState()
    val currentDropboxSyncFolder by viewModel.dropboxSyncFolder.collectAsState(
        initial = CyclingConstants.DEFAULT_DROPBOX_SYNC_FOLDER
    )

    var ftpDialog by remember { mutableStateOf<FtpDialogTarget?>(null) }
    var showMaxHrDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }

    ftpDialog?.let { target ->
        FtpEntryDialog(
            target = target,
            onDismiss = { ftpDialog = null },
            onSave = { date, ftp ->
                ftpDialog = null
                viewModel.saveFtpEntry(date, ftp)
            },
            onDelete = { date ->
                ftpDialog = null
                viewModel.deleteFtpEntry(date)
            }
        )
    }

    // Max HR edit dialog
    if (showMaxHrDialog) {
        NumberEditDialog(
            title = "Max Heart Rate",
            label = "Max HR (bpm)",
            currentValue = currentMaxHr,
            helperText = "Your maximum heart rate in beats per minute. " +
                "Defines heart rate zone boundaries (Z1–Z5). " +
                "Requires a heart rate monitor.",
            onDismiss = { showMaxHrDialog = false },
            onConfirm = { parsed ->
                showMaxHrDialog = false
                viewModel.requestMaxHrChange(parsed)
            }
        )
    }

    // Max HR change confirmation dialog
    pendingMaxHr?.let { newMaxHr ->
        ConfirmDialog(
            title = "Change Max HR?",
            text = "New Max HR = $newMaxHr bpm will be used for all future file imports.\n\n" +
                "Existing session data (heart rate zones) remains based on " +
                "Max HR = $currentMaxHr bpm and cannot be updated without re-importing those files.",
            confirmLabel = "Confirm",
            onConfirm = { viewModel.confirmMaxHrChange() },
            onDismiss = { viewModel.cancelMaxHrChange() }
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
                title = "FTP: $currentFtp W today",
                subtitle = "Add FTP — each ride is judged against the FTP in force on its date",
                onClick = { ftpDialog = FtpDialogTarget.Add }
            )
            ftpEntries.sortedByDescending { it.effectiveDate ?: LocalDate.MIN }.forEach { entry ->
                SettingsRow(
                    icon = null,
                    title = "${entry.ftp} W",
                    subtitle = entry.effectiveDate?.toString() ?: "Before first test",
                    onClick = { ftpDialog = FtpDialogTarget.Edit(entry) }
                )
            }
            Text(
                text = "Adding or editing an earlier date reshapes Training Load from that date on. " +
                    "Power zones, intervals, sprints and tags of already-imported rides stay as computed at import.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            SettingsRow(
                icon = Icons.Default.FavoriteBorder,
                title = "Max Heart Rate",
                subtitle = "$currentMaxHr bpm",
                onClick = { showMaxHrDialog = true }
            )

            val homeSubtitle = if (homeDisplayName.isNotBlank()) {
                homeDisplayName
            } else {
                "${FormatUtils.formatDecimal(currentHomeLat, 5)}, ${FormatUtils.formatDecimal(currentHomeLon, 5)}"
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

            // Debug-only: issue #170 threshold-tuning review tool. Kept as a Settings row
            // (not an instrumented test) so it needs no androidTest install/uninstall cycle.
            if (BuildConfig.DEBUG) {
                SettingsRow(
                    icon = Icons.Default.BugReport,
                    title = "Dump ride tags (debug)",
                    subtitle = dumpStatus ?: "Write ride_tag_dump.csv to app files",
                    onClick = { viewModel.dumpSessionTagsForReview() }
                )
                SettingsRow(
                    icon = Icons.Default.BugReport,
                    title = "Apply re-tag (debug)",
                    subtitle = retagStatus ?: "Overwrite stored tags with the classifier's current output",
                    onClick = { viewModel.applyRetag() }
                )
            }

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
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
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
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Spacer(modifier = Modifier.size(24.dp)) // keep indented rows aligned with icon rows
        }
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

/**
 * Numeric-field edit dialog shared by the FTP and Max-HR editors: a labeled number field plus a
 * helper-text blurb, saving only when the trimmed input parses to a positive int different from
 * [currentValue] (otherwise the dialog just stays open, same as before extraction).
 */
@Composable
private fun NumberEditDialog(
    title: String,
    label: String,
    currentValue: Int,
    helperText: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var input by remember { mutableStateOf(currentValue.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(label) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = helperText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = input.trim().toIntOrNull()
                if (parsed != null && parsed > 0 && parsed != currentValue) {
                    onConfirm(parsed)
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
