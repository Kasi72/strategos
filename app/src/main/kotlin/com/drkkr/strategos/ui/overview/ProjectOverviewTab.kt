package com.drkkr.strategos.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.agent.ChatItem
import com.drkkr.strategos.agent.ThreadUi
import com.drkkr.strategos.data.db.entities.JournalInsightPostNoteEntity
import com.drkkr.strategos.data.db.entities.TrajectoryPointEntity
import com.drkkr.strategos.domain.analysis.TrajectorySnapshot
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.UiPalette
import com.drkkr.strategos.ui.components.rememberPalette
import com.drkkr.strategos.ui.counsel.NewThreadDialog
import com.drkkr.strategos.ui.theme.GradientPillButton
import com.drkkr.strategos.ui.theme.NeoChip
import com.drkkr.strategos.ui.theme.NeoGlassCard
import com.drkkr.strategos.ui.theme.NeoTokens
import com.drkkr.strategos.ui.theme.StratTokens
import java.text.DateFormat
import java.util.Date
import kotlin.math.exp

private val Coral = Color(0xFFFF6B5B)

/** Space 3: where the project sits, what the journal added, a 10-second check-in, and the project's threads. */
@Composable
fun ProjectOverviewTab(vm: StrategosViewModel, onOpenThread: () -> Unit, onOpenSummary: () -> Unit, onGoHome: () -> Unit) {
    val ui by vm.counselState.collectAsState()
    val app by vm.state.collectAsState()
    val p = rememberPalette()
    val counsel = vm.counsel
    val scenario = app.activeScenario
    if (scenario == null || counsel == null) {
        Column(Modifier.fillMaxSize().background(p.canvas).padding(StratTokens.screenPad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Pick a project first", color = p.ink, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGoHome) { Text("Go to Projects") }
        }
        return
    }
    val snap = ui.trajectory
    var newThread by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().background(p.canvas).testTag("overview_list"),
        contentPadding = PaddingValues(StratTokens.screenPad), verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(scenario.title.ifBlank { "Your project" }, color = p.ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f))
                snap?.let { NeoChip(it.phaseLabel, phaseColor(it)) }
            }
        }
        items(ui.postNotes.take(2), key = { it.id }) { note ->
            JournalPostNoteCard(note, p, onApply = { counsel.applyJournalPostNote(note.id) }, onDismiss = { counsel.dismissJournalPostNote(note.id) })
        }
        item { SigmoidTrajectoryCard(snap, ui.trajectoryPoints, p) }
        item {
            QuickCheckCard(p) { exec, reaction, feeling, note -> counsel.submitQuickCheck(exec, reaction, feeling, note) }
        }
        item { ClearWinPlaybookCard(ui.items.filterIsInstance<ChatItem.AssistantText>().lastOrNull { "Clear-win move:" in it.text }?.text, p) }
        item {
            ProjectThreadsSection(
                threads = ui.threads, activeId = ui.conversationId, p = p,
                onOpen = { id -> counsel.selectThread(id); onOpenThread() }, onNew = { newThread = true }
            )
        }
        item { TextButton(onClick = onOpenSummary) { Text("Full summary") } }
    }
    if (newThread) NewThreadDialog(onDismiss = { newThread = false }, onCreate = { title, tag -> counsel.createNamedThread(title, tag); newThread = false; onOpenThread() })
}

private fun phaseColor(s: TrajectorySnapshot): Color = when {
    s.phaseLabel.startsWith("Fragile") -> Coral
    s.phaseLabel.startsWith("Early") -> NeoTokens.amber
    s.phaseLabel.startsWith("Tipping") -> NeoTokens.teal
    else -> NeoTokens.green
}

@Composable
internal fun JournalPostNoteCard(note: JournalInsightPostNoteEntity, p: UiPalette, onApply: () -> Unit, onDismiss: () -> Unit) {
    NeoGlassCard(Modifier.fillMaxWidth().testTag("post_note_card"), accentColor = NeoTokens.amber) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("💡 ${note.insightHeadline}", color = p.ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            if (note.practicalApproach.isNotBlank()) Text(note.practicalApproach, color = p.muted, style = MaterialTheme.typography.bodyMedium)
            if (note.doThis.isNotBlank()) Text("✅ Do this: ${note.doThis}", color = p.ink, style = MaterialTheme.typography.bodyMedium)
            if (note.dontDoThis.isNotBlank()) Text("🚫 Don't do this: ${note.dontDoThis}", color = p.ink, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = onApply, modifier = Modifier.testTag("post_note_apply")) { Text("Apply to Playbook") }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("post_note_dismiss")) { Text("Dismiss") }
            }
        }
    }
}

