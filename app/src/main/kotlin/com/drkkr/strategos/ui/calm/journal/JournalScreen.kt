package com.drkkr.strategos.ui.calm.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.CalmColors
import com.drkkr.strategos.ui.calm.theme.CalmFonts
import com.drkkr.strategos.ui.calm.theme.calmCard
import com.drkkr.strategos.ui.components.rememberVoiceInput
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val WRITTEN_DOT = Color(0xFF5FA88F)

internal val CalmColors.linkText: Color get() = if (isDark) onAction else action

/** Journal (board 08): privacy line, week strip or month calendar, composer, and the entries by day. Stateless. */
@Composable
fun JournalScreen(ui: JournalUiState, actions: JournalActions, modifier: Modifier = Modifier) {
    val c = Calm.colors
    var month by remember { mutableStateOf(false) }
    var viewMonth by remember { mutableStateOf(YearMonth.from(ui.today)) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf<Mood?>(null) }

    val shown = if (selected != null) ui.entries.filter { it.day == selected } else ui.entries
    val groups = shown.groupBy { it.groupLabel }
    val save = { actions.onSave(draft.trim(), mood); draft = ""; mood = null; expanded = false }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Journal", style = Calm.type.display.copy(fontSize = 28.sp, lineHeight = 34.sp), color = c.ink)
                if (!ui.isEmpty) Row(
                    Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(c.paper2).border(1.dp, c.cardBorder, RoundedCornerShape(18.dp))
                        .clickable { month = !month }.padding(start = 14.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(ui.today.month.getDisplayName(TextStyle.FULL, Locale.getDefault()), color = c.ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.ink2, modifier = Modifier.size(16.dp).rotate(if (month) -90f else 90f))
                }
            }
            Row(Modifier.padding(horizontal = 16.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Eco, null, tint = c.ink2, modifier = Modifier.size(18.dp))
                Text("Only yours. Nothing here is advice or evidence unless you link it and say so.", color = c.ink2, fontSize = 14.sp, lineHeight = 21.sp)
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (ui.isEmpty) {
                    item("empty") {
                        Composer(draft, { draft = it }, mood, { mood = if (mood == it) null else it }, large = true, showCancel = false, onCancel = {}, onSave = save)
                        EmptyHelp(onStarter = { draft = "$it: " })
                    }
                } else {
                    item("cal") {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            if (!month) WeekStrip(ui, selected, { selected = if (selected == it) null else it }, { selected = null })
                            else MonthGrid(ui, viewMonth, selected, { viewMonth = it }, { selected = if (selected == it) null else it }, { selected = null })
                        }
                    }
                    item("composer") {
                        if (expanded || draft.isNotEmpty()) Composer(draft, { draft = it }, mood, { mood = if (mood == it) null else it }, large = false, showCancel = true,
                            onCancel = { expanded = false; draft = ""; mood = null }, onSave = save)
                        else CompactComposer(onOpen = { expanded = true }, onSpoken = { draft = it; expanded = true })
                    }
                    if (groups.isEmpty()) item("none") {
                        val sel = selected
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (sel != null) "No entries on ${sel.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${sel.dayOfMonth}." else "No entries yet.",
                                color = c.ink3, fontSize = 15.sp
                            )
                            Box(Modifier.padding(top = 8.dp).height(44.dp).clickable { selected = null }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                Text("Show all entries", color = c.linkText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    groups.forEach { (label, list) ->
                        item("h-$label") {
                            Text(
                                (if (selected != null) "$label · ${JournalUi.entriesCountLabel(list.size)}" else label).uppercase(),
                                color = c.ink3, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
                            )
                        }
                        list.forEach { e -> item(e.id) { EntryCard(e, actions) } }
                    }
                }
            }
        }
        if (ui.savedToast) Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { PrivateToast() }
    }
}

@Composable
private fun Dot(linked: Boolean, written: Boolean) {
    val c = Calm.colors
    Box(Modifier.height(9.dp), contentAlignment = Alignment.Center) {
        if (linked) Box(Modifier.size(9.dp).border(2.dp, c.action, CircleShape))
        else if (written) Box(Modifier.size(7.dp).background(WRITTEN_DOT, CircleShape))
    }
}

