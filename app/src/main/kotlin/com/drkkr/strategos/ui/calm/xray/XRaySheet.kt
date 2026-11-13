package com.drkkr.strategos.ui.calm.xray

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.domain.agent.MoveKind
import com.drkkr.strategos.domain.agent.XRayGrid
import com.drkkr.strategos.domain.agent.XRayRow
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.CalmFonts
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val MONO = CalmFonts.Mono

/**
 * The X-ray's own material. The sheet is the machine room, so it is always the dark panel with an
 * isometric grid, whichever theme the calm surface is in. Amber is the engine's colour and is used for
 * nothing else; green means good for you / trust, coral means hurts / tension, crimson a move that backfires.
 */
internal object XR {
    val panel = Color(0xFF101014)
    val card = Color(0xFF1A1A1E)
    val dock = Color(0xFF141418)
    val hair = Color.White.copy(alpha = .07f)
    val line = Color(0xFF8A8A96).copy(alpha = .22f)
    val ink = Color(0xFFF7F7FA)
    val ink2 = Color(0xFFB4B4C0)
    val ink3 = Color(0xFF8A8A96)
    val iris = Color(0xFF8A86FF)
    val irisFill = Color(0xFF5B57E0)
    val irisSoft = Color(0xFFD6D4FF)
    val amber = Color(0xFFF0B64E)
    val trust = Color(0xFF67C495)
    val tension = Color(0xFFFF6B63)
    val crimson = Color(0xFFEF4444)
}

/** Table cell fill by score, on the dark panel: dark coral (20) → slate (50) → green (80). The number is always printed too. */
internal fun cellColor(score: Int): Color {
    val x = score.coerceIn(20, 80).toFloat()
    val lo = Color(0xFF7A302D); val mid = Color(0xFF3A3A48); val hi = Color(0xFF2A7A5A)
    return if (x <= 50f) androidx.compose.ui.graphics.lerp(lo, mid, (x - 20f) / 30f) else androidx.compose.ui.graphics.lerp(mid, hi, (x - 50f) / 30f)
}

/** A tall upright phone. Laid out like [SNUG] today: the header scrolls with the slide and only the slider bar is pinned. */
private val ROOMY = 560.dp
/** Below this (landscape, a split-screen sliver) nothing is pinned: header, slide and slider share one scroll. */
private val SNUG = 380.dp
/** The sheet stops short of the top, so the status bar and a strip of the conversation stay visible. */
private const val SHEET_FILL = .94f

/**
 * The X-ray sheet (board 07): a header with the move and its verdict, two slides (Arena: who is in
 * the game; Board: how each move scores), and the what-if slider pinned to the bottom so the board and the
 * slider are on screen together.
 *
 * Layout is built for upright phones of any size. Only the slider bar is pinned to the bottom, and it is kept slim
 * (one status line, the two replies side by side, the track), so the header, tabs and slide scroll as one page in
 * everything above it. Only a sliver of a window (landscape, split screen) drops the pinning, so nothing is ever
 * cut off.
 */
