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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.verticalScroll
import com.drkkr.strategos.domain.files.AttachedFile
import com.drkkr.strategos.ui.calm.theme.CalmFonts
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
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

/**
 * Talk (board 06): the project's one conversation. The top bar holds the title (tap it for the overview) and the
 * X-ray button; below it the conversation, then the composer. Stateless.
 */
@Composable
fun TalkScreen(ui: TalkUiState, actions: TalkActions, modifier: Modifier = Modifier) {
    val c = Calm.colors
    var explaining by remember { mutableStateOf<EngineTagUi?>(null) }
    var allTopics by remember { mutableStateOf(false) }
    var powerExplainer by remember { mutableStateOf(false) }

    // The conversation fills the whole screen; the header and the composer float over it and the text scrolls underneath.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var headerPx by remember { mutableStateOf(0) }
    var footerPx by remember { mutableStateOf(0) }
    Box(modifier.fillMaxSize().imePadding()) {
        Conversation(ui, actions, Modifier.fillMaxSize(), onExplain = { explaining = it },
            topPad = with(density) { headerPx.toDp() }, bottomPad = with(density) { footerPx.toDp() })
        // Opaque header (CP-P3): the conversation scrolls under it and must not show through the back arrow or the chips.
        Column(Modifier.align(Alignment.TopCenter).onSizeChanged { headerPx = it.height }.background(c.ground).statusBarsPadding()) {
            TopBar(ui, actions.onBack, actions.onOpenOverview, actions.onOpenXRay,
                power = if (ui.powerAvailable) ui.powerMode else null,
                onPower = {
                    when {
                        ui.powerMode -> actions.onSetPowerMode(false)
                        !ui.powerExplained -> powerExplainer = true      // first time in this conversation: say what it does first
                        else -> actions.onSetPowerMode(true)
                    }
                })
            if (ui.powerAvailable && ui.powerMode) PowerBanner()
            if (ui.offline) OfflineBanner()
            // One project, one conversation: new topics can no longer be started. The row only appears for older projects
            // that already have several, so nothing they wrote becomes unreachable.
            if (ui.topics.size > 1) TopicRow(ui, onSelect = actions.onSelectTopic, onAll = { allTopics = true })
        }
        Box(Modifier.align(Alignment.BottomCenter).onSizeChanged { footerPx = it.height }) { Composer(ui, actions) }
    }

    explaining?.let { tag -> PillExplainer(tag, onXRay = { explaining = null; actions.onOpenXRay() }, onClose = { explaining = null }) }
    if (powerExplainer) PowerExplainer(
        onTurnOn = { powerExplainer = false; actions.onPowerExplained(); actions.onSetPowerMode(true) },
        onClose = { powerExplainer = false; actions.onPowerExplained() }
    )
    if (allTopics) AllTopicsSheet(ui.topics, onPick = { actions.onSelectTopic(it); allTopics = false }, onClose = { allTopics = false })
}

/**
 * Back, then the title as the door to the overview (the way a contact's name opens their details in a messaging app),
 * with one quiet line under it saying how things are trending. The X-ray is the single amber button on the right.
 */
@Composable
private fun TopBar(ui: TalkUiState, onBack: () -> Unit, onOverview: () -> Unit, onXRay: () -> Unit, power: Boolean? = null, onPower: () -> Unit = {}) {
    val c = Calm.colors
    androidx.compose.foundation.layout.BoxWithConstraints {
        // With both chips showing on a phone, Power shrinks to its mark so the title keeps room to read.
        val tight = maxWidth < 480.dp && power != null && ui.canXRay
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.card.copy(alpha = 0.94f)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to projects", tint = c.ink)
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                // The trend is said in words only when the whole line fits; otherwise the dot carries it, never a cut-off word.
                val words = ui.statusLine.isNotBlank() && maxWidth >= 200.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale
                Column(
                    Modifier.fillMaxWidth().testTag("talk-title").clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "Open the project overview", role = androidx.compose.ui.semantics.Role.Button, onClick = onOverview)
                        .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(ui.projectName, style = Calm.type.subtitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ui.statusLine.isNotBlank()) {
                            Box(Modifier.size(7.dp).background(if (ui.statusTone > 0) c.trust else if (ui.statusTone < 0) c.tension else c.ink3, CircleShape))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (words) "${ui.statusLine} · Overview" else "Overview", style = Calm.type.pill, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).semantics { if (ui.statusLine.isNotBlank()) contentDescription = "${ui.statusLine}. Overview" })
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.ink3, modifier = Modifier.size(14.dp))
                    }
                }
            }
            // Power Mode: off by default, per conversation, and visible (I-P1). Hidden under the Power Mode lock (I-P4).
            if (power != null) { PowerChip(power, onPower, compact = tight); Spacer(Modifier.width(6.dp)) }
            if (ui.canXRay) XRayButton(onXRay)
        }
    }
}

