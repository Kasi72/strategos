package com.drkkr.strategos.ui.calm.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.domain.agent.BrainFact
import com.drkkr.strategos.domain.agent.BrainFactKind
import com.drkkr.strategos.domain.agent.InfluenceGraph
import com.drkkr.strategos.domain.agent.Pattern
import com.drkkr.strategos.domain.agent.PersonMemory
import com.drkkr.strategos.domain.model.Provenance
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.calmCard

@Composable
fun OverviewScreen(ui: OverviewUiState, a: OverviewActions, modifier: Modifier = Modifier, journalPage: (@Composable () -> Unit)? = null) {
    var onJournal by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        TopBar(ui.projectName, ui.subtitle, a.onBack, a.onRename)
        if (journalPage != null) ProjectTabs(onJournal, ui.linkedJournalCount) { onJournal = it }
        Box(Modifier.fillMaxSize()) {
            if (onJournal && journalPage != null) androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().imePadding()) {
                item { journalPage() }
                item { Spacer(Modifier.height(96.dp)) }
            } else LazyColumn(Modifier.fillMaxSize()) {
                item { MomentumSection(ui) }
                if (ui.pendingCheck != null) item { QuickCheckCard(ui.pendingCheck.question, a.onSaveQuickCheck) }
                if (ui.guidance.isNotEmpty()) item { GuidanceSection(ui, a.onOpenReply) }
                ui.pattern?.let { item { PatternCard(it, a.onReadMore, a.onOpenXRay) } }
                if (ui.threads.isNotEmpty()) item { ConversationsSection(ui, a.onOpenThread, a.onNewTopic) }
                item { MemorySection(ui, a.onReview, a.onAddFact) }
                ui.influence?.let { if (it.nodes.isNotEmpty()) item { InfluenceSection(it) } }
                if (ui.journalTeasers.isNotEmpty()) item { JournalTeaserSection(ui, a.onApplyJournal, a.onDismissJournal, a.onOpenJournalTab) }
                item { Spacer(Modifier.height(96.dp)) }
            }
            if (!ui.loading) ExtendedFloatingActionButton(
                onClick = a.onOpenChat,
                containerColor = Calm.colors.action, contentColor = Calm.colors.actionText,
                icon = { Icon(Icons.Outlined.ChatBubbleOutline, ui.chatLabel) }, text = { if (!onJournal) Text(ui.chatLabel) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(Calm.dimens.gutter)
            )
        }
    }
}

@Composable
private fun ProjectTabs(onJournal: Boolean, linkedCount: Int, onPick: (Boolean) -> Unit) {
    val c = Calm.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        listOf(false to "Overview", true to "Journal").forEach { (isJ, label) ->
            val on = onJournal == isJ
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable { onPick(isJ) }.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, color = if (on) c.ink else c.ink3, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, fontSize = 15.sp)
                    if (isJ && linkedCount > 0) Text("$linkedCount", color = c.selectedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.selected).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Box(Modifier.padding(top = 4.dp).height(3.dp).fillMaxWidth().background(if (on) c.action else Color.Transparent))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

@Composable
private fun TopBar(name: String, subtitle: String, onBack: () -> Unit, onRename: () -> Unit) {
    val c = Calm.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to projects", tint = c.ink)
        }
        Column(Modifier.weight(1f).clickable(onClick = onRename)) {
            Text(name, style = Calm.type.subtitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = Calm.type.caption, color = c.ink3)
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null, trailingColor: Color = Calm.colors.ink3) {
    Row(Modifier.fillMaxWidth().padding(start = Calm.dimens.gutter, end = Calm.dimens.gutter, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Calm.type.title, color = Calm.colors.ink, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Calm.type.caption.copy(fontWeight = FontWeight.SemiBold), color = trailingColor)
    }
}

@Composable
private fun MomentumSection(ui: OverviewUiState) {
    val c = Calm.colors
    val statusColor = when (ui.status) {
        com.drkkr.strategos.domain.analysis.MomentumStatus.IMPROVING -> c.trust
        com.drkkr.strategos.domain.analysis.MomentumStatus.TENSE -> c.tension
        else -> c.ink3
    }
    SectionHeader("Is it getting better?", ui.status.word, statusColor)
    Column(Modifier.padding(horizontal = Calm.dimens.gutter).calmCard(c).padding(Calm.dimens.cardPadding)) {
        Text("MOMENTUM", style = Calm.type.caption, color = c.ink3)
        Spacer(Modifier.height(8.dp))
        if (ui.chart.hasData) MomentumChart(ui.chart, ui.events.size)
        else Text("Your chart starts after your first reply and a quick check.", style = Calm.type.body, color = c.ink2)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot("Trust", c.trust); LegendDot("Tension", c.tension)
        }
    }
    if (ui.momentumBlurb.isNotBlank())
        Text(ui.momentumBlurb, style = Calm.type.body, color = c.ink2, modifier = Modifier.padding(Calm.dimens.gutter, 12.dp, Calm.dimens.gutter, 0.dp))
    if (ui.events.isNotEmpty()) Column(Modifier.padding(Calm.dimens.gutter, 12.dp, Calm.dimens.gutter, 0.dp).calmCard(c)) {
        ui.events.forEachIndexed { i, e ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Calm.dimens.cardPadding, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(c.ink), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = Calm.type.pill, color = c.ground)
                }
                Spacer(Modifier.width(12.dp))
                Text(e.label, style = Calm.type.body, color = c.ink, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = 18.dp, height = 3.dp).background(color))
        Text(label, style = Calm.type.pill, color = Calm.colors.ink2)
    }
}