@Composable
fun XRayContent(ui: XRayUiState, onOdds: (Float) -> Unit, onKeep: () -> Unit, onReset: () -> Unit, onReadMore: (String) -> Unit, onClose: () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(XR.panel).drawBehind {
            // Isometric paper: two sets of lines at ±26.565° (the 2:1 slope the board is drawn on).
            val step = 27.dp.toPx(); val c = XR.iris.copy(alpha = .055f)
            var y = -size.width / 2f
            while (y < size.height) {
                drawLine(c, Offset(0f, y), Offset(size.width, y + size.width / 2f), 1f)
                drawLine(c, Offset(0f, y + size.width / 2f), Offset(size.width, y), 1f)
                y += step
            }
        }
    ) {
        val g = ui.grid
        if (ui.loading || g == null) {
            Column(Modifier.fillMaxWidth().padding(Calm.dimens.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ui.loading) "Working it out…" else "Not enough to rank yet", style = if (ui.loading) Calm.type.body else Calm.type.title,
                        color = if (ui.loading) XR.ink2 else XR.ink, modifier = Modifier.weight(1f))
                    CloseButton(onClose)
                }
                if (!ui.loading) Text(ui.notEnough?.question.orEmpty(), style = Calm.type.body, color = XR.ink2)
                Spacer(Modifier.height(24.dp))
            }
            return@BoxWithConstraints
        }
        val bounded = constraints.hasBoundedHeight
        val fit = when { !bounded -> Fit.FREE; maxHeight >= ROOMY -> Fit.ROOMY; maxHeight >= SNUG -> Fit.SNUG; else -> Fit.SLIVER }
        key(g.rows.size) { Deck(ui, g, fit, maxHeight * SHEET_FILL, onOdds, onKeep, onReset, onReadMore, onClose) }
    }
}

private enum class Fit { ROOMY, SNUG, SLIVER, FREE }