/** The engine's door: amber, like everything that belongs to the engine. A 36dp pill inside a 48dp touch target. */
@Composable
private fun XRayButton(onClick: () -> Unit) {
    val c = Calm.colors
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.heightIn(min = 48.dp).clip(shape).clickable(onClickLabel = "Open the X-ray", role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            // Opaque: the conversation scrolls under the header.
            Modifier.height(36.dp).clip(shape).background(c.card).background(c.rigor.copy(alpha = 0.14f)).border(1.dp, c.rigor.copy(alpha = 0.4f), shape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(Modifier.size(8.dp).background(c.rigor, RoundedCornerShape(2.dp)))
            Text("X-Ray", color = c.rigor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun PowerChip(on: Boolean, onClick: () -> Unit, compact: Boolean = false) {
    val c = Calm.colors
    val shape = RoundedCornerShape(16.dp)
    Box(
        // Opaque: the conversation scrolls under the header, and a see-through chip would show it.
        Modifier.height(32.dp).clip(shape).background(c.card).background(if (on) c.rigor.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (on) c.rigor else c.line, shape).clickable(onClickLabel = "Power Mode", onClick = onClick).padding(horizontal = if (compact) 10.dp else 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(if (compact) "⚡" else "⚡ Power", color = if (on) c.rigor else c.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun PowerBanner() {
    val c = Calm.colors
    Text("Power Mode — hardball moves for work and negotiations.", color = c.rigor, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp))
            .background(c.card).background(c.rigor.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp))
}

/** Shown the first time Power Mode is turned on in a conversation: what it does, what it never does (blueprint §1), where it is off. */
@Composable
private fun PowerExplainer(onTurnOn: () -> Unit, onClose: () -> Unit) {
    val c = Calm.colors
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.paper).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("⚡ Power Mode", color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Plays harder in work and negotiations: hold your number, a firm posture, real competition, timing. Each reply gets a Power Read: the leverage, the plays being used on you, and the moves that pay, with where they come from.",
                color = c.ink, fontSize = 15.sp, lineHeight = 22.sp)
            Text("It never helps with false claims or fake offers, exploiting someone's weak spots, isolating someone, making them depend on you, or pinning blame on someone innocent.",
                color = c.ink2, fontSize = 15.sp, lineHeight = 22.sp)
            Text("It's off for partners, family and friends: there you see how the dynamics look, not hardball moves.", color = c.ink2, fontSize = 15.sp, lineHeight = 22.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chip("Not now", onClose)
                Box(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(c.action).clickable(onClick = onTurnOn).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center) { Text("Turn on", color = c.onAction, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
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
private fun TopicRow(ui: TalkUiState, onSelect: (String) -> Unit, onAll: () -> Unit) {
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
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit, dashed: Boolean = false) {
    val c = Calm.colors
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.height(32.dp).clip(shape).background(if (dashed) c.card.copy(alpha = 0.94f) else c.paper2)
            .border(1.dp, c.line, shape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = c.ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
private fun Conversation(ui: TalkUiState, actions: TalkActions, modifier: Modifier, onExplain: (EngineTagUi) -> Unit, topPad: androidx.compose.ui.unit.Dp = 0.dp, bottomPad: androidx.compose.ui.unit.Dp = 0.dp) {
    val c = Calm.colors
    val state = rememberLazyListState()
    // The check-in is asked once the coach is idle; after it is answered, one quiet line confirms what was logged.
    val askCheckIn = ui.checkIn != null && !ui.thinking && ui.problem == TalkProblem.NONE && ui.turns.isNotEmpty()
    var logged by remember { mutableStateOf<String?>(null) }
    val lastKey = ui.turns.size + (if (ui.thinking) 1 else 0) + (if (ui.problem != TalkProblem.NONE) 1 else 0) + (if (askCheckIn || logged != null) 1 else 0)
    LaunchedEffect(lastKey) { if (lastKey > 0) state.animateScrollToItem((lastKey - 1).coerceAtLeast(0)) }
    // The text dissolves into the status bar and header at the top and into the composer at the bottom, instead of ending on a hard edge.
    val fade = Modifier.graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val h = size.height.coerceAtLeast(1f)
        val top = (topPad.toPx() / h).coerceIn(0f, 0.45f)
        val bottomStart = (1f - bottomPad.toPx() * 0.9f / h).coerceIn(0.55f, 1f)
        val bottomEnd = (1f - bottomPad.toPx() * 0.4f / h).coerceIn(bottomStart, 1f)
        drawRect(
            Brush.verticalGradient(colorStops = arrayOf(0f to Color.Transparent, top to Color.Black, bottomStart to Color.Black, bottomEnd to Color.Transparent, 1f to Color.Transparent)),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
        )
    }
    LazyColumn(
        modifier.fillMaxWidth().then(fade), state = state,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = topPad + 8.dp, bottom = bottomPad + 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
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
                is TalkTurn.PowerRead -> PowerReadCard(turn.read, actions.onSetLeverage)
            }
        }
        if (ui.thinking) item("thinking") { Thinking(ui.activity) }
        if (ui.problem != TalkProblem.NONE && !ui.thinking) item("problem") { Problem(ui, actions) }
        if (askCheckIn) item("checkin") {
            CheckInCard(ui.checkIn.orEmpty()) { did, landed, said -> logged = said; actions.onSaveCheckIn(did, landed) }
        } else if (logged != null) item("logged") {
            Text(logged.orEmpty(), color = c.ink3, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun UserBubble(t: TalkTurn.User) {
    val c = Calm.colors
    var expanded by remember(t.id) { mutableStateOf(false) }
    var reading by remember(t.id) { mutableStateOf(false) }
    val long = t.text.length > 240
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CopyMenu(t.text) { longPress -> Column(
            Modifier.widthIn(max = 320.dp).alpha(if (t.queued) 0.82f else 1f)
                .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(c.action).then(longPress).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // The file that went with the message: a chip, not the prepared text. Tapping it shows exactly what the coach read.
            t.file?.let { f ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClickLabel = "See what was read from this file") { reading = true }
                        .heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp).testTag("file-chip"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FileBadge(f.badge, onDark = true)
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(f.facts, color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White.copy(alpha = 0.82f), modifier = Modifier.size(18.dp))
                }
            }
            if (t.text.isNotEmpty()) Text(t.text, color = Color.White, fontSize = 16.sp, lineHeight = 24.sp, maxLines = if (long && !expanded) 6 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            if (long && !expanded) Text("Read more", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { expanded = true })
        } }
        if (t.queued) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Schedule, null, tint = c.ink3, modifier = Modifier.size(14.dp))
            Text("Waiting for a connection", color = c.ink3, fontSize = 12.sp)
        }
    }
    if (reading) t.file?.let { FileSheet(it) { reading = false } }
}

/** The three-letter mark for a file: green for sheets, blue for documents, coral for slides, grey for plain text. */
@Composable
private fun FileBadge(badge: String, onDark: Boolean = false) {
    val c = Calm.colors
    val tint = when (badge) { "CSV", "XLS" -> c.trust; "DOC" -> c.action; "PPT" -> c.tension; else -> c.ink3 }
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (onDark) Color.White else tint), contentAlignment = Alignment.Center) {
        Text(badge, color = if (onDark) tint else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = CalmFonts.Mono, maxLines = 1)
    }
}

/** "What I read from this file": the brief exactly as the coach got it, so the user can check it before trusting the answer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSheet(f: AttachedFile, onClose: () -> Unit) {
    val c = Calm.colors
    ModalBottomSheet(
        onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.paper, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), scrimColor = Color(0xFF101E40).copy(alpha = 0.4f)
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("What I read from this file", style = Calm.type.title, color = c.ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FileBadge(f.badge)
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${f.label} · ${f.facts}", color = c.ink3, fontSize = 13.sp)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f, fill = false).clip(RoundedCornerShape(16.dp)).background(c.card).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(f.body, color = c.ink2, fontSize = 12.5.sp, lineHeight = 18.sp, fontFamily = CalmFonts.Mono, modifier = Modifier.testTag("file-brief"))
            }
            Text("Prepared on your phone. The coach reads this summary, not the file itself, and uses it for the rest of this conversation.", color = c.ink3, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

private val CHECK_DID = listOf("DID_AS_SUGGESTED" to "Did as suggested", "TRIED_VARIATION" to "Tried a variation", "SKIPPED_REACTIVE" to "Reacted instead")
private val CHECK_LANDED = listOf("DE_ESCALATED" to "Warmer", "NO_CHANGE" to "About the same", "ESCALATED_ATTACKED" to "Colder")

/**
 * The check-in, asked where the advice was given: what the user did with the last suggestion and how it landed.
 * Nothing is pre-selected and nothing is saved until both rows are answered and Save is tapped, so a stray tap
 * never writes a point onto the chart.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckInCard(question: String, onSave: (did: String, landed: String, said: String) -> Unit) {
    val c = Calm.colors
    var did by remember(question) { mutableStateOf<String?>(null) }
    var landed by remember(question) { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.coach).border(1.dp, c.selectedBorder, shape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("CHECK-IN", style = Calm.type.pill, color = c.ink3)
        Text(question, style = Calm.type.coachLead, color = c.coachInk)
        Text("WHAT YOU DID", style = Calm.type.pill, color = c.ink3)
        CheckChips(CHECK_DID, did) { did = it }
        Text("HOW IT LANDED", style = Calm.type.pill, color = c.ink3)
        CheckChips(CHECK_LANDED, landed) { landed = it }
        val ready = did != null && landed != null
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (!ready) Text("Pick one in each row.", color = c.ink3, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Box(
                Modifier.heightIn(min = 48.dp).alpha(if (ready) 1f else 0.5f).clip(RoundedCornerShape(24.dp)).background(c.action)
                    .clickable(enabled = ready, onClickLabel = "Save the check-in") {
                        val a = CHECK_DID.first { it.first == did }.second.lowercase(); val b = CHECK_LANDED.first { it.first == landed }.second.lowercase()
                        onSave(did!!, landed!!, "Logged: $a · $b. Your chart is updated.")
                    }.padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) { Text("Save", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckChips(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    val c = Calm.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            val shape = RoundedCornerShape(20.dp)
            Box(
                Modifier.heightIn(min = 40.dp).clip(shape).background(if (on) c.selected else Color.Transparent).border(1.dp, if (on) c.selectedBorder else c.line, shape)
                    .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { onSelect(key) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (on) c.selectedText else c.ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
        }
    }
}

private val SAY_LABEL = Regex("^\\*\\*What to say:\\*\\*\\s*")
private val PLAIN_LABEL = Regex("^\\*\\*(What's happening underneath|Clear-win move|Best move for now):\\*\\*\\s*")

/** Light inline emphasis: **word** renders bold; nothing else is interpreted. */
internal fun emphasis(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(p) } else append(p) }
}

@Composable
private fun CoachCard(t: CoachText, tag: EngineTagUi?, reveal: RevealMath, onXRay: () -> Unit, onExplain: (EngineTagUi) -> Unit) {
    val c = Calm.colors
    // The reply flows straight onto the page, like a chat: no filled card, no folding, the whole width.
    CopyMenu(t.plain()) { longPress -> Column(Modifier.fillMaxWidth().then(longPress), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (t.lead.isNotEmpty()) Text(emphasis(t.lead), style = Calm.type.coachLead, color = c.ink)
        if (t.body.isNotEmpty()) t.body.split("\n\n").filter { it.isNotBlank() }.forEach { para ->
            val say = SAY_LABEL.find(para)
            if (say != null) {
                // The words to say are set off as a quote, without the label.
                Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.action))
                    Text(emphasis(para.removeRange(say.range)), style = Calm.type.coachBody.copy(fontWeight = FontWeight.Medium), color = c.ink)
                }
            } else Text(emphasis(para.replace(PLAIN_LABEL, "")), style = Calm.type.coachBody, color = c.ink)
        }
        t.bullets.forEach { b ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = Calm.type.coachBody, color = c.ink)
                Text(emphasis(b), style = Calm.type.coachBody, color = c.ink)
            }
        }
        if (t.hasQuickRead) QuickRead(t)
        if (t.closing.isNotEmpty()) Text(emphasis(t.closing), style = Calm.type.coachBody, color = c.ink)
        if (tag != null && reveal != RevealMath.NEVER) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
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
    val file = ui.attachment
    // A file that is ready can go on its own; while one is still being read, sending waits so it is never left behind.
    val canSend = file?.state != AttachState.READING && (draft.isNotBlank() || file?.state == AttachState.READY)
    val shape = RoundedCornerShape(28.dp)
    Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp).navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().calmCard(c, shape).padding(4.dp)) {
            if (file != null) AttachmentRow(file, actions.onRemoveAttachment)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = actions.onAttach), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AttachFile, "Attach a file", tint = c.actionLink, modifier = Modifier.size(22.dp))
                }
                Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (draft.isEmpty()) Text(
                        when { file?.state == AttachState.READY -> "Add a question (optional)"; ui.turns.isEmpty() -> "What’s on your mind?"; else -> "What happened next?" },
                        color = c.ink3, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
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
}

/** The picked file, waiting above the text box: what it is and how big, or why it can't be read. */
@Composable
private fun AttachmentRow(file: AttachmentUi, onRemove: () -> Unit) {
    val c = Calm.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(24.dp, 24.dp, 12.dp, 12.dp)).background(c.yoursBg)
            .heightIn(min = 56.dp).padding(start = 10.dp).testTag("attachment"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FileBadge(file.badge)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(file.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(file.line, color = if (file.state == AttachState.FAILED) c.error else c.ink3, fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = if (file.state == AttachState.FAILED) 3 else 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, "Remove file", tint = c.ink3, modifier = Modifier.size(20.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllTopicsSheet(topics: List<TopicUi>, onPick: (String) -> Unit, onClose: () -> Unit) {
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
        }
    }
}
