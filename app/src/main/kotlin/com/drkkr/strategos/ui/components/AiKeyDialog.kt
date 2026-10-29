package com.drkkr.strategos.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared dialog for entering/removing the Anthropic API key that powers AI
 * scenario extraction. The key is stored only in the device's encrypted storage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiKeyDialog(
    hasKey: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text("Anthropic API key", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    "Paste your Anthropic API key to enable AI scenario extraction. " +
                        "It is stored only in this device's encrypted storage and sent " +
                        "directly to the Anthropic API. Without a key the app uses " +
                        "on-device parsing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("sk-ant-…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (key.isNotBlank()) onSave(key) },
                enabled = key.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Save") }
        },
        dismissButton = {
            if (hasKey) TextButton(
                onClick = onClear,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Remove key") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
