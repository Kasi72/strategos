package com.drkkr.strategos.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.drkkr.strategos.domain.agent.AiReview
import com.drkkr.strategos.domain.agent.ReviewItem
import com.drkkr.strategos.domain.agent.ReviewKind
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.ProvenanceChip
import com.drkkr.strategos.ui.components.rememberPalette
import com.drkkr.strategos.ui.theme.StratTokens

/** Full-screen list of every AI-inferred value, with Keep / Confirm / Remove (blueprint §11.6). Saves through the ViewModel. */
@Composable
fun ReviewInferredScreen(vm: StrategosViewModel, onClose: () -> Unit) {
    val p = rememberPalette()
    val app by vm.state.collectAsState()
    val scenario = app.activeScenario
    val items = scenario?.let(AiReview::items).orEmpty()
    var confirmAll by remember { mutableStateOf(false) }

    fun apply(change: (com.drkkr.strategos.domain.model.Scenario) -> com.drkkr.strategos.domain.model.Scenario) {
        val s = vm.state.value.activeScenario ?: return
        vm.updateActiveScenario(change(s))
        vm.saveScenario()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(p.canvas).padding(StratTokens.screenPad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Review AI inputs", color = p.ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.testTag("review_close")) { Text("Done") }
            }
            Text(
                if (items.isEmpty()) "Nothing left to review. Every value is yours or confirmed." else "${items.size} values were guessed by the AI. Keep what looks right, remove what doesn't.",
                color = p.muted, style = MaterialTheme.typography.bodyMedium
            )
            if (items.isNotEmpty()) OutlinedButton(onClick = { confirmAll = true }) { Text("Accept all as my estimates") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.kind.name + it.key }) { item ->
                    ReviewRow(item, p.ink, p.muted, p.raised, p.border,
                        onKeep = { apply { AiReview.keepAsMine(it, item) } },
                        onConfirm = { apply { AiReview.confirm(it, item) } },
                        onRemove = { apply { AiReview.remove(it, item) } })
                }
            }
        }
    }
    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text("Accept all as your estimates?") },
        text = { Text("This marks every AI guess as your own estimate. The engine will then treat them as yours.") },
        confirmButton = { TextButton(onClick = { apply(AiReview::acceptAll); confirmAll = false }) { Text("Accept all") } },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } }
    )
}

@Composable
private fun ReviewRow(
    item: ReviewItem, ink: androidx.compose.ui.graphics.Color, muted: androidx.compose.ui.graphics.Color, raised: androidx.compose.ui.graphics.Color,
    border: androidx.compose.ui.graphics.Color, onKeep: () -> Unit, onConfirm: () -> Unit, onRemove: () -> Unit
) {
    val group = when (item.kind) { ReviewKind.PERSON -> "Person"; ReviewKind.OPTION -> "Option"; ReviewKind.LIMIT -> "Limit"; ReviewKind.OUTCOME -> "Outcome"; ReviewKind.BELIEF -> "Likelihood" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(raised).border(1.dp, border, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ProvenanceChip(FactStatus.AI_INFERRED)
            Text(group, color = muted, style = MaterialTheme.typography.labelSmall)
        }
        Text(item.title, color = ink, style = MaterialTheme.typography.bodyLarge)
        if (item.note.isNotBlank()) Text(item.note, color = muted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onKeep) { Text("Keep as my estimate") }
            TextButton(onClick = onConfirm) { Text("Confirm") }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}