@Composable
private fun Deck(
    ui: XRayUiState, g: XRayGrid, fit: Fit, tallest: androidx.compose.ui.unit.Dp,
    onOdds: (Float) -> Unit, onKeep: () -> Unit, onReset: () -> Unit, onReadMore: (String) -> Unit, onClose: () -> Unit
) {
    val ctx = LocalContext.current
    // System "remove animations": everything appears at its final state and auto-play is hidden.
    val still = remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val arena = ui.arena
    var tab by rememberSaveable { mutableIntStateOf(if (arena != null) 0 else 1) }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var table by rememberSaveable { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    val showArena = arena != null && tab == 0

    // A flip under the finger gets one light tick, so it can be felt without looking away from the board.
    val haptic = LocalHapticFeedback.current
    var lastBest by remember { mutableIntStateOf(g.bestIndex) }
    LaunchedEffect(g.bestIndex) {
        if (g.bestIndex != lastBest) { lastBest = g.bestIndex; if (ui.changed && !playing) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }

    // Auto-play: Arena, then each move worst to best, then a sweep across the flip and back. Any touch stops it.
    val rows by rememberUpdatedState(g.rows)
    val flips by rememberUpdatedState(ui.flips)
    val odds by rememberUpdatedState(onOdds)
    val reset by rememberUpdatedState(onReset)
    val coachPct = (ui.coachOdds * 100).roundToInt()
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        selected = -1; table = false; reset()
        if (arena != null) { tab = 0; delay(3000) }
        tab = 1; delay(1300)
        for (i in rows.indices.reversed()) { selected = i; delay(1900) }
        selected = -1; delay(600)
        val to = (flips.filter { it > coachPct }.minOrNull()?.plus(14) ?: flips.filter { it < coachPct }.maxOrNull()?.minus(14) ?: if (coachPct <= 50) 92 else 8).coerceIn(2, 98)
        val step = if (to >= coachPct) 1 else -1
        var p = coachPct
        while (p != to) { p += step; odds(p / 100f); delay(34) }
        delay(1400)
        while (p != coachPct) { p -= step; odds(p / 100f); delay(24) }
        reset(); playing = false
    }
    val stop = Modifier.pointerInput(Unit) {
        awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); playing = false } }
    }

    val header: @Composable () -> Unit = { Header(ui, g, onClose, stop) }
    val tabs: @Composable () -> Unit = {
        if (arena != null) Tabs(tab, playing, canPlay = !still, onTab = { tab = it }, onPlay = { playing = !playing }, stop = stop)
    }
    val slide: @Composable () -> Unit = {
        AnimatedContent(
            showArena, label = "slide",
            transitionSpec = {
                if (still) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { if (targetState) -it / 24 else it / 24 }) togetherWith fadeOut(tween(110))
            },
            modifier = Modifier.padding(horizontal = Calm.dimens.gutter).then(stop)
        ) { onArena ->
            if (onArena && arena != null) ArenaSlide(arena, still, Modifier.padding(top = 4.dp, bottom = 16.dp))
            else BoardSlide(ui, g, selected, table, still, onSelect = { selected = if (selected == it) -1 else it }, onTable = { table = !table }, onReadMore = onReadMore)
        }
    }
    val dock: @Composable () -> Unit = { Dock(ui, g, onOdds, onKeep, onReset, stop) }

    when (fit) {
        // An upright phone: only the slim slider bar is pinned. The header and tabs scroll away with the slide, so
        // nearly the whole sheet is reading room; the bar itself names the best move while the header is out of view.
        Fit.ROOMY, Fit.SNUG -> Column(Modifier.fillMaxWidth().height(tallest)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { header(); tabs(); slide() }
            dock()
        }
        // A sliver of a window: one scroll, nothing pinned, nothing cut off.
        Fit.SLIVER -> Column(Modifier.fillMaxWidth().heightIn(max = tallest).verticalScroll(rememberScrollState())) { header(); tabs(); slide(); dock() }
        Fit.FREE -> Column(Modifier.fillMaxWidth()) { header(); tabs(); slide(); dock() }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Close X-ray", onClick = onClose), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Close, contentDescription = "Close X-ray", tint = XR.ink2, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Header(ui: XRayUiState, g: XRayGrid, onClose: () -> Unit, stop: Modifier) {
    // At the coach's odds the verdict is the engine's own; once the user drags, it is the grid's.
    val robust = if (!ui.changed) (ui.engineRobust ?: g.robust) else g.robust
    val badge by animateColorAsState(if (robust) XR.trust else XR.tension, tween(200), label = "badge")
    Column(Modifier.fillMaxWidth().then(stop).padding(start = Calm.dimens.gutter, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ui.changed) "WITH YOUR ODDS" else "THE COACH'S MOVE", style = Calm.type.pill.copy(fontFamily = MONO), color = XR.amber, modifier = Modifier.weight(1f))
            CloseButton(onClose)
        }
        Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimatedContent(g.best.label, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "move") { label ->
                // The whole move, never cut: a long one drops to the smaller heading size so it still fits a phone screen.
                Text(label, style = if (label.length > LONG_MOVE) Calm.type.subtitle else Calm.type.title, color = XR.ink, modifier = Modifier.fillMaxWidth().semantics { heading() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (robust) "ROBUST" else "FRAGILE", style = Calm.type.pill.copy(fontFamily = MONO, fontWeight = FontWeight.SemiBold), color = badge, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(badge.copy(alpha = .16f)).padding(horizontal = 8.dp, vertical = 2.dp))
                Text(if (robust) "Holds up even if your guesses are a bit off." else "Only holds if your guesses are right.", style = Calm.type.caption, color = XR.ink2, modifier = Modifier.weight(1f))
            }
            val pct = (ui.odds * 100).roundToInt()
            Text(
                when {
                    ui.flipped -> "Flipped · the coach said: ${ui.coachBestLabel}"
                    ui.changed -> "Same move as the coach · at $pct%"
                    else -> "At the coach's odds · $pct%"
                },
                style = Calm.type.pill.copy(fontFamily = MONO), color = if (ui.flipped) XR.amber else XR.ink3
            )
        }
    }
}