@Composable
private fun MomentumChart(chart: ChartUi, eventCount: Int) {
    val c = Calm.colors
    Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "Trust and tension over time" }) {
        val h = size.height; val w = size.width
        val (lo, hi) = com.drkkr.strategos.ui.calm.theme.TrendScale.window(
            chart.trustPast, chart.tensionPast, chart.trustHold, chart.tensionHold, chart.trustRevert, chart.tensionRevert
        )
        val inset = 6f
        fun y(v: Float) = h - inset - ((v - lo) / (hi - lo)) * (h - 2 * inset)
        fun pts(series: List<Float>, startX: Float, endX: Float): List<Offset> {
            if (series.isEmpty()) return emptyList()
            val dx = if (series.size == 1) 0f else (endX - startX) / (series.size - 1)
            return series.mapIndexed { i, v -> Offset(startX + i * dx, y(v)) }
        }
        val splitX = w * 0.6f
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        fun line(points: List<Offset>, color: Color, width: Float, dashed: Boolean, alpha: Float = 1f) {
            for (i in 0 until points.size - 1)
                drawLine(color.copy(alpha = alpha), points[i], points[i + 1], strokeWidth = width, cap = StrokeCap.Round, pathEffect = if (dashed) dash else null)
        }
        val tPast = pts(chart.trustPast, 0f, splitX)
        val nPast = pts(chart.tensionPast, 0f, splitX)
        val last = tPast.lastOrNull() ?: Offset(splitX, y(50f))
        val nLast = nPast.lastOrNull() ?: Offset(splitX, y(50f))
        line(nPast, c.tension, 4f, false)
        line(tPast, c.trust, 6f, false)
        line(listOf(last) + pts(chart.trustHold, splitX, w).map { it }, c.trust, 6f, true)
        line(listOf(nLast) + pts(chart.tensionHold, splitX, w).map { it }, c.tension, 4f, true)
        line(listOf(last) + pts(chart.trustRevert, splitX, w), c.trust, 3f, true, alpha = 0.4f)
        line(listOf(nLast) + pts(chart.tensionRevert, splitX, w), c.tension, 3f, true, alpha = 0.4f)
    }
}

@Composable
private fun QuickCheckCard(question: String, onSave: (String, String) -> Unit) {
    val c = Calm.colors
    var did by remember { mutableStateOf("DID_AS_SUGGESTED") }
    var landed by remember { mutableStateOf("NO_CHANGE") }
    Column(Modifier.padding(Calm.dimens.gutter, 24.dp, Calm.dimens.gutter, 0.dp).calmCard(c).padding(Calm.dimens.cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(question, style = Calm.type.subtitle, color = c.ink)
        Text("Takes ten seconds and sharpens the chart above.", style = Calm.type.caption, color = c.ink3)
        Text("WHAT YOU DID", style = Calm.type.caption, color = c.ink3)
        ChipRow(listOf("DID_AS_SUGGESTED" to "Did as suggested", "TRIED_VARIATION" to "Tried a variation", "SKIPPED_REACTIVE" to "Reacted instead"), did) { did = it }
        Text("HOW IT LANDED", style = Calm.type.caption, color = c.ink3)
        ChipRow(listOf("DE_ESCALATED" to "Warmer", "NO_CHANGE" to "About the same", "ESCALATED_ATTACKED" to "Colder"), landed) { landed = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PrimaryButton("Save") { onSave(did, landed) }
        }
    }
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val c = Calm.colors
    // Wraps to a second line instead of squeezing a long label into a sliver on narrow phones.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Text(label, style = Calm.type.caption, color = if (on) c.selectedText else c.ink2,
                modifier = Modifier.heightIn(min = Calm.dimens.chipHeight).clip(RoundedCornerShape(16.dp))
                    .background(if (on) c.selected else Color.Transparent)
                    .clickable { onSelect(key) }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun GuidanceSection(ui: OverviewUiState, onOpenReply: (String?) -> Unit) {
    val c = Calm.colors
    SectionHeader("What to do")
    Column(Modifier.padding(horizontal = Calm.dimens.gutter).calmCard(c)) {
        val dos = ui.guidance.filter { it.isDo }; val donts = ui.guidance.filterNot { it.isDo }
        if (dos.isNotEmpty()) Text("DO", style = Calm.type.caption, color = c.trust, modifier = Modifier.padding(Calm.dimens.cardPadding, 16.dp, Calm.dimens.cardPadding, 4.dp))
        dos.forEach { GuidanceRow(it, true, onOpenReply) }
        if (donts.isNotEmpty()) Text("DON'T", style = Calm.type.caption, color = c.tension, modifier = Modifier.padding(Calm.dimens.cardPadding, 16.dp, Calm.dimens.cardPadding, 4.dp))
        donts.forEach { GuidanceRow(it, false, onOpenReply) }
    }
}

@Composable
private fun GuidanceRow(row: GuidanceRowUi, isDo: Boolean, onOpenReply: (String?) -> Unit) {
    val c = Calm.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpenReply(row.refId) }.padding(Calm.dimens.cardPadding, 8.dp), verticalAlignment = Alignment.Top) {
        Icon(if (isDo) Icons.Filled.Check else Icons.Filled.Close, null, tint = if (isDo) c.trust else c.tension, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.text, style = Calm.type.body, color = c.ink)
            Text(row.source, style = Calm.type.caption, color = c.ink3)
        }
    }
}

