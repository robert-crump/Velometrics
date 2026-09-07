package com.velometrics.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Title/body/confirm-or-cancel dialog shared across confirmation flows — originally the
 * FTP-change, Max-HR-change, and recalculate confirmations in Settings, promoted here (#193) so
 * Session Detail's and Home's delete-ride confirmations (#193/#194) can reuse the same shell
 * instead of duplicating another AlertDialog wrapper.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
