package com.drkkr.strategos.ui.calm.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.data.repo.RevealMath
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.CalmFonts
import com.drkkr.strategos.ui.calm.theme.SquircleShape
import com.drkkr.strategos.ui.calm.theme.calmCard
import com.drkkr.strategos.ui.calm.theme.link
import com.drkkr.strategos.ui.settings.AiConsentText

// Pastel tile family (iOS grouped-row icon tiles).
private val TileTeal = Color(0xFF3CB0AD)
private val TileSlate = Color(0xFF808AA6)

/** Settings (board 12): coach, reveal the math, privacy. Premium iOS grouped layout. */
@Composable
fun SettingsScreen(ui: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier, nudge: Boolean = false) {
    val c = Calm.colors
    var editingKey by remember { mutableStateOf(false) }
    var keyHelp by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Text("Settings", style = Calm.type.display.copy(fontSize = 32.sp, lineHeight = 38.sp), color = c.ink, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
        if (nudge && ui.keyStatus != KeyStatus.CONNECTED) Text(
            "Add your AI key to get started. Your coach can't answer until it's set, and nothing you write is sent before then.",
            color = c.ink, fontSize = 15.sp, lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(SquircleShape(16.dp)).background(c.selected).padding(14.dp)
        )
        if (!ui.available) {
            Text("Secure storage isn't available on this phone, so the coach can't be set up.", color = c.ink2, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
        } else if (ui.loaded) {
            Header("Your coach")
            Column(Modifier.padding(horizontal = 16.dp).calmCard(c)) {
                Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tile(c.action, Icons.Outlined.VpnKey)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AI counsel", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (ui.keyStatus == KeyStatus.MISSING) "Add a key below to turn this on." else "The coach writes your advice from what you tell it.",
                            color = c.ink3, fontSize = 13.sp, lineHeight = 19.sp
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
                else Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { editingKey = true }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI key", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Connected · ends in ${ui.keyLast4.orEmpty()}", color = c.trust, fontSize = 13.sp)
                    }
                    SquircleBtn("Change", filled = true) { editingKey = true }
                }
            }

            Header("Reveal the math")
            Column(Modifier.padding(horizontal = 16.dp).calmCard(c)) {
                Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tile(TileTeal, Icons.Outlined.Visibility)
                    Spacer(Modifier.width(12.dp))
                    Text("Show the engine's working", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                    Segmented(listOf(RevealMath.ON_TAP to "On tap", RevealMath.ALWAYS to "Always", RevealMath.NEVER to "Never"), ui.reveal, actions.onReveal)
                }
            }
            Text(SettingsUi.revealNote(ui.reveal), color = c.ink2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 7.dp))

            Header("Appearance")
            Column(Modifier.padding(horizontal = 16.dp).calmCard(c)) {
                Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tile(c.rigorGraphic, Icons.Outlined.Palette)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Theme", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Follows your phone's light or dark setting.", color = c.ink3, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }

            Header("Privacy")
            Column(Modifier.padding(horizontal = 16.dp).calmCard(c)) {
                InfoRow(TileSlate, Icons.Outlined.Lock, "What stays on this phone", "Your projects, memory and journal.", privacy == "phone") { privacy = if (privacy == "phone") null else "phone" }
                if (privacy == "phone") Note("Every project, your memory and all journal entries live in an encrypted database on this phone. Nothing is synced to an account.")
                Divider()
                InfoRow(c.action, Icons.Outlined.Visibility, "What the coach can see", "Only the conversation you send. Never your journal.", privacy == "coach") { privacy = if (privacy == "coach") null else "coach" }
                if (privacy == "coach") Note("Journal entries you haven't linked are never read for advice. A linked entry stays private context until you tap Apply on a takeaway.")
            }

            Header("")
            Box(Modifier.padding(horizontal = 16.dp)) {
                SquircleBtn("Delete everything", filled = true, full = true, danger = true, icon = Icons.Outlined.DeleteOutline) { deleting = true }
            }
            Text("Removes every project, memory and journal entry. Your key and settings stay.", color = c.ink3, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp))

            if (ui.keyStatus == KeyStatus.CONNECTED) TextButton(onClick = { confirmRemove = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
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

@Composable private fun Tile(bg: Color, icon: ImageVector) {
    Box(Modifier.size(29.dp).clip(SquircleShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/** Filled or tinted squircle button, iOS-style. */
@Composable private fun SquircleBtn(
    label: String, filled: Boolean, full: Boolean = false, danger: Boolean = false,
    icon: ImageVector? = null, onClick: () -> Unit
) {
    val c = Calm.colors
    val bg = when { danger -> c.tension; filled -> c.action; else -> c.selected }
    val fg = if (filled || danger) Color.White else c.link
    Box(
        (if (full) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 46.dp).clip(SquircleShape(15.dp)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = if (full) 0.dp else 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun Header(text: String) {
    if (text.isEmpty()) { Spacer(Modifier.height(24.dp)); return }
    Text(text, color = Calm.colors.ink2, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 7.dp))
}

@Composable private fun Divider() { Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(Calm.colors.line)) }

@Composable private fun Note(text: String) {
    Text(text, color = Calm.colors.ink2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp))
}

@Composable private fun InfoRow(tileBg: Color, icon: ImageVector, title: String, sub: String, open: Boolean, onClick: () -> Unit) {
    val c = Calm.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Tile(tileBg, icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = c.ink3, fontSize = 13.sp, lineHeight = 19.sp)
        }
        Text(if (open) "⌃" else "›", color = c.ink3, fontSize = 20.sp)
    }
}

/** iOS segmented control: squircle track, a sliding squircle thumb. */
@Composable private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    val c = Calm.colors
    Row(Modifier.fillMaxWidth().height(33.dp).clip(SquircleShape(9.dp)).background(c.selected).padding(2.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(SquircleShape(7.dp)).background(if (on) c.card else Color.Transparent).clickable { onPick(v) },
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (on) c.ink else c.ink2, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium) }
        }
    }
}

