package com.drkkr.strategos.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.agent.AiSettingsUi
import com.drkkr.strategos.agent.KeyState
import com.drkkr.strategos.data.repo.UiMode
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.UiPalette
import com.drkkr.strategos.ui.components.rememberPalette
import com.drkkr.strategos.ui.theme.NeoTokens
import com.drkkr.strategos.ui.theme.StratTokens

/** Privacy text shown before AI is turned on (blueprint §11.5, with Groq as the provider). */
object AiConsentText {
    const val TITLE = "Turn on AI counsel?"
    val POINTS = listOf(
        "Your decisions, models and history stay encrypted on this phone.",
        "When you chat, your messages and a short summary of what's saved are sent to Groq, an AI company, to write replies. Groq's data policies apply.",
        "The analysis always runs on this phone. The AI helps build the model and never calculates the results.",
        "Don't include patient identifiers, names, addresses or anything you aren't allowed to share with a third party. Check your institution's data policy.",
        "You can turn AI off at any time. Strategos works fully offline without it."
    )
}

private const val KEYS_URL = "https://console.groq.com/keys"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(vm: StrategosViewModel) {
    val p = rememberPalette()
    val controller = vm.aiSettings
    val mode by vm.uiMode.collectAsState()
    val ui by (controller?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(AiSettingsUi()) }).collectAsState()
    LaunchedEffect(Unit) { controller?.load() }

    Column(Modifier.fillMaxSize().background(p.canvas).verticalScroll(rememberScrollState()).padding(StratTokens.screenPad), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", color = p.ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (controller == null) {
            Text("Secure storage isn't available on this phone, so AI counsel can't be set up.", color = p.muted)
            return@Column
        }

        // ── AI on/off ─────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI counsel", color = p.ink, style = MaterialTheme.typography.titleMedium)
                Text(if (ui.ready) "On" else "Off. Strategos works fully offline.", color = p.muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = ui.ready, onCheckedChange = { controller.setEnabled(it) }, enabled = ui.hasKey, modifier = Modifier.testTag("ai_toggle"))
        }

        // ── Privacy consent, inline right after a key passes its test ─────────
        if (ui.needsConsent) ConsentCard(p, onAccept = controller::acceptConsent, onDecline = { controller.setEnabled(false) })

        // ── Key ───────────────────────────────────────────────────────────────
        KeySection(ui, p, onSave = controller::saveAndConnect, onRemove = controller::remove)

        // ── Model ─────────────────────────────────────────────────────────────
        Text("Model", color = p.ink, style = MaterialTheme.typography.titleMedium)
        if (mode == UiMode.EXPERT && ui.hasKey) ModelPicker(ui, p, onSelect = controller::selectModel, onRefresh = { controller.refreshModels() })
        else Text(ui.model, color = p.muted, style = MaterialTheme.typography.bodyMedium)
        ui.modelMessage?.let { Text(it, color = p.muted, style = MaterialTheme.typography.bodySmall) }

        // ── Mode ──────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show details (for professionals)", color = p.ink, style = MaterialTheme.typography.titleMedium)
                Text("Adds the model tabs, the engine's reports and the AI input review.", color = p.muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = mode == UiMode.EXPERT, onCheckedChange = { vm.setUiMode(if (it) UiMode.EXPERT else UiMode.GUIDED) }, modifier = Modifier.testTag("expert_toggle"))
        }

        // ── Privacy and data ──────────────────────────────────────────────────
        var showPrivacy by remember { mutableStateOf(false) }
        TextButton(onClick = { showPrivacy = !showPrivacy }) { Text(if (showPrivacy) "Hide what is sent" else "What is sent and what stays on this phone") }
        if (showPrivacy) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { AiConsentText.POINTS.forEach { Text("• $it", color = p.muted, style = MaterialTheme.typography.bodySmall) } }

        var confirmDelete by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete all conversations") }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all conversations?") },
            text = { Text("Your messages with the AI will be removed from this phone. Your decisions and models stay.") },
            confirmButton = { TextButton(onClick = { vm.deleteAllConversations(); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
        Spacer(Modifier.height(StratTokens.dockTotalBottomPad))
    }
}

@Composable
private fun ConsentCard(p: UiPalette, onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.accent.copy(alpha = 0.10f)).border(1.dp, p.accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AiConsentText.TITLE, color = p.ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        AiConsentText.POINTS.forEach { Text("• $it", color = p.ink, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAccept, modifier = Modifier.testTag("consent_accept")) { Text("I understand") }
            OutlinedButton(onClick = onDecline) { Text("Keep AI off") }
        }
    }
}

@Composable
private fun KeySection(ui: AiSettingsUi, p: UiPalette, onSave: (String) -> Unit, onRemove: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LaunchedEffect(ui.keyState) { if (ui.keyState == KeyState.CONNECTED) { text = ""; changing = false } }   // never keep the key in memory
    val checking = ui.keyState == KeyState.CHECKING

    Text("Groq API key", color = p.ink, style = MaterialTheme.typography.titleMedium)
    if (ui.hasKey && !changing) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("●", color = NeoTokens.green)
            Text("Connected · key ending ${ui.keyLast4.orEmpty()}", color = p.ink, modifier = Modifier.weight(1f).testTag("key_connected"))
            TextButton(onClick = { changing = true }) { Text("Change") }
            TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
        }
        if (ui.message != null) Text(ui.message, color = p.muted, style = MaterialTheme.typography.bodySmall)
    } else {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, enabled = !checking, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("key_field"),
            label = { Text("Paste your Groq API key") },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "Hide key" else "Show key") } }
        )
        when (ui.keyState) {
            KeyState.REJECTED, KeyState.OFFLINE, KeyState.ERROR -> Text(ui.message.orEmpty(), color = NeoTokens.red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("key_error"))
            else -> if (ui.message != null) Text(ui.message, color = p.muted, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onSave(text) }, enabled = !checking && text.isNotBlank(), modifier = Modifier.testTag("key_save")) { Text("Save and connect") }
            if (checking) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Checking your key…", color = p.muted, style = MaterialTheme.typography.bodySmall) }
            if (changing && !checking) TextButton(onClick = { changing = false; text = "" }) { Text("Cancel") }
        }
        TextButton(onClick = { uri.openUri(KEYS_URL) }) { Text("Where do I get a key?") }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove your key?") },
        text = { Text("AI counsel will turn off. Your conversations stay on this phone.") },
        confirmButton = { TextButton(onClick = { onRemove(); confirmRemove = false }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(ui: AiSettingsUi, p: UiPalette, onSelect: (String) -> Unit, onRefresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var customId by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = ui.model, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().menuAnchor().testTag("model_field"),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) }
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ui.models.forEach { m ->
                    DropdownMenuItem(
                        text = { Column { Text((if (m.current) "✓ " else "") + m.id + if (m.recommended) "  · Recommended" else ""); Text(m.priceLabel, style = MaterialTheme.typography.labelSmall, color = p.muted) } },
                        onClick = { open = false; onSelect(m.id) }
                    )
                }
                DropdownMenuItem(text = { Text("Custom model id…") }, onClick = { open = false; custom = true })
            }
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh the model list") }
    }
    if (custom) AlertDialog(
        onDismissRequest = { custom = false },
        title = { Text("Custom model id") },
        text = { OutlinedTextField(value = customId, onValueChange = { customId = it }, singleLine = true, label = { Text("Model id") }) },
        confirmButton = { TextButton(onClick = { onSelect(customId); custom = false; customId = "" }, enabled = customId.isNotBlank()) { Text("Check and use") } },
        dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancel") } }
    )
}