@Composable
private fun Tabs(tab: Int, playing: Boolean, canPlay: Boolean, onTab: (Int) -> Unit, onPlay: () -> Unit, stop: Modifier) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Calm.dimens.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).then(stop).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .05f)).padding(3.dp)) {
            listOf("Arena", "Board").forEachIndexed { i, t ->
                val on = tab == i
                val bg by animateColorAsState(if (on) XR.card else Color.Transparent, tween(180), label = "seg")
                Box(
                    Modifier.weight(1f).heightIn(min = 42.dp).clip(RoundedCornerShape(11.dp)).background(bg)
                        .clickable(role = Role.Tab) { onTab(i) }.semantics { selected = on },
                    contentAlignment = Alignment.Center
                ) { Text(t, style = Calm.type.caption.copy(fontWeight = FontWeight.SemiBold), color = if (on) XR.ink else XR.ink2, maxLines = 1) }
            }
        }
        // Play sits outside the stop-on-touch area, so tapping it while playing stops rather than restarts.
        if (canPlay) Box(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(XR.amber.copy(alpha = .14f)).border(1.dp, XR.amber.copy(alpha = .35f), RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClickLabel = if (playing) "Stop the walkthrough" else "Play a walkthrough of the slides", onClick = onPlay).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) { Text(if (playing) "■ Stop" else "▶ Play", style = Calm.type.caption.copy(fontWeight = FontWeight.SemiBold), color = XR.amber, maxLines = 1) }
    }
}

