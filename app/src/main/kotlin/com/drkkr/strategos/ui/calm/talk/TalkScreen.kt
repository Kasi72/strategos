package com.drkkr.strategos.ui.calm.talk

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.drkkr.strategos.data.repo.RevealMath
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.calmCard
import com.drkkr.strategos.ui.components.rememberVoiceInput

/** Talk (board 06): back + project name, topic chips, the conversation, and the composer. Stateless. */
@Composable
fun TalkScreen(ui: TalkUiState, actions: TalkActions, modifier: Modifier = Modifier) {
    val c = Calm.colors
    var explaining by remember { mutableStateOf<EngineTagUi?>(null) }
    var newTopic by remember { mutableStateOf(false) }
    var allTopics by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().imePadding()) {
        TopBar(ui.projectName, actions.onBack)
        if (ui.offline) OfflineBanner()
        TopicRow(ui, onSelect = actions.onSelectTopic, onAll = { allTopics = true }, onNew = { newTopic = true })
        Conversation(ui, actions, Modifier.weight(1f), onExplain = { explaining = it })
        Composer(ui, actions)
    }

    explaining?.let { tag -> PillExplainer(tag, onXRay = { explaining = null; actions.onOpenXRay() }, onClose = { explaining = null }) }
    if (newTopic) NewTopicSheet(onStart = { actions.onNewTopic(it); newTopic = false }, onClose = { newTopic = false })
    if (allTopics) AllTopicsSheet(ui.topics, onPick = { actions.onSelectTopic(it); allTopics = false }, onNew = { allTopics = false; newTopic = true }, onClose = { allTopics = false })
}