@Composable
internal fun SigmoidTrajectoryCard(snap: TrajectorySnapshot?, points: List<TrajectoryPointEntity>, p: UiPalette) {
    NeoGlassCard(Modifier.fillMaxWidth().testTag("trajectory_card"), accentColor = NeoTokens.teal) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Where things sit", color = p.ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val history = points.takeLast(MAX_HISTORY).map { it.sigmoidProgress }.ifEmpty { listOfNotNull(snap?.sigmoidProgress) }
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                val steps = history.size - 1 + PROJECTION_STEPS
                val w = size.width; val h = size.height
                fun x(i: Int) = if (steps <= 0) w / 2f else w * i / steps
                fun y(v: Double) = h - (v.toFloat().coerceIn(0f, 100f) / 100f) * h
                // The S-curve backdrop.
                val curve = Path()
                for (i in 0..40) {
                    val t = i / 40f
                    val py = y(100.0 / (1.0 + exp(-0.09 * ((t - 0.5) * 100.0))))
                    if (i == 0) curve.moveTo(w * t, py) else curve.lineTo(w * t, py)
                }
                drawPath(curve, p.muted.copy(alpha = 0.25f), style = Stroke(width = 3f))
                drawLine(p.muted.copy(alpha = 0.2f), Offset(0f, y(50.0)), Offset(w, y(50.0)), strokeWidth = 1f)
                if (history.isEmpty() || snap == null) return@Canvas
                val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                val last = history.size - 1
                fun projection(values: List<Double>, color: Color) {
                    val path = Path().apply {
                        moveTo(x(last), y(history.last()))
                        values.forEachIndexed { k, v -> lineTo(x(last + 1 + k), y(v)) }
                    }
                    drawPath(path, color, style = Stroke(width = 4f, pathEffect = dash))
                }
                projection(snap.positiveProjection, NeoTokens.green)
                projection(snap.negativeProjection, Coral)
                val path = Path()
                history.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
                drawPath(path, NeoTokens.blueCore, style = Stroke(width = 6f))
                drawCircle(NeoTokens.blueCore, radius = 10f, center = Offset(x(last), y(history.last())))
            }
            if (snap != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Legend("↗ If you hold this behavior", NeoTokens.green)
                    Legend("↘ If reactive habits repeat", Coral)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NeoChip("Trust τ ${snap.trustScore.toInt()}/100", NeoTokens.green)
                    NeoChip("Tension ε ${snap.tensionScore.toInt()}/100", Coral)
                    NeoChip("Streak ${snap.consistencyStreak} turns", NeoTokens.blueCore)
                }
            } else {
                Text("Log a conversation or do a quick check-in and the curve starts here.", color = p.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Legend(text: String, color: Color) = Text(text, color = color, style = MaterialTheme.typography.labelSmall)

private const val MAX_HISTORY = 12
private const val PROJECTION_STEPS = 5

private val EXECUTION = listOf("DID_AS_SUGGESTED" to "Did as suggested", "TRIED_VARIATION" to "Tried a variation", "SKIPPED_REACTIVE" to "Reacted instead")
private val REACTION = listOf("DE_ESCALATED" to "Calmer", "NEUTRAL_IGNORED" to "No change", "ESCALATED_ATTACKED" to "Got heated")
private val FEELING = listOf("GROUNDED" to "Grounded", "ANXIOUS_REGRETFUL" to "Anxious or regretful")

@Composable
internal fun QuickCheckCard(p: UiPalette, onSubmit: (String, String, String, String) -> Unit) {
    var exec by remember { mutableStateOf<String?>(null) }
    var reaction by remember { mutableStateOf<String?>(null) }
    var feeling by remember { mutableStateOf<String?>(null) }
    NeoGlassCard(Modifier.fillMaxWidth().testTag("quick_check_card"), accentColor = NeoTokens.blueCore) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How did the last interaction go?", color = p.ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ChipRow("What you did", EXECUTION, exec, p) { exec = it }
            ChipRow("Their reaction", REACTION, reaction, p) { reaction = it }
            ChipRow("How you feel", FEELING, feeling, p) { feeling = it }
            GradientPillButton(
                text = "Update Trajectory", enabled = exec != null && reaction != null && feeling != null,
                onClick = { onSubmit(exec!!, reaction!!, feeling!!, ""); exec = null; reaction = null; feeling = null },
                modifier = Modifier.fillMaxWidth().testTag("quick_check_submit"), height = 44.dp
            )
        }
    }
}

@Composable
private fun ChipRow(title: String, options: List<Pair<String, String>>, selected: String?, p: UiPalette, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = p.muted, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (key, label) ->
                NeoChip(label, if (key == selected) NeoTokens.blueCore else p.muted, glowing = key == selected, modifier = Modifier.clickable { onSelect(key) })
            }
        }
    }
}

@Composable
internal fun ClearWinPlaybookCard(guideText: String?, p: UiPalette) {
    NeoGlassCard(Modifier.fillMaxWidth().testTag("playbook_card"), accentColor = NeoTokens.green) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Clear-win playbook", color = p.ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (guideText == null) {
                Text("Tell me what's happening in a thread and your next move shows up here.", color = p.muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                // The guide runs from "What's happening underneath:" to the closing note.
                val body = guideText.substring(guideText.indexOf("What's happening underneath:").coerceAtLeast(0)).substringBefore("This is a best guess").trim()
                Text(body, color = p.ink, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun ProjectThreadsSection(threads: List<ThreadUi>, activeId: String?, p: UiPalette, onOpen: (String) -> Unit, onNew: () -> Unit) {
    NeoGlassCard(Modifier.fillMaxWidth().testTag("threads_card"), accentColor = NeoTokens.purple) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Conversations in this project", color = p.ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onNew, modifier = Modifier.testTag("new_thread")) { Icon(Icons.Default.Add, null); Text("New Named Thread") }
            }
            threads.forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (t.pinned) "📌 " else "") + t.title, color = p.ink, fontWeight = if (t.id == activeId) FontWeight.SemiBold else FontWeight.Normal)
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t.updatedAt)), color = p.muted, style = MaterialTheme.typography.labelSmall)
                    }
                    NeoChip(t.topicTag, NeoTokens.purple)
                }
            }
        }
    }
}