@Composable
private fun Legend(selected: LocalDate?, showAll: String, onShowAll: () -> Unit) {
    val c = Calm.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).background(WRITTEN_DOT, CircleShape)); Text("Written", color = c.ink2, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(9.dp).border(2.dp, c.action, CircleShape)); Text("Linked to a project", color = c.ink2, fontSize = 12.sp)
            }
        }
        if (selected != null) Text(showAll, color = c.linkText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onShowAll).padding(vertical = 8.dp))
    }
}

@Composable
private fun DayCell(day: LocalDate, size: Int, ui: JournalUiState, selected: LocalDate?, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val c = Calm.colors
    val future = day.isAfter(ui.today)
    val isToday = day == ui.today
    val isSel = selected == day
    Column(modifier.clickable(enabled = !future) { onPick(day) }.padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier.size(size.dp).then(if (isToday) Modifier.border(2.dp, c.action, CircleShape) else Modifier)
                .background(if (isSel) c.selected else Color.Transparent, CircleShape).alpha(if (future) 0.55f else 1f),
            contentAlignment = Alignment.Center
        ) {
            Text("${day.dayOfMonth}", color = if (future) c.ink3 else if (isSel) c.selectedText else c.ink, fontSize = if (size > 33) 15.sp else 14.sp,
                fontWeight = if (isToday || isSel) FontWeight.Bold else FontWeight.Medium)
        }
        Dot(day in ui.linkedDays, day in ui.writtenDays)
    }
}

@Composable
private fun WeekStrip(ui: JournalUiState, selected: LocalDate?, onPick: (LocalDate) -> Unit, onShowAll: () -> Unit) {
    val c = Calm.colors
    Column(Modifier.fillMaxWidth().calmCard(c).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            JournalUi.week(ui.today).forEach { d ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()).uppercase(), color = c.ink3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                    DayCell(d, 36, ui, selected, onPick)
                }
            }
        }
        Legend(selected, "Show all", onShowAll)
    }
}

@Composable
private fun MonthGrid(ui: JournalUiState, month: YearMonth, selected: LocalDate?, onMonth: (YearMonth) -> Unit, onPick: (LocalDate) -> Unit, onShowAll: () -> Unit) {
    val c = Calm.colors
    val canNext = month.isBefore(YearMonth.from(ui.today))
    Column(Modifier.fillMaxWidth().calmCard(c).padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { onMonth(month.minusMonths(1)) }, contentAlignment = Alignment.Center) { Icon(Icons.Default.KeyboardArrowLeft, "Previous month", tint = c.ink) }
            Text("${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.size(44.dp).clip(CircleShape).alpha(if (canNext) 1f else 0.35f).clickable(enabled = canNext) { onMonth(month.plusMonths(1)) }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month", tint = c.ink)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, color = c.ink3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        JournalUi.monthCells(month).chunked(7).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val d = row.getOrNull(i)
                    if (d == null) Box(Modifier.weight(1f)) else DayCell(d, 32, ui, selected, onPick, Modifier.weight(1f))
                }
            }
        }
        Legend(selected, "Show all days", onShowAll)
    }
}

@Composable
private fun MoodRow(mood: Mood?, onPick: (Mood) -> Unit) {
    val c = Calm.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Mood.values().forEach { m ->
            val on = mood == m
            val shape = RoundedCornerShape(16.dp)
            Box(Modifier.height(32.dp).clip(shape).background(if (on) c.selected else c.paper2).border(1.dp, if (on) c.selectedBorder else c.cardBorder, shape).clickable { onPick(m) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center) { Text(m.label, color = if (on) c.selectedText else c.ink2, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium) }
        }
    }
}