@Composable private fun ConsentBlock(actions: SettingsActions) {
    val c = Calm.colors
    Column(Modifier.fillMaxWidth().background(c.coach).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AiConsentText.TITLE, color = c.coachInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        AiConsentText.POINTS.forEach { Text("• $it", color = c.coachInk, fontSize = 14.sp, lineHeight = 20.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SquircleBtn("I understand", filled = true, onClick = actions.onAcceptConsent)
            SquircleBtn("Keep AI off", filled = false, onClick = actions.onDeclineConsent)
        }
    }
}

@Composable private fun KeyEditor(ui: SettingsUiState, help: Boolean, onHelp: () -> Unit, actions: SettingsActions, canClose: Boolean, onClose: () -> Unit) {
    val c = Calm.colors
    var text by remember { mutableStateOf("") }
    val error = ui.keyStatus == KeyStatus.ERROR
    val checking = ui.keyStatus == KeyStatus.CHECKING
    Column(Modifier.fillMaxWidth().background(if (error) c.errorBg else Color.Transparent).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI key", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(
            when {
                checking -> "Checking your key…"
                error -> ui.keyMessage ?: "Not accepted. Check it and try again."
                else -> "Paste the key you copied from your AI provider."
            },
            color = if (error) c.error else c.ink3, fontSize = 13.sp, lineHeight = 19.sp
        )
        Box(Modifier.fillMaxWidth().clip(SquircleShape(12.dp)).background(c.ground).border(1.dp, if (error) c.error else c.selectedBorder, SquircleShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val enabled = text.isNotBlank() && !checking
            Box(
                Modifier.heightIn(min = 46.dp).clip(SquircleShape(15.dp)).background(if (enabled) c.action else c.selected)
                    .clickable(enabled = enabled) { actions.onSaveKey(text); text = ""; onClose() }.padding(horizontal = 22.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("Test key", color = if (enabled) Color.White else c.ink3, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Box(Modifier.heightIn(min = 46.dp).clickable(onClick = onHelp).padding(horizontal = 10.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text("Where do I find it?", color = c.link, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (canClose) Box(Modifier.weight(1f).heightIn(min = 46.dp).clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.CenterEnd) {
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
        Column(Modifier.clip(SquircleShape(26.dp)).background(c.card).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Delete everything?", color = c.ink, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("This removes every project, topic, memory and journal entry from this phone. It can't be undone.", color = c.ink2, fontSize = 16.sp, lineHeight = 24.sp)
            Text("Type DELETE to confirm", color = c.ink3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().clip(SquircleShape(12.dp)).background(c.ground).border(1.dp, c.ink3, SquircleShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (typed.isEmpty()) Text("DELETE", color = c.ink3, fontFamily = CalmFonts.Mono, fontSize = 16.sp, letterSpacing = 1.2.sp)
                BasicTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true, cursorBrush = SolidColor(c.action),
                    textStyle = Calm.type.body.copy(color = c.ink, fontFamily = CalmFonts.Mono, fontSize = 16.sp, letterSpacing = 1.2.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(Modifier.fillMaxWidth().height(50.dp).clip(SquircleShape(16.dp)).background(c.action).clickable(onClick = onKeep), contentAlignment = Alignment.Center) {
                Text("Keep everything", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(SquircleShape(16.dp)).background(if (ok) c.tension else c.errorBg).clickable(enabled = ok, onClick = onDelete),
                contentAlignment = Alignment.Center
            ) { Text("Delete", color = if (ok) Color.White else c.ink3, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