@Composable
private fun PatternCard(p: Pattern, onReadMore: (String) -> Unit, onOpenXRay: () -> Unit) {
    val c = Calm.colors
    SectionHeader("The pattern here")
    Column(Modifier.padding(horizontal = Calm.dimens.gutter).clip(RoundedCornerShape(Calm.dimens.cardRadius)).background(c.coach).padding(20.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(p.name, style = Calm.type.title.copy(fontFamily = com.drkkr.strategos.ui.calm.theme.CalmFonts.Serif), color = c.coachInk)
        Text(p.summary, style = Calm.type.coachBody, color = c.coachInk)
        if (p.variables.isNotEmpty()) {
            Text("WHAT MATTERS IN YOUR CASE", style = Calm.type.caption, color = c.trust)
            p.variables.forEach { v ->
                Text(buildString { append(v.label); append(". "); append(v.value) }, style = Calm.type.body, color = c.coachInk)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.heightIn(min = 48.dp).clickable { onReadMore(p.wikiUrl) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Read more", style = Calm.type.subtitle, color = c.action)
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = c.action, modifier = Modifier.size(16.dp))
            }
            Text("See how I got here", style = Calm.type.subtitle, color = c.rigor, modifier = Modifier.heightIn(min = 48.dp).clickable { onOpenXRay() })
        }
    }
}

@Composable
private fun ConversationsSection(ui: OverviewUiState, onOpenThread: (String) -> Unit, onNewTopic: () -> Unit) {
    val c = Calm.colors
    SectionHeader("Conversations", "one shared memory")
    Column(Modifier.padding(horizontal = Calm.dimens.gutter).calmCard(c)) {
        ui.threads.forEach { t ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onOpenThread(t.id) }.padding(Calm.dimens.cardPadding, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t.title, style = Calm.type.subtitle, color = c.ink)
                        if (t.isOpenNow) Text("open now", style = Calm.type.pill, color = c.selectedText,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.selected).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                    if (t.lastLine.isNotBlank()) Text(t.lastLine, style = Calm.type.caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(t.whenLabel, style = Calm.type.caption, color = c.ink3)
                Icon(Icons.Filled.ChevronRight, null, tint = c.ink3)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onNewTopic).padding(Calm.dimens.cardPadding, 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Add, null, tint = c.action, modifier = Modifier.size(20.dp))
            Text("New topic", style = Calm.type.subtitle, color = c.action)
        }
    }
}