/** Slide 2: the board, then the same three moves as a list you can open to see what each one gets you. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardSlide(
    ui: XRayUiState, g: XRayGrid, selected: Int, table: Boolean, still: Boolean,
    onSelect: (Int) -> Unit, onTable: () -> Unit, onReadMore: (String) -> Unit
) {
    val pct = (g.odds * 100).roundToInt()
    val p = g.odds.toFloat()
    val coach = g.rows.indexOfFirst { it.label.equals(ui.coachBestLabel, ignoreCase = true) }
    val sel = selected.takeIf { it in g.rows.indices } ?: -1
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Legend("1", "${g.wellLabel} · $pct%", XR.trust)
            Legend("2", "${g.badLabel} · ${100 - pct}%", XR.tension)
            Legend("S", "your score", XR.amber)
        }
        if (table) PayoffGrid(g.rows, g.bestIndex, g.wellLabel, g.badLabel, pct)
        else IsoBoard(
            rows = g.rows, blend = g.rows.map { p * it.well + (1 - p) * it.bad }, best = g.bestIndex, coach = coach, selected = sel, still = still,
            description = "Board. " + g.rows.joinToString(". ") { "${it.label}: ${it.well} if ${g.wellLabel}, ${it.bad} if ${g.badLabel}, ${it.expected} overall" } + ".",
            onSelect = onSelect
        )
        if (!ui.changed && g.rows.any { it.expected > g.best.expected })
            Text("The coach picks the move that holds up, not only the highest score.", style = Calm.type.caption, color = XR.ink2)

        Text("YOUR MOVES · TAP ONE TO SEE WHAT YOU GET", style = Calm.type.pill.copy(fontFamily = MONO), color = XR.ink3)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            g.rows.forEachIndexed { i, r -> MoveRow(i, r, g, pct, isCoach = i == coach, isBest = i == g.bestIndex, open = sel == i, onClick = { onSelect(i) }) }
        }
        Text(if (table) "See it as blocks" else "See it as a table", style = Calm.type.caption.copy(fontWeight = FontWeight.SemiBold), color = XR.irisSoft,
            modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onTable).padding(vertical = 14.dp))

        ui.pattern?.let { pat ->
            Text("WHY THIS WORKS", style = Calm.type.pill.copy(fontFamily = MONO), color = XR.ink3)
            Text(pat.name.uppercase(), style = Calm.type.subtitle, color = XR.ink)
            Text(pat.summary, style = Calm.type.body, color = XR.ink2)
            Text("Read more: ${pat.name}", style = Calm.type.subtitle, color = XR.irisSoft, modifier = Modifier.heightIn(min = 48.dp).clickable { onReadMore(pat.wikiUrl) }.padding(vertical = 12.dp))
        }
        ui.humanLens?.let { line ->
            Text("HUMAN LENS", style = Calm.type.pill.copy(fontFamily = MONO), color = XR.ink3)
            Text(line, style = Calm.type.body, color = XR.ink, modifier = Modifier.clickable(enabled = ui.humanLensUrl != null) { ui.humanLensUrl?.let(onReadMore) })
            if (ui.humanLensUrl != null && ui.humanLensTopic != null)
                Text("Read more: ${ui.humanLensTopic}", style = Calm.type.subtitle, color = XR.irisSoft, modifier = Modifier.heightIn(min = 48.dp).clickable { onReadMore(ui.humanLensUrl) }.padding(vertical = 12.dp))
        }
        if (ui.modelStrip.isNotBlank()) Text(ui.modelStrip, style = Calm.type.pill.copy(fontFamily = MONO), color = XR.amber)
    }
}

@Composable
private fun Legend(key: String, text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = Calm.type.pill.copy(fontFamily = MONO, fontWeight = FontWeight.SemiBold), color = color)
        Text(text, style = Calm.type.pill.copy(fontFamily = MONO), color = XR.ink3)
    }
}

private fun kindWord(k: MoveKind) = when (k) { MoveKind.HOLDS_UP -> "Holds up"; MoveKind.GAMBLE -> "A gamble"; MoveKind.BACKFIRES -> "Backfires" }

/** One move: its letter, name, kind and score. Opens in place to show what it gets you each way. */
@Composable
private fun MoveRow(i: Int, r: XRayRow, g: XRayGrid, pct: Int, isCoach: Boolean, isBest: Boolean, open: Boolean, onClick: () -> Unit) {
    val kind = r.kind
    val tone = when (kind) { MoveKind.HOLDS_UP -> XR.trust; MoveKind.GAMBLE -> XR.ink2; MoveKind.BACKFIRES -> XR.crimson }
    val edge by animateColorAsState(if (!open) XR.hair else if (kind == MoveKind.BACKFIRES) XR.crimson else XR.amber, tween(200), label = "edge")
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(XR.card.copy(alpha = .86f)).border(if (open) 1.5.dp else 1.dp, edge, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (open) "Open" else "Closed" }
            .animateContentSize(tween(220))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(('A' + i).toString(), style = Calm.type.caption.copy(fontFamily = MONO), color = if (isBest) XR.amber else XR.ink3)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.label, style = Calm.type.caption.copy(fontWeight = FontWeight.SemiBold), color = XR.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(kindWord(kind), style = Calm.type.pill, color = tone, maxLines = 1)
                    if (isCoach) Text("Coach's move", style = Calm.type.pill.copy(fontWeight = FontWeight.SemiBold), color = XR.amber, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("${r.expected}", style = Calm.type.subtitle.copy(fontFamily = MONO, fontWeight = FontWeight.Medium), color = XR.ink)
        }
        if (open) {
            Outcome("If ${g.wellLabel}", r.well, XR.trust)
            Outcome("If ${g.badLabel}", r.bad, XR.tension)
            Text(
                when (kind) {
                    MoveKind.HOLDS_UP -> "Holds up either way: the worse case still scores ${minOf(r.well, r.bad)}. At $pct% odds you get ${r.expected}."
                    MoveKind.BACKFIRES -> "Hurts either way: both outcomes score under 50. At $pct% odds you get ${r.expected}."
                    MoveKind.GAMBLE -> "Scores ${maxOf(r.well, r.bad)} one way and ${minOf(r.well, r.bad)} the other, so it rides on the odds. At $pct% you get ${r.expected}."
                },
                style = Calm.type.caption, color = XR.ink2
            )
        }
    }
}

@Composable
private fun Outcome(label: String, score: Int, color: Color) {
    val fill by animateFloatAsState(score / 100f, tween(320), label = "bar")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Calm.type.pill, color = XR.ink2, modifier = Modifier.weight(1.4f))
        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = .07f))) {
            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(CircleShape).background(color))
        }
        Text("$score", style = Calm.type.caption.copy(fontFamily = MONO), color = XR.ink, modifier = Modifier.width(28.dp))
    }
}

