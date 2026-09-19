package com.velometrics.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.velometrics.app.domain.model.FtpEntry
import com.velometrics.app.util.CyclingConstants
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** What the FTP dialog is open for: a new entry or an existing history row. */
internal sealed interface FtpDialogTarget {
    data object Add : FtpDialogTarget
    data class Edit(val entry: FtpEntry) : FtpDialogTarget
}

/**
 * Add/edit dialog for one FTP history entry (#218, ADR 0001). The date can't be in the future; the
 * seed ("Before first test") row keeps a null date and can't be deleted; saving on an existing
 * date overwrites that entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FtpEntryDialog(
    target: FtpDialogTarget,
    onDismiss: () -> Unit,
    onSave: (LocalDate?, Int) -> Unit,
    onDelete: (LocalDate) -> Unit
) {
    val existing = (target as? FtpDialogTarget.Edit)?.entry
    val isSeed = existing != null && existing.effectiveDate == null
    var date by remember { mutableStateOf(existing?.effectiveDate ?: LocalDate.now()) }
    var input by remember { mutableStateOf(existing?.ftp?.toString() ?: "") }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        val today = LocalDate.now()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            }
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add FTP" else "Edit FTP") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("FTP (W)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isSeed) {
                    Text("Effective: before first test", style = MaterialTheme.typography.bodyMedium)
                } else {
                    TextButton(onClick = { showPicker = true }) { Text("Effective from $date") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "The average power you can sustain for one hour. Defines power zones, sprint " +
                        "(≥${(CyclingConstants.SPRINT_THRESHOLD_FACTOR * 100).roundToInt()}% FTP) and interval " +
                        "(≥${(CyclingConstants.INTERVAL_THRESHOLD_FACTOR * 100).roundToInt()}% FTP) detection. " +
                        "Requires a power meter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = input.trim().toIntOrNull()
                if (parsed != null && parsed > 0) {
                    onSave(if (isSeed) null else date, parsed)
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                existing?.effectiveDate?.let { entryDate ->
                    TextButton(onClick = { onDelete(entryDate) }) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
