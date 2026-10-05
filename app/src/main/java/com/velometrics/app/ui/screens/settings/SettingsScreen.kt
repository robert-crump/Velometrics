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
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Scale
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
    val riderWeightKg by viewModel.riderWeightKg.collectAsState(initial = null)
    val bikeKitWeightKg by viewModel.bikeKitWeightKg.collectAsState(
        initial = CyclingConstants.DEFAULT_BIKE_KIT_WEIGHT_KG
    )
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
    var showRiderWeightDialog by remember { mutableStateOf(false) }
    var showBikeKitWeightDialog by remember { mutableStateOf(false) }
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

    if (showRiderWeightDialog) {
        val range = CyclingConstants.RIDER_WEIGHT_RANGE_KG
        NumberEditDialog(
            title = "Rider weight",
            label = "Body weight (kg)",
            currentValue = riderWeightKg,
            validRange = range,
            helperText = "Plus bike + kit, used by Speed IQ to turn braking into lost seconds " +
                "(${range.first}–${range.last} kg). Each ride keeps the weight it was imported with; " +
                "a change applies to future imports only.",
            onDismiss = { showRiderWeightDialog = false },
            onConfirm = { parsed ->
                showRiderWeightDialog = false
                viewModel.saveRiderWeight(parsed)
            }
        )
    }

    if (showBikeKitWeightDialog) {
        val range = CyclingConstants.BIKE_KIT_WEIGHT_RANGE_KG
        NumberEditDialog(
            title = "Bike + kit weight",
            label = "Bike + kit (kg)",
            currentValue = bikeKitWeightKg,
            validRange = range,
            helperText = "Bike, bottles, bags and clothing (${range.first}–${range.last} kg). " +
                "Added to the rider weight for Speed IQ; a change applies to future imports only.",
            onDismiss = { showBikeKitWeightDialog = false },
            onConfirm = { parsed ->
                showBikeKitWeightDialog = false
                viewModel.saveBikeKitWeight(parsed)
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
                icon = Icons.Default.Scale,
                title = "Rider weight",
                subtitle = riderWeightKg?.let { "$it kg" }
                    ?: "Not set — Speed IQ assumes ${CyclingConstants.SPEED_IQ_DEFAULT_SYSTEM_MASS_KG.roundToInt()} kg " +
                        "rider + bike + kit",
                onClick = { showRiderWeightDialog = true }
            )

            SettingsRow(
                icon = Icons.Default.PedalBike,
                title = "Bike + kit weight",
                subtitle = "$bikeKitWeightKg kg",
                onClick = { showBikeKitWeightDialog = true }
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
 * Numeric-field edit dialog shared by the Max-HR and system-weight editors: a labeled number field
 * plus a helper-text blurb, saving only when the trimmed input parses to an int in [validRange]
 * different from [currentValue] (null = unset, empty field); otherwise the dialog just stays open.
 */
@Composable
private fun NumberEditDialog(
    title: String,
    label: String,
    currentValue: Int?,
    helperText: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    validRange: IntRange = 1..Int.MAX_VALUE
) {
    var input by remember { mutableStateOf(currentValue?.toString() ?: "") }
    val parsed = input.trim().toIntOrNull()
    val outOfRange = parsed != null && parsed !in validRange
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(label) },
                    isError = outOfRange,
                    supportingText = if (outOfRange) {
                        { Text("Must be ${validRange.first}–${validRange.last}") }
                    } else null,
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
                if (parsed != null && parsed in validRange && parsed != currentValue) {
                    onConfirm(parsed)
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