/** The slider and its two actions. Reset and Keep slide in only once the odds have been moved. */
@Composable
private fun Dock(ui: XRayUiState, g: XRayGrid, onOdds: (Float) -> Unit, onKeep: () -> Unit, onReset: () -> Unit, stop: Modifier) {
    val pct = (ui.odds * 100).roundToInt()
    Column(
        Modifier.fillMaxWidth().then(stop).background(XR.dock.copy(alpha = .97f))
            .drawBehind { drawLine(XR.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(start = Calm.dimens.gutter, end = Calm.dimens.gutter, top = 8.dp, bottom = 4.dp)
    ) {
        OddsSlider(
            value = ui.odds, coach = ui.coachOdds, flips = ui.flips, wellLabel = g.wellLabel, badLabel = g.badLabel,
            spoken = "Chance ${ui.oddsLabel}. Best move: ${g.best.label}." + if (ui.flipped) " Changed from the coach's move." else "",
            onChange = onOdds,
            // Once the odds move, the line above the track says which move wins now: the header may be scrolled out of view.
            status = if (ui.flipped) "Flipped. Best now: ${g.best.label}" else if (ui.changed) "Still best: ${g.best.label}" else null,
            statusColor = if (ui.flipped) XR.amber else XR.ink2
        )
        AnimatedVisibility(ui.changed, enter = expandVertically(tween(200)) + fadeIn(tween(200)), exit = shrinkVertically(tween(160)) + fadeOut(tween(120))) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text("Reset", style = Calm.type.body.copy(fontWeight = FontWeight.SemiBold), color = XR.irisSoft, maxLines = 1,
                    modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onReset).padding(horizontal = 12.dp, vertical = 12.dp))
                Text("Keep as my estimate", style = Calm.type.body.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(24.dp)).background(XR.irisFill)
                        .clickable(role = Role.Button, onClickLabel = "Keep $pct percent as my estimate", onClick = onKeep).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun PayoffGrid(rows: List<XRayRow>, best: Int, wellLabel: String, badLabel: String, wellPct: Int) {
    Column(Modifier.semantics { contentDescription = "Scores for each of your moves if he takes it well or badly" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("YOUR MOVE", style = Calm.type.pill.copy(fontFamily = MONO), color = XR.ink3, modifier = Modifier.weight(1.5f))
            Text("WELL $wellPct%", style = Calm.type.pill.copy(fontFamily = MONO), color = XR.ink3, modifier = Modifier.weight(1f))
            Text("BADLY ${100 - wellPct}%", style = Calm.type.pill.copy(fontFamily = MONO), color = XR.ink3, modifier = Modifier.weight(1f))
            Text("SCORE", style = Calm.type.pill.copy(fontFamily = MONO), color = XR.ink3, modifier = Modifier.weight(0.8f))
        }
        rows.forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth().then(if (i == best) Modifier.border(2.dp, XR.amber, RoundedCornerShape(12.dp)) else Modifier).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Text(r.label, style = Calm.type.caption.copy(fontWeight = FontWeight.SemiBold), color = XR.ink, modifier = Modifier.weight(1.5f).padding(start = 4.dp))
                Cell(r.well, Modifier.weight(1f)); Cell(r.bad, Modifier.weight(1f))
                Text("${r.expected}", style = Calm.type.subtitle.copy(fontFamily = MONO), color = XR.ink, modifier = Modifier.weight(0.8f))
            }
        }
        Text("Well = ${wellLabel.lowercase()} · Badly = ${badLabel.lowercase()}", style = Calm.type.pill, color = XR.ink3)
    }
}

@Composable
private fun Cell(score: Int, modifier: Modifier) {
    val bg by animateColorAsState(cellColor(score), tween(200), label = "cell")
    Box(modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text("$score", style = Calm.type.subtitle.copy(fontFamily = MONO), color = XR.ink)
    }
}

/** Past this many characters the move under the header uses the smaller heading size. */
private const val LONG_MOVE = 60