@Composable
private fun MemorySection(ui: OverviewUiState, onReview: () -> Unit, onAddFact: () -> Unit) {
    val c = Calm.colors
    SectionHeader("What the app knows")
    Column(Modifier.padding(horizontal = Calm.dimens.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ui.inferredCount > 0) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Calm.dimens.cardRadius)).background(Color(0xFFF6E6BC)).clickable(onClick = onReview).heightIn(min = 56.dp).padding(Calm.dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${ui.inferredCount} facts inferred by AI · Review", style = Calm.type.subtitle, color = Color(0xFF5E3E0C), modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, null, tint = Color(0xFF5E3E0C))
        }
        ui.people.forEach { person -> PersonCard(person) }
        if (ui.limits.isNotEmpty()) Column(Modifier.calmCard(c)) {
            Text("YOUR LIMITS", style = Calm.type.caption, color = c.ink3, modifier = Modifier.padding(Calm.dimens.cardPadding, 16.dp, Calm.dimens.cardPadding, 4.dp))
            ui.limits.forEach { FactRow(null, it.text, it.provenance) }
        }
        Row(Modifier.heightIn(min = 48.dp).clickable(onClick = onAddFact), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Add, null, tint = c.action, modifier = Modifier.size(20.dp))
            Text("Add something the app should know", style = Calm.type.subtitle, color = c.action)
        }
        if (ui.modelStrip.isNotBlank()) Column(Modifier.calmCard(c).padding(Calm.dimens.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("THE MODEL BEHIND THE ADVICE", style = Calm.type.caption, color = c.rigor)
            Text(ui.modelStrip, style = Calm.type.caption.copy(fontFamily = com.drkkr.strategos.ui.calm.theme.CalmFonts.Mono), color = c.ink2)
            Text("${ui.reviewedCount} of ${ui.inferredTotal} reviewed", style = Calm.type.caption.copy(fontFamily = com.drkkr.strategos.ui.calm.theme.CalmFonts.Mono), color = c.ink2)
        }
    }
}

@Composable
private fun PersonCard(person: PersonMemory) {
    val c = Calm.colors
    Column(Modifier.calmCard(c)) {
        Row(Modifier.padding(Calm.dimens.cardPadding, 12.dp, Calm.dimens.cardPadding, 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(c.ground), contentAlignment = Alignment.Center) { Text(person.initial, style = Calm.type.subtitle, color = c.ink) }
            Text(person.name, style = Calm.type.subtitle, color = c.ink)
        }
        person.facts.forEach { f -> FactRow(f.kind.name.lowercase().replaceFirstChar { it.uppercase() }, f.text, f.provenance) }
    }
}

@Composable
private fun FactRow(label: String?, text: String, provenance: Provenance) {
    val c = Calm.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(Calm.dimens.cardPadding, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (label != null) Text(label, style = Calm.type.caption, color = c.ink3)
            Text(text, style = Calm.type.body, color = c.ink)
        }
        val yours = provenance == Provenance.USER
        Text(if (yours) "yours" else "inferred", style = Calm.type.pill,
            color = if (yours) c.ink2 else Color(0xFF7A4F0C),
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (yours) c.ground else Color(0xFFF6E6BC)).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun InfluenceSection(graph: InfluenceGraph) {
    val c = Calm.colors
    SectionHeader("Who pulls on whom")
    val names = graph.nodes.associate { it.id to (if (it.isSelf) "You" else it.name) }
    Column(Modifier.padding(horizontal = Calm.dimens.gutter).calmCard(c).padding(Calm.dimens.cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("How much each person can sway the others, as far as I can tell.", style = Calm.type.caption, color = c.ink3)
        val edges = graph.edges.filter { names[it.fromId] != null && names[it.toId] != null }
        if (edges.isEmpty()) Text("Nothing recorded yet. Tell me who has a say in this and I'll show it here.", style = Calm.type.body, color = c.ink2)
        edges.forEach { e ->
            val strength = when { e.weight < 0.3 -> "a little"; e.weight < 0.6 -> "some"; else -> "a lot" }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${names[e.fromId]} pulls on ${names[e.toId]?.let { if (it == "You") "you" else it }}: $strength", style = Calm.type.body, color = c.ink, modifier = Modifier.weight(1f))
                    if (e.inferred) Text("my guess", style = Calm.type.pill, color = Color(0xFF7A4F0C),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFF6E6BC)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.ground)) {
                    Box(Modifier.fillMaxWidth(e.weight.toFloat().coerceIn(0.08f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.action))
                }
            }
        }
    }
}

@Composable
private fun JournalTeaserSection(ui: OverviewUiState, onApply: (String) -> Unit, onDismiss: (String) -> Unit, onOpenJournalTab: () -> Unit) {
    val c = Calm.colors
    SectionHeader("From your journal")
    Column(Modifier.padding(horizontal = Calm.dimens.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ui.journalTeasers.forEach { t ->
            Column(Modifier.calmCard(c).padding(Calm.dimens.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MAY RELATE TO THIS PROJECT", style = Calm.type.caption, color = c.rigor)
                Text(t.headline, style = Calm.type.subtitle, color = c.ink)
                Text("Journal · ${t.whenLabel}", style = Calm.type.caption, color = c.ink3)
                Text(t.takeaway, style = Calm.type.coachBody, color = c.ink2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Apply") { onApply(t.postNoteId) }
                    Text("Dismiss", style = Calm.type.subtitle, color = c.action, modifier = Modifier.heightIn(min = 48.dp).clickable { onDismiss(t.postNoteId) }.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    val c = Calm.colors
    Text(label, style = Calm.type.subtitle, color = c.actionText,
        modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(c.action).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 12.dp))
}