@Composable
private fun TopBar(name: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to overview", tint = Calm.colors.ink)
        }
        Text(name, style = Calm.type.subtitle, color = Calm.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OfflineBanner() {
    val c = Calm.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(c.card).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.WifiOff, null, tint = c.ink2, modifier = Modifier.size(18.dp))
        Text("You’re offline. I’ll send this when you’re back.", color = c.ink2, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun TopicRow(ui: TalkUiState, onSelect: (String) -> Unit, onAll: () -> Unit, onNew: () -> Unit) {
    val c = Calm.colors
    Row(
        Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        ui.topics.take(3).forEach { t ->
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier.height(32.dp).clip(shape).background(if (t.isOpen) c.selected else c.paper2)
                    .border(1.dp, if (t.isOpen) c.selectedBorder else c.line, shape)
                    .clickable { onSelect(t.id) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(t.name, color = if (t.isOpen) c.selectedText else c.ink2, fontSize = 14.sp, fontWeight = if (t.isOpen) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
            }
        }
        if (ui.topics.size > 3) Chip("All (${ui.topics.size})", onAll)
        Chip("+ Topic", onNew, dashed = true)
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit, dashed: Boolean = false) {
    val c = Calm.colors
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.height(32.dp).clip(shape).background(if (dashed) Color.Transparent else c.paper2)
            .border(1.dp, c.line, shape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = c.ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
private fun Conversation(ui: TalkUiState, actions: TalkActions, modifier: Modifier, onExplain: (EngineTagUi) -> Unit) {
    val c = Calm.colors
    val state = rememberLazyListState()
    val lastKey = ui.turns.size + (if (ui.thinking) 1 else 0) + (if (ui.problem != TalkProblem.NONE) 1 else 0)
    LaunchedEffect(lastKey) { if (lastKey > 0) state.animateScrollToItem((lastKey - 1).coerceAtLeast(0)) }
    LazyColumn(
        modifier.fillMaxWidth(), state = state,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        if (ui.turns.isEmpty() && !ui.thinking && !ui.loading) item("empty") {
            Text("Tell me what’s going on with ${ui.projectName.lowercase()}. A few sentences is plenty.", color = c.ink3, fontSize = 14.sp, lineHeight = 21.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
        }
        itemsIndexed(ui.turns, key = { _, t -> t.id }) { _, turn ->
            when (turn) {
                is TalkTurn.User -> UserBubble(turn)
                is TalkTurn.Coach -> CoachCard(turn.text, turn.tag, ui.reveal, actions.onOpenXRay, onExplain)
                is TalkTurn.Ask -> AskCard(turn, onAnswer = actions.onSend)
                is TalkTurn.Note -> Text(turn.text, color = c.ink3, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                is TalkTurn.Support -> ErrorLike("Support is available", turn.text, null, null)
            }
        }
        if (ui.thinking) item("thinking") { Thinking(ui.activity) }
        if (ui.problem != TalkProblem.NONE && !ui.thinking) item("problem") { Problem(ui, actions) }
    }
}

@Composable
private fun UserBubble(t: TalkTurn.User) {
    val c = Calm.colors
    var expanded by remember(t.id) { mutableStateOf(false) }
    val long = t.text.length > 240
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CopyMenu(t.text) { longPress -> Column(
            Modifier.widthIn(max = 320.dp).alpha(if (t.queued) 0.82f else 1f)
                .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(c.action).then(longPress).padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(t.text, color = Color.White, fontSize = 16.sp, lineHeight = 24.sp, maxLines = if (long && !expanded) 6 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            if (long && !expanded) Text("Read more", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp).clickable { expanded = true })
        } }
        if (t.queued) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Schedule, null, tint = c.ink3, modifier = Modifier.size(14.dp))
            Text("Waiting for a connection", color = c.ink3, fontSize = 12.sp)
        }
    }
}

/** Light inline emphasis: **word** renders bold; nothing else is interpreted. */
internal fun emphasis(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(p) } else append(p) }
}

@Composable
private fun CoachCard(t: CoachText, tag: EngineTagUi?, reveal: RevealMath, onXRay: () -> Unit, onExplain: (EngineTagUi) -> Unit) {
    val c = Calm.colors
    var folded by remember(t.body) { mutableStateOf(t.body.length > 600) }
    CopyMenu(t.plain()) { longPress -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.coach).then(longPress).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (t.lead.isNotEmpty()) Text(emphasis(t.lead), style = Calm.type.coachLead, color = c.coachInk)
        if (folded) {
            Box(Modifier.heightIn(max = 92.dp)) {
                Text(emphasis(t.body), style = Calm.type.coachBody, color = c.coachInk, maxLines = 3, overflow = TextOverflow.Clip)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp).background(Brush.verticalGradient(listOf(Color.Transparent, c.coach))))
            }
            Row(Modifier.height(48.dp).clickable { folded = false }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Show the rest", color = c.onAction, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.KeyboardArrowDown, null, tint = c.onAction, modifier = Modifier.size(16.dp))
            }
        } else {
            if (t.body.isNotEmpty()) Text(emphasis(t.body), style = Calm.type.coachBody, color = c.coachInk)
            t.bullets.forEach { b ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = Calm.type.coachBody, color = c.coachInk)
                    Text(emphasis(b), style = Calm.type.coachBody, color = c.coachInk)
                }
            }
        }
        if (t.hasQuickRead) QuickRead(t)
        if (t.closing.isNotEmpty() && (!folded || t.hasQuickRead)) Text(emphasis(t.closing), style = Calm.type.coachBody, color = c.coachInk, modifier = Modifier.padding(top = 4.dp))
        if (tag != null && reveal != RevealMath.NEVER) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            EnginePill(tag, onXRay, onExplain)
        }
    } }
}

/** Long-press a message to copy all of it. The content gets the gesture modifier; the menu hangs off the same spot. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CopyMenu(text: String, content: @Composable (Modifier) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box {
        content(Modifier.combinedClickable(onClick = {}, onLongClick = { open = true }))
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Copy") },
                onClick = { clipboard.setText(AnnotatedString(text)); open = false; android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show() }
            )
        }
    }
}

/** "Do this / Don't do this" as a short, bold quick read at the bottom of the reply, for people who skim. */
@Composable
private fun QuickRead(t: CoachText) {
    val c = Calm.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card.copy(alpha = 0.72f)).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (t.dos.isNotEmpty()) {
            Text("Do this", color = c.trust, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
            t.dos.forEach { QuickLine("✓", it, c.trust) }
        }
        if (t.donts.isNotEmpty()) {
            Text("Don't do this", color = c.tension, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, modifier = Modifier.padding(top = if (t.dos.isNotEmpty()) 6.dp else 0.dp))
            t.donts.forEach { QuickLine("✕", it, c.tension) }
        }
    }
}

