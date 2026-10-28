package com.drkkr.strategos.ui.calm.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.agent.AiSettingsUi
import com.drkkr.strategos.data.repo.RevealMath
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.link
import com.drkkr.strategos.ui.calm.theme.CalmFonts
import com.drkkr.strategos.ui.calm.theme.calmCard
import com.drkkr.strategos.ui.settings.AiConsentText

/** Settings (board 12): coach, reveal the math, privacy. Stateless. */
@Composable
fun SettingsScreen(ui: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier, nudge: Boolean = false) {
    val c = Calm.colors
    var editingKey by remember { mutableStateOf(false) }
    var keyHelp by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Settings", style = Calm.type.display.copy(fontSize = 28.sp, lineHeight = 34.sp), color = c.ink, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        if (nudge && ui.keyStatus != KeyStatus.CONNECTED) Text(
            "Add your AI key to get started. Your coach can't answer until it's set, and nothing you write is sent before then.",
            color = c.ink, fontSize = 15.sp, lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.selected).padding(14.dp)
        )
        if (!ui.available) {
            Text("Secure storage isn't available on this phone, so the coach can't be set up.", color = c.ink2, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
        } else if (ui.loaded) {
            Header("Your coach")
            Column(Modifier.padding(horizontal = 16.dp).calmCard(c, RoundedCornerShape(20.dp))) {
                Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI counsel", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (ui.keyStatus == KeyStatus.MISSING) "Add a key below to turn this on." else "The coach writes your advice from what you tell it.",
                            color = c.ink3, fontSize = 14.sp, lineHeight = 20.sp
                        )
                    }
                    Switch(
                        checked = ui.aiOn, onCheckedChange = actions.onToggleAi, enabled = ui.keyStatus != KeyStatus.MISSING,
                        colors = SwitchDefaults.colors(checkedTrackColor = c.action, checkedThumbColor = Color.White)
                    )
                }
                if (ui.needsConsent) ConsentBlock(actions)

                Divider()
                val showEditor = editingKey || ui.keyStatus == KeyStatus.ERROR || ui.keyStatus == KeyStatus.MISSING || ui.keyStatus == KeyStatus.CHECKING
                if (showEditor) KeyEditor(ui, keyHelp, { keyHelp = !keyHelp }, actions, canClose = ui.keyStatus == KeyStatus.CONNECTED) { editingKey = false }
                else Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { editingKey = true }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI key", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("✓ Connected · ends in ${ui.keyLast4.orEmpty()}", color = c.trust, fontSize = 14.sp)
                    }
                    Text("Change", color = c.link, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }

            }

            Header("Reveal the math")
            Segmented(
                listOf(RevealMath.ALWAYS to "Always", RevealMath.ON_TAP to "On tap", RevealMath.NEVER to "Never"), ui.reveal, actions.onReveal, 56.dp
            )
            Text(SettingsUi.revealNote(ui.reveal), color = c.ink2, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp))

            Header("Appearance")
            Text("Follows your phone's light or dark setting.", color = c.ink2, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 32.dp))

            Header("Privacy")
            Column(Modifier.padding(horizontal = 16.dp).calmCard(c, RoundedCornerShape(20.dp))) {
                InfoRow("What stays on this phone", "Your projects, memory and journal.", privacy == "phone") { privacy = if (privacy == "phone") null else "phone" }
                if (privacy == "phone") Note("Every project, your memory and all journal entries live in an encrypted database on this phone. Nothing is synced to an account.")
                Divider()
                InfoRow("What the coach can see", "Only the conversation you send. Never your journal.", privacy == "coach") { privacy = if (privacy == "coach") null else "coach" }
                if (privacy == "coach") Note("Journal entries you haven't linked are never read for advice. A linked entry stays private context until you tap Apply on a takeaway.")
                Divider()
                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { deleting = true }.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                    Text("Delete everything", color = c.tension, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Removes all projects, memory and journal entries.", color = c.ink3, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            if (ui.keyStatus == KeyStatus.CONNECTED) TextButton(onClick = { confirmRemove = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Remove my key", color = c.ink3, fontSize = 14.sp)
            }
        }
    }

    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove your key?") },
        text = { Text("The coach turns off. Your conversations stay on this phone.") },
        confirmButton = { TextButton(onClick = { actions.onRemoveKey(); confirmRemove = false }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
    )
    if (deleting) DeleteDialog(onKeep = { deleting = false }, onDelete = { deleting = false; actions.onDeleteEverything() })
}

@Composable private fun Header(text: String) {
    Text(text.uppercase(), color = Calm.colors.ink3, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp))
}

@Composable private fun Divider() { Box(Modifier.fillMaxWidth().height(1.dp).background(Calm.colors.line)) }

@Composable private fun Note(text: String) {
    Text(text, color = Calm.colors.ink2, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
}

@Composable private fun InfoRow(title: String, sub: String, open: Boolean, onClick: () -> Unit) {
    val c = Calm.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = c.ink3, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Text(if (open) "⌃" else "›", color = c.ink3, fontSize = 20.sp)
    }
}