@Composable
private fun Composer(
    draft: String, onDraft: (String) -> Unit, mood: Mood?, onMood: (Mood) -> Unit,
    large: Boolean, showCancel: Boolean, onCancel: () -> Unit, onSave: () -> Unit
) {
    val c = Calm.colors
    val shape = RoundedCornerShape(24.dp)
    val voice = rememberVoiceInput(onText = { spoken -> onDraft((draft + " " + spoken).trim()) })
    val can = draft.isNotBlank()
    val size = if (large) 18.sp else 17.sp
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().calmCard(c, shape).border(1.dp, c.selectedBorder, shape).padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            if (draft.isEmpty()) Text("What’s on your heart?", color = c.ink3, fontSize = size)
            BasicTextField(draft, onDraft, textStyle = Calm.type.body.copy(color = c.ink, fontSize = size, lineHeight = 26.sp),
                cursorBrush = SolidColor(c.action), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), minLines = 2)
        }
        MoodRow(mood, onMood)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = voice.start).padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Mic, null, tint = if (voice.listening) c.tension else c.linkText, modifier = Modifier.size(24.dp))
                Text(if (voice.listening) "Listening…" else "Speak", color = c.linkText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showCancel) Box(Modifier.height(48.dp).clickable(onClick = onCancel).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text("Cancel", color = c.ink3, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                Box(Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (can) c.action else c.yoursBg).clickable(enabled = can, onClick = onSave).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text("Save privately", color = if (can) Color.White else c.ink3, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CompactComposer(onOpen: () -> Unit, onSpoken: (String) -> Unit) {
    val c = Calm.colors
    val voice = rememberVoiceInput(onText = onSpoken)
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 56.dp).calmCard(c, RoundedCornerShape(28.dp)).padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("What’s on your heart?", color = c.ink3, fontSize = 16.sp, modifier = Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 14.dp))
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = voice.start), contentAlignment = Alignment.Center) { Icon(Icons.Default.Mic, "Dictate journal entry", tint = c.linkText, modifier = Modifier.size(24.dp)) }
        Box(Modifier.size(48.dp).clip(CircleShape).background(c.yoursBg).clickable(onClick = onOpen), contentAlignment = Alignment.Center) { Icon(Icons.Default.ArrowUpward, "Write entry", tint = c.ink3, modifier = Modifier.size(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyHelp(onStarter: (String) -> Unit) {
    val c = Calm.colors
    Column(Modifier.padding(horizontal = 24.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Nothing here yet. That’s fine. This page is only for you, and it never has to be tidy.", fontFamily = CalmFonts.Serif, fontSize = 19.sp, lineHeight = 28.sp, color = c.ink2)
        Text("IF YOU’RE NOT SURE WHERE TO START", color = c.ink3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalUi.STARTERS.forEach { s ->
                val shape = RoundedCornerShape(20.dp)
                Box(Modifier.height(40.dp).clip(shape).background(c.paper2).border(1.dp, c.cardBorder, shape).clickable { onStarter(s) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(s, color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun EntryCard(e: JournalEntryUi, actions: JournalActions) {
    val c = Calm.colors
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth().calmCard(c).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(e.timeLabel, color = c.ink3, fontSize = 13.sp)
            e.mood?.let { m ->
                val shape = RoundedCornerShape(12.dp)
                Box(Modifier.height(24.dp).clip(shape).background(c.paper2).border(1.dp, c.cardBorder, shape).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(m.label, color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Text(e.text, color = c.ink, fontSize = 16.sp, lineHeight = 24.sp)
        val linkedId = e.linkedProjectId
        val suggestedId = e.suggestedProjectId
        if (linkedId != null && e.linkedProjectName != null) {
            Row(
                Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(c.yoursBg).clickable { actions.onOpenProject(linkedId) }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Link, null, tint = c.ink2, modifier = Modifier.size(12.dp))
                Text(e.linkedProjectName, color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        } else if (suggestedId != null && e.suggestedProjectName != null) {
            val shape = RoundedCornerShape(16.dp)
            Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.ink3.copy(alpha = 0.5f), shape).padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(buildAnnotatedString {
                    append("Looks related to ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.ink)) { append(e.suggestedProjectName) }
                    append(". Link it?")
                }, color = c.ink2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                Box(Modifier.height(48.dp).clickable { actions.onLink(e.id, suggestedId) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Link", color = c.linkText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.height(48.dp).clickable { actions.onDismissSuggestion(e.id, suggestedId) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Not this", color = c.ink3, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** "Recorded privately" confirmation. */
@Composable
fun PrivateToast(message: String = "Recorded privately") {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B2A4A)).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.Eco, null, tint = Color(0xFFA9C6F2), modifier = Modifier.size(20.dp))
        Text(message, color = Color(0xFFF3F7FC), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
