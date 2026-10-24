package com.drkkr.strategos.ui.counsel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.ChatItem
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.rememberPalette
import com.drkkr.strategos.ui.theme.NeoTokens
import com.drkkr.strategos.ui.theme.StratTokens

/**
 * The Counsel ("Talk") screen. SIMPLE PROFILE: the user writes, the app answers with a plain result, and the next message
 * is a follow-up. Guided mode shows only the conversation; Expert mode adds a model strip and the engine result card.
 */
@Composable
fun CounselTab(vm: StrategosViewModel, expert: Boolean, onOpenSettings: () -> Unit, onGoHome: () -> Unit, onReviewAi: () -> Unit) {
    val ui by vm.counselState.collectAsState()
    val app by vm.state.collectAsState()
    val p = rememberPalette()
    val counsel = vm.counsel
    val scenario = app.activeScenario

    Column(Modifier.fillMaxSize().background(p.canvas).imePadding()) {
        if (scenario == null || counsel == null) {
            Column(Modifier.fillMaxSize().padding(StratTokens.screenPad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Start from Home", color = p.ink, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Tell me what's on your mind and I'll set it up here.", color = p.muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onGoHome) { Text("Go to Home") }
            }
            return@Column
        }

        // ── Header ────────────────────────────────────────────────────────────
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(start = StratTokens.screenPad, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(scenario.title.ifBlank { "Your decision" }, color = p.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("counsel_menu")) { Icon(Icons.Default.MoreVert, "More", tint = p.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Undo last change") }, enabled = counsel.canUndo, onClick = { menu = false; counsel.undoLastChange() })
                    DropdownMenuItem(text = { Text("Clear this conversation") }, onClick = { menu = false; counsel.clearConversation() })
                }
            }
        }
        if (expert) ui.strip?.let { s ->
            Row(Modifier.fillMaxWidth().padding(horizontal = StratTokens.screenPad, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${s.players} players · ${s.influencers} influencers · ${s.actions} options · ${s.payoffsFilled}/${s.payoffsExpected} outcomes", color = p.muted, style = MaterialTheme.typography.labelSmall)
                if (s.aiSharePct > 0) Text("${s.aiSharePct}% AI", color = NeoTokens.amber, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable(onClick = onReviewAi))
            }
        }

        // ── Notices ───────────────────────────────────────────────────────────
        Column(Modifier.padding(horizontal = StratTokens.screenPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.supportVisible) NoticeCard(
                "Support is available", ui.supportText.ifBlank { "If you're in danger or thinking about harming yourself, contact your clinician or local emergency services now." },
                action = "OK", onAction = counsel::dismissSupport, p = p, accent = NeoTokens.red
            )
            when {
                ui.keyInvalid -> NoticeCard("Your ${AgentConfig.PROVIDER_NAME} key stopped working", "Update it in Settings to keep going. Your conversation is safe on this phone.", "Update key", onOpenSettings, p, NeoTokens.amber)
                !ui.aiReady && (!ui.hasKey || ui.needsConsent) -> NoticeCard("Set up AI counsel", "Add your ${AgentConfig.PROVIDER_NAME} key and agree to the privacy note. It takes a minute, and the analysis itself always runs on this phone.", "Open settings", onOpenSettings, p)
                !ui.aiReady -> NoticeCard("AI counsel is off", "Strategos works fully offline. Turn AI counsel on to talk this through.", "Turn on AI counsel", onOpenSettings, p)
            }
        }

        // ── Threads and journal note ──────────────────────────────────────────
        var newThread by remember { mutableStateOf(false) }
        ThreadSwitcherBar(ui.threads, ui.conversationId, enabled = !ui.running, onSelect = { counsel.selectThread(it) }, onNew = { newThread = true })
        if (newThread) NewThreadDialog(onDismiss = { newThread = false }, onCreate = { title, tag -> counsel.createNamedThread(title, tag); newThread = false })
        ui.postNotes.firstOrNull()?.let { note ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = StratTokens.screenPad, vertical = 4.dp).testTag("counsel_post_note"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("✨ Based on your new journal entry: ${note.insightHeadline.removePrefix("Based on your journal entry:").trim()}", color = p.ink, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 2)
                TextButton(onClick = { counsel.applyJournalPostNote(note.id) }) { Text("Apply") }
            }
        }

        // ── Conversation ──────────────────────────────────────────────────────
        val listState = rememberLazyListState()
        LaunchedEffect(ui.items.size, ui.running) { if (ui.items.isNotEmpty()) listState.animateScrollToItem(ui.items.size - 1) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("counsel_list"), state = listState,
            contentPadding = PaddingValues(horizontal = StratTokens.screenPad, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (ui.items.isEmpty() && ui.aiReady) item {
                Text("Tell me about the situation: who is involved and what you could do. I'll compare your options, and you can come back with what happens next.", color = p.muted, style = MaterialTheme.typography.bodyMedium)
            }
            val lastIndex = ui.items.lastIndex
            itemsIndexed(ui.items, key = { i, item -> "$i-${item.id}" }) { index, item ->
                when (item) {
                    is ChatItem.UserText -> MessageBubble(item.text, isUser = true, p = p)
                    is ChatItem.AssistantText -> MessageBubble(item.text, isUser = false, p = p)
                    is ChatItem.Activity -> if (expert) ActivityRow(item.steps, p)
                    is ChatItem.Recommendation -> if (expert) RecommendationCard(item.digest, onReviewAi, p)
                    is ChatItem.Questions -> QuestionCard(item.questions, item.answered, { q, option -> counsel.answerQuestion(q.id, q.text, option) }, p)
                    is ChatItem.Support -> NoticeCard("Support is available", ui.supportText, null, {}, p, NeoTokens.red)
                    is ChatItem.EventNote -> EventNote(item.text, p)
                    is ChatItem.Error -> ErrorCard(item.text, item.retryable && index == lastIndex, { counsel.retryLastTurn() }, p)
                    else -> Unit      // change-set, what-if, stress-test and recap cards belong to the agent-loop flow
                }
            }
        }

        // ── Composer ──────────────────────────────────────────────────────────
        if (ui.aiReady) {
            ui.activity?.let { Text(it, color = p.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = StratTokens.screenPad)) }
            var draft by remember(scenario.id) { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(start = StratTokens.screenPad, end = StratTokens.screenPad, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f).testTag("counsel_input"),
                    placeholder = { Text(if (ui.items.isEmpty()) "What's on your mind?" else "What happened next?") }, maxLines = 5
                )
                val voice = com.drkkr.strategos.ui.components.rememberVoiceInput(onText = { spoken -> draft = (draft + " " + spoken).trim() })
                IconButton(onClick = voice.start, modifier = Modifier.testTag("counsel_mic")) {
                    Icon(Icons.Default.Mic, if (voice.listening) "Listening" else "Speak", tint = if (voice.listening) NeoTokens.red else p.muted)
                }
                if (ui.running) {
                    Button(onClick = counsel::stop, modifier = Modifier.testTag("counsel_stop")) { Icon(Icons.Default.Stop, "Stop") }
                } else {
                    Button(onClick = { counsel.send(draft); draft = "" }, enabled = draft.isNotBlank(), modifier = Modifier.testTag("counsel_send")) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}