@Composable private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, height: androidx.compose.ui.unit.Dp) {
    val c = Calm.colors
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(height).clip(RoundedCornerShape(28.dp)).background(c.paper2).border(1.dp, c.cardBorder, RoundedCornerShape(28.dp)).padding(4.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(24.dp)).background(if (on) c.card else androidx.compose.ui.graphics.Color.Transparent).clickable { onPick(v) },
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (on) c.ink else c.ink2, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium) }
        }
    }
}

@Composable private fun ConsentBlock(actions: SettingsActions) {
    val c = Calm.colors
    Column(Modifier.fillMaxWidth().background(c.coach).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AiConsentText.TITLE, color = c.coachInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        AiConsentText.POINTS.forEach { Text("• $it", color = c.coachInk, fontSize = 14.sp, lineHeight = 20.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("I understand", true, actions.onAcceptConsent)
            Pill("Keep AI off", false, actions.onDeclineConsent)
        }
    }
}

@Composable private fun Pill(text: String, primary: Boolean, onClick: () -> Unit) {
    val c = Calm.colors
    Box(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (primary) c.action else c.card).border(1.dp, if (primary) c.action else c.cardBorder, RoundedCornerShape(24.dp)).clickable(onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (primary) Color.White else c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable private fun KeyEditor(ui: SettingsUiState, help: Boolean, onHelp: () -> Unit, actions: SettingsActions, canClose: Boolean, onClose: () -> Unit) {
    val c = Calm.colors
    var text by remember { mutableStateOf("") }
    val error = ui.keyStatus == KeyStatus.ERROR
    val checking = ui.keyStatus == KeyStatus.CHECKING
    Column(Modifier.fillMaxWidth().background(if (error) c.errorBg else c.paper2).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI key", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(
            when {
                checking -> "Checking your key…"
                error -> ui.keyMessage ?: "Not accepted. Check it and try again."
                else -> "Paste the key you copied from your AI provider."
            },
            color = if (error) c.error else c.ink3, fontSize = 14.sp, lineHeight = 20.sp
        )
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).border(1.5.dp, if (error) c.error else c.selectedBorder, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (text.isEmpty()) Text("Paste key", color = c.ink3, fontFamily = CalmFonts.Mono, fontSize = 15.sp)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true, enabled = !checking,
                textStyle = Calm.type.body.copy(color = c.ink, fontFamily = CalmFonts.Mono, fontSize = 15.sp), cursorBrush = SolidColor(c.action),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (help) Text("Your key is stored only on this phone, encrypted, and used when you send a message in Talk.", color = c.ink2, fontSize = 13.sp, lineHeight = 19.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (text.isBlank() || checking) c.line else c.action)
                    .clickable(enabled = text.isNotBlank() && !checking) { actions.onSaveKey(text); text = ""; onClose() }.padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) { Text("Test key", color = if (text.isBlank() || checking) c.ink3 else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Box(Modifier.height(48.dp).clickable(onClick = onHelp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text("Where do I find it?", color = c.link, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (canClose) Box(Modifier.weight(1f).height(48.dp).clickable(onClick = onClose).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterEnd) {
                Text("Close", color = c.ink3, fontSize = 14.sp)
            }
        }
        if (help) TextButton(onClick = actions.onOpenKeyHelp) { Text("Open the key page", color = c.link) }
    }
}


@Composable private fun DeleteDialog(onKeep: () -> Unit, onDelete: () -> Unit) {
    val c = Calm.colors
    var typed by remember { mutableStateOf("") }
    val ok = SettingsUi.canDelete(typed)
    androidx.compose.ui.window.Dialog(onDismissRequest = onKeep) {
        Column(Modifier.clip(RoundedCornerShape(28.dp)).background(c.paper).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Delete everything?", color = c.ink, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("This removes every project, topic, memory and journal entry from this phone. It can't be undone.", color = c.ink2, fontSize = 16.sp, lineHeight = 24.sp)
            Text("Type DELETE to confirm", color = c.ink3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).border(1.5.dp, c.ink3, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (typed.isEmpty()) Text("DELETE", color = c.ink3, fontFamily = CalmFonts.Mono, fontSize = 16.sp, letterSpacing = 1.2.sp)
                BasicTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true, cursorBrush = SolidColor(c.action),
                    textStyle = Calm.type.body.copy(color = c.ink, fontFamily = CalmFonts.Mono, fontSize = 16.sp, letterSpacing = 1.2.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(c.action).clickable(onClick = onKeep), contentAlignment = Alignment.Center) {
                Text("Keep everything", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(if (ok) c.tension else c.errorBg).clickable(enabled = ok, onClick = onDelete),
                contentAlignment = Alignment.Center
            ) { Text("Delete", color = if (ok) Color.White else c.ink3, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
