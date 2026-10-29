package com.drkkr.strategos.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.data.repo.JournalEntryView
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.UiPalette
import com.drkkr.strategos.ui.components.rememberPalette
import com.drkkr.strategos.ui.components.rememberVoiceInput
import com.drkkr.strategos.ui.theme.GradientPillButton
import com.drkkr.strategos.ui.theme.NeoChip
import com.drkkr.strategos.ui.theme.NeoGlassCard
import com.drkkr.strategos.ui.theme.NeoTokens
import com.drkkr.strategos.ui.theme.StratTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val MOODS = listOf("CALM" to "Calm", "ANXIOUS" to "Anxious", "FRUSTRATED" to "Frustrated", "HOPEFUL" to "Hopeful", "DRAINED" to "Drained")

/**
 * Space 2. A quiet place to write. Saving shows only "Recorded privately 🍃": no reply, no chat bubbles, no feedback
 * on what was written. Whatever the background screener finds shows up on the project's Overview, never here.
 */
@Composable
fun JournalTab(vm: StrategosViewModel) {
    val ui by vm.counselState.collectAsState()
    val app by vm.state.collectAsState()
    val p = rememberPalette()
    val counsel = vm.counsel
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf<String?>(null) }
    var projectId by remember { mutableStateOf<String?>(null) }       // null = All Projects
    val titles = app.scenarios.associate { it.id to it.title.ifBlank { "Untitled" } }

    LaunchedEffect(ui.journalAck) {
        if (ui.journalAck != null) { delay(ACK_MS); counsel?.clearJournalAck() }
    }

    Column(Modifier.fillMaxSize().background(p.canvas).imePadding()) {
        Column(Modifier.padding(horizontal = StratTokens.screenPad, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Private Reflection Journal", color = p.ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Write what you feel and how you want to grow. Never judged.", color = p.muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (counsel == null) {
            Text("The journal needs secure storage on this phone.", color = p.muted, modifier = Modifier.padding(StratTokens.screenPad))
            return@Column
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("journal_list"),
            contentPadding = PaddingValues(horizontal = StratTokens.screenPad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Tags(titles, projectId, { projectId = it }, mood, { mood = it }, p) }
            item {
                NeoGlassCard(Modifier.fillMaxWidth(), accentColor = NeoTokens.teal) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth().testTag("journal_input"),
                            placeholder = { Text("What's on your heart?") }, minLines = 4, maxLines = 10
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val voice = rememberVoiceInput(onText = { spoken -> draft = (draft + " " + spoken).trim() })
                            IconButton(onClick = voice.start, modifier = Modifier.testTag("journal_mic")) {
                                Icon(Icons.Default.Mic, if (voice.listening) "Listening" else "Speak", tint = if (voice.listening) NeoTokens.red else p.muted)
                            }
                            GradientPillButton(
                                text = "Record Reflection", enabled = draft.isNotBlank(), height = 44.dp,
                                modifier = Modifier.weight(1f).testTag("journal_record"),
                                onClick = {
                                    val text = draft; val m = mood ?: "NEUTRAL"; val pid = projectId
                                    draft = ""; mood = null
                                    scope.launch { counsel.recordJournalEntry(text, m, pid) }
                                }
                            )
                        }
                        ui.journalAck?.let { Text(it, color = NeoTokens.teal, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("journal_ack")) }
                    }
                }
            }
            items(ui.journalEntries, key = { it.id }) { Entry(it, titles, p) }
        }
    }
}

private const val ACK_MS = 4_000L

@Composable
private fun Tags(titles: Map<String, String>, projectId: String?, onProject: (String?) -> Unit, mood: String?, onMood: (String?) -> Unit, p: UiPalette) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("About", color = p.muted, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            NeoChip("All Projects", if (projectId == null) NeoTokens.teal else p.muted, glowing = projectId == null, modifier = Modifier.clickable { onProject(null) })
            titles.entries.take(3).forEach { (id, title) ->
                NeoChip(title.take(18), if (projectId == id) NeoTokens.teal else p.muted, glowing = projectId == id, modifier = Modifier.clickable { onProject(id) })
            }
        }
        Text("Feeling (optional)", color = p.muted, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            MOODS.forEach { (key, label) ->
                NeoChip(label, if (mood == key) NeoTokens.purple else p.muted, glowing = mood == key, modifier = Modifier.clickable { onMood(if (mood == key) null else key) })
            }
        }
    }
}

@Composable
private fun Entry(e: JournalEntryView, titles: Map<String, String>, p: UiPalette) {
    NeoGlassCard(Modifier.fillMaxWidth(), accentColor = NeoTokens.blueCore) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.createdAt)), color = p.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                e.linkedScenarioId?.let { id -> titles[id]?.let { NeoChip(it.take(18), NeoTokens.teal) } }
                if (e.moodTag != "NEUTRAL") NeoChip(e.moodTag.lowercase().replaceFirstChar { it.uppercase() }, NeoTokens.purple)
            }
            Text(e.text, color = p.ink, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