@Composable
private fun QuickLine(mark: String, text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mark, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(emphasis(text), color = Calm.colors.coachInk, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EnginePill(tag: EngineTagUi, onXRay: () -> Unit, onExplain: (EngineTagUi) -> Unit) {
    val c = Calm.colors
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.height(24.dp).clip(shape).background(c.paper2).border(1.dp, c.rigor.copy(alpha = 0.3f), shape)
                .clickable(onClick = onXRay).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.size(8.dp).background(c.rigor, RoundedCornerShape(1.dp)))
            Text(listOf(tag.move, tag.archetype, tag.robustness).filter { it.isNotEmpty() }.joinToString(" · "), color = c.rigor, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Box(Modifier.size(32.dp).clip(CircleShape).clickable { onExplain(tag) }, contentAlignment = Alignment.Center) {
            Text("?", color = c.rigor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskCard(t: TalkTurn.Ask, onAnswer: (String) -> Unit) {
    val c = Calm.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.coach).padding(16.dp)) {
            Text(emphasis(t.question), style = Calm.type.coachBody, color = c.coachInk)
        }
        if (!t.answered) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            t.options.forEach { o ->
                val shape = RoundedCornerShape(24.dp)
                Box(Modifier.height(48.dp).clip(shape).background(c.card).border(1.5.dp, c.selectedBorder, shape).clickable { onAnswer("Re: ${t.question} → $o") }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center) { Text(o, color = c.onAction, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun Thinking(activity: String?) {
    val c = Calm.colors
    val pulse by rememberInfiniteTransition(label = "sk").animateFloat(0.45f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.coach).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0.62f, 1f, 0.94f, 0.7f).forEachIndexed { i, w ->
                Box(Modifier.fillMaxWidth(w).height(if (i == 0) 16.dp else 12.dp).alpha(pulse).clip(RoundedCornerShape(6.dp)).background(c.coachSkeleton))
            }
        }
        Text(activity ?: "Updating trajectory…", color = c.ink3, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun Problem(ui: TalkUiState, actions: TalkActions) {
    when (ui.problem) {
        TalkProblem.KEY_REJECTED -> ErrorLike("Couldn’t reach the coach", "Your message is saved. The AI key wasn’t accepted, so nothing was sent. You can check it in Settings.",
            if (ui.canRetry) actions.onRetry else null, actions.onOpenSettings)
        TalkProblem.FAILED -> ErrorLike("Couldn’t reach the coach", "Your message is saved. Something went wrong getting a reply. Try again in a moment.",
            if (ui.canRetry) actions.onRetry else null, null)
        TalkProblem.NO_KEY -> ErrorLike("Add your AI key", "The coach needs a key to reply. It takes a minute, and your project stays on this phone.", null, actions.onOpenSettings)
        TalkProblem.AI_OFF -> ErrorLike("The coach is off", "Turn it on in Settings to talk this through.", null, actions.onOpenSettings)
        TalkProblem.NONE -> Unit
    }
}

@Composable
private fun ErrorLike(title: String, body: String, onRetry: (() -> Unit)?, onSettings: (() -> Unit)?) {
    val c = Calm.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.errorBg).border(1.dp, c.tension.copy(alpha = 0.34f), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Warning, null, tint = c.error, modifier = Modifier.size(20.dp))
            Text(title, color = c.error, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(body, color = if (c.isDark) c.ink2 else Color(0xFF33445E), fontSize = 15.sp, lineHeight = 22.sp)
        if (onRetry != null || onSettings != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onRetry != null) Box(Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.action).clickable(onClick = onRetry).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Text("Try again", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (onSettings != null) Box(Modifier.height(48.dp).clickable(onClick = onSettings).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text("Open Settings", color = c.actionLink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private val com.drkkr.strategos.ui.calm.theme.CalmColors.actionLink: Color get() = if (isDark) onAction else action

@Composable
private fun Composer(ui: TalkUiState, actions: TalkActions) {
    val c = Calm.colors
    var draft by remember { mutableStateOf("") }
    val voice = rememberVoiceInput(onText = { spoken -> draft = (draft + " " + spoken).trim() })
    val canSend = draft.isNotBlank()
    val shape = RoundedCornerShape(28.dp)
    Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp).navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).calmCard(c, shape).padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                if (draft.isEmpty()) Text(if (ui.turns.isEmpty()) "What’s on your mind?" else "What happened next?", color = c.ink3, fontSize = 16.sp)
                BasicTextField(
                    draft, { draft = it }, textStyle = Calm.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.action),
                    modifier = Modifier.fillMaxWidth(), maxLines = 5
                )
            }
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = voice.start), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Mic, if (voice.listening) "Listening" else "Speak", tint = if (voice.listening) c.tension else c.actionLink, modifier = Modifier.size(24.dp))
            }
            if (ui.thinking) Box(Modifier.size(48.dp).clip(CircleShape).border(2.dp, c.action, CircleShape).clickable(onClick = actions.onStop), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Stop, "Stop reply", tint = c.actionLink, modifier = Modifier.size(18.dp))
            } else Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (canSend) c.action else c.yoursBg).clickable(enabled = canSend) { actions.onSend(draft.trim()); draft = "" },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.ArrowUpward, "Send message", tint = if (canSend) Color.White else c.ink3, modifier = Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun PillExplainer(tag: EngineTagUi, onXRay: () -> Unit, onClose: () -> Unit) {
    val c = Calm.colors
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().calmCard(c, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What this label means", color = c.rigor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.ink)) { append(tag.move) }; append(" is the move I’m recommending.\n")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.ink)) { append(tag.archetype.ifEmpty { "This" }) }; append(" is the kind of situation I think this is.\n")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.ink)) { append(tag.robustness) }
                append(if (tag.robust) " means it still holds if they react badly." else " means it depends heavily on how they react.")
            }, color = c.ink2, fontSize = 14.sp, lineHeight = 21.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.height(48.dp).clickable(onClick = onXRay), verticalAlignment = Alignment.CenterVertically) {
                    Text("See how I got here", color = c.rigor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.rigor, modifier = Modifier.size(16.dp))
                }
                Box(Modifier.height(48.dp).clickable(onClick = onClose).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Got it", color = c.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewTopicSheet(onStart: (String) -> Unit, onClose: () -> Unit) {
    val c = Calm.colors
    var draft by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.paper, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), scrimColor = Color(0xFF101E40).copy(alpha = 0.4f)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding().navigationBarsPadding()) {
            Text("A new topic", style = Calm.type.title, color = c.ink)
            Text("Same project, a separate conversation. Everything the project learns carries across.", color = c.ink2, fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 8.dp))
            FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TalkUi.TOPIC_STARTERS.forEach { s ->
                    val on = draft == s
                    val shape = RoundedCornerShape(18.dp)
                    Box(Modifier.height(36.dp).clip(shape).background(if (on) c.selected else c.paper2).border(1.dp, if (on) c.selectedBorder else c.line, shape).clickable { draft = s }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center) { Text(s, color = if (on) c.selectedText else c.ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
            Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).border(1.5.dp, c.action, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("What do you want to talk through?", color = c.actionLink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.padding(top = 4.dp)) {
                    if (draft.isEmpty()) Text("Name the angle or write your first line…", color = c.ink3, fontSize = 16.sp)
                    BasicTextField(draft, { draft = it }, textStyle = Calm.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.action), modifier = Modifier.fillMaxWidth())
                }
            }
            Text("Nothing is created until you send your first message, so you can’t end up with an empty thread.", color = c.ink3, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(52.dp).alpha(if (draft.isBlank()) 0.5f else 1f).clip(RoundedCornerShape(26.dp)).background(c.action).clickable(enabled = draft.isNotBlank()) { onStart(draft.trim()) },
                    contentAlignment = Alignment.Center
                ) { Text("Start talking", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Box(Modifier.height(52.dp).clickable(onClick = onClose).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text("Cancel", color = c.ink2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllTopicsSheet(topics: List<TopicUi>, onPick: (String) -> Unit, onNew: () -> Unit, onClose: () -> Unit) {
    val c = Calm.colors
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.paper, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), scrimColor = Color(0xFF101E40).copy(alpha = 0.4f)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Topics", style = Calm.type.title, color = c.ink)
                Text("one shared memory", color = c.ink3, fontSize = 13.sp)
            }
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
                topics.forEachIndexed { i, t ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onPick(t.id) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(t.name, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (t.isOpen) Text("open now", color = c.selectedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.selected).padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                            if (t.lastLine.isNotEmpty()) Text(t.lastLine, color = c.ink3, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(t.whenLabel, color = c.ink3, fontSize = 13.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onNew), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Add, null, tint = c.actionLink, modifier = Modifier.size(22.dp))
                Text("New topic", color = c.actionLink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
