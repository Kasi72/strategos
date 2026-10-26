package com.drkkr.strategos.ui.calm.projects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.domain.analysis.MomentumStatus
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.StrategosTheme
import com.drkkr.strategos.ui.calm.theme.groundBrush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** The three dock destinations (DESIGN.md product rules: Projects, Journal, Settings only). */
enum class DockItem { PROJECTS, JOURNAL, SETTINGS }

@Composable
fun ProjectsScreen(ui: ProjectsUiState, a: ProjectsActions) {
    val c = Calm.colors
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = { StrategosDock(DockItem.PROJECTS, a.onOpenJournal, a.onOpenSettings) }
    ) { pad ->
        Box(Modifier.fillMaxSize().background(c.groundBrush).padding(pad)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text("Strategos", style = Calm.type.title, color = c.ink,
                        modifier = Modifier.padding(start = Calm.dimens.gutter, top = 12.dp, bottom = 4.dp))
                    Text("What's on your mind?", style = Calm.type.display, color = c.ink,
                        modifier = Modifier.padding(horizontal = Calm.dimens.gutter).padding(bottom = 16.dp))
                    StartComposer(ui.draft, ui.canSend, a.onDraft, a.onSend, a.onSpeak)
                    if (!ui.hasKey) KeyNudgeCard(a.onAddKey)
                    Spacer(Modifier.height(20.dp))
                }
                if (ui.isFirstRun) {
                    item { SectionLabel("Or start from one of these") }
                    items(ProjectsUi.EXAMPLES) { ex -> ExampleStarter(ex) { a.onPickExample(ex) } }
                    item {
                        Text(
                            "Each situation becomes a project you can come back to. Nothing you write is shared until you send it.",
                            style = Calm.type.body, color = c.ink2,
                            modifier = Modifier.padding(horizontal = Calm.dimens.gutter, vertical = 20.dp)
                        )
                    }
                } else {
                    item { SectionLabel("Your projects") }
                    items(ui.projects, key = { it.id }) { p ->
                        ProjectCard(p, onClick = { a.onOpenProject(p.id) }, onRename = { a.onRename(p.id) }, onArchive = { a.onArchive(p.id) }, onDelete = { a.onDelete(p.id) })
                    }
                    if (ui.archivedCount > 0) item {
                        Text("Archived (${ui.archivedCount})", style = Calm.type.caption, color = c.ink3,
                            modifier = Modifier.fillMaxWidth().clickable { a.onOpenArchived() }
                                .padding(horizontal = Calm.dimens.gutter, vertical = 14.dp))
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = Calm.type.caption, color = Calm.colors.ink3,
        modifier = Modifier.padding(start = Calm.dimens.gutter, top = 12.dp, bottom = 12.dp))
}

@Composable
private fun StartComposer(draft: String, canSend: Boolean, onDraft: (String) -> Unit, onSend: () -> Unit, onSpeak: () -> Unit) {
    val c = Calm.colors
    Column(
        Modifier.padding(horizontal = Calm.dimens.gutter)
            .clip(RoundedCornerShape(24.dp)).background(c.card)
            .border(1.dp, c.cardBorder, RoundedCornerShape(24.dp))
            .padding(start = Calm.dimens.gutter, end = 4.dp, top = Calm.dimens.cardPadding, bottom = 4.dp)
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(end = 8.dp)) {
            if (draft.isEmpty()) Text("Describe it in your own words…", style = Calm.type.subtitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = c.ink3)
            BasicTextField(
                value = draft, onValueChange = onDraft,
                textStyle = Calm.type.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.action),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.heightIn(min = Calm.dimens.touchTarget).clip(RoundedCornerShape(24.dp)).clickable { onSpeak() }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.KeyboardVoice, contentDescription = "Speak", tint = c.action, modifier = Modifier.size(24.dp))
                Text("Speak", style = Calm.type.caption.copy(fontSize = Calm.type.subtitle.fontSize), color = c.action)
            }
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                    .background(if (canSend) c.action else c.line)
                    .clickable(enabled = canSend) { onSend() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = if (canSend) c.actionText else c.ink3, modifier = Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun KeyNudgeCard(onAddKey: () -> Unit) {
    val c = Calm.colors
    Row(
        Modifier.padding(horizontal = Calm.dimens.gutter, vertical = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(Calm.dimens.cardRadius)).background(c.card)
            .border(1.dp, c.cardBorder, RoundedCornerShape(Calm.dimens.cardRadius))
            .clickable { onAddKey() }.padding(Calm.dimens.cardPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Add an AI key to turn on the coach.", style = Calm.type.body, color = c.ink, modifier = Modifier.weight(1f))
        Text("Add key", style = Calm.type.subtitle, color = c.action)
    }
}

@Composable
private fun ExampleStarter(text: String, onClick: () -> Unit) {
    val c = Calm.colors
    Text(
        text, style = Calm.type.body, color = c.ink2,
        modifier = Modifier.padding(horizontal = Calm.dimens.gutter, vertical = 6.dp).fillMaxWidth()
            .clip(RoundedCornerShape(Calm.dimens.cardRadius)).background(c.card)
            .border(1.dp, c.cardBorder, RoundedCornerShape(Calm.dimens.cardRadius))
            .clickable { onClick() }.heightIn(min = Calm.dimens.rowMinHeight).padding(Calm.dimens.cardPadding)
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(p: ProjectCardUi, onClick: () -> Unit, onRename: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    val c = Calm.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.padding(horizontal = Calm.dimens.gutter, vertical = 6.dp).fillMaxWidth()
                .clip(RoundedCornerShape(Calm.dimens.cardRadius)).background(c.card)
                .border(1.dp, c.cardBorder, RoundedCornerShape(Calm.dimens.cardRadius))
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .heightIn(min = 80.dp).padding(Calm.dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = Calm.type.subtitle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p.subtitle, style = Calm.type.caption, color = c.ink3)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MomentumSpark(p.trustSpark, p.tensionSpark, p.status)
                StatusWord(p.status)
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            androidx.compose.material3.DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; onArchive() })
            androidx.compose.material3.DropdownMenuItem(text = { Text("Delete project", color = c.tension) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun StatusWord(status: MomentumStatus) {
    val c = Calm.colors
    val color = when (status) {
        MomentumStatus.IMPROVING -> c.trust
        MomentumStatus.TENSE -> c.tension
        MomentumStatus.STEADY -> c.ink3
    }
    Text(status.word, style = Calm.type.caption, color = color)
}

/** 64×24dp trust (solid) + tension (dashed) spark. Each series is scaled to its own range. */
@Composable
private fun MomentumSpark(trust: List<Float>, tension: List<Float>, status: MomentumStatus) {
    val c = Calm.colors
    Canvas(
        Modifier.width(64.dp).height(24.dp).semantics {
            contentDescription = "Momentum: ${status.word}"
        }
    ) {
        fun path(series: List<Float>): List<Offset> {
            if (series.size < 2) {
                val y = size.height / 2
                return listOf(Offset(0f, y), Offset(size.width, y))
            }
            val min = series.min(); val max = series.max()
            val span = (max - min).takeIf { it > 0f } ?: 1f
            val dx = size.width / (series.size - 1)
            return series.mapIndexed { i, v -> Offset(i * dx, size.height - ((v - min) / span) * size.height) }
        }
        fun draw(points: List<Offset>, color: androidx.compose.ui.graphics.Color, width: Float, dashed: Boolean) {
            for (i in 0 until points.size - 1) {
                if (dashed && i % 2 == 1) continue
                drawLine(color, points[i], points[i + 1], strokeWidth = width, cap = StrokeCap.Round)
            }
        }
        draw(path(tension), c.tension, 3f, dashed = true)
        draw(path(trust), c.trust, 5f, dashed = false)
    }
}

@Composable
fun StrategosDock(selected: DockItem, onJournal: () -> Unit, onSettings: () -> Unit) {
    val c = Calm.colors
    Row(
        Modifier.fillMaxWidth().background(c.card).height(80.dp).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockTab("Projects", Icons.Outlined.Folder, selected == DockItem.PROJECTS, Modifier.weight(1f)) {}
        DockTab("Journal", Icons.AutoMirrored.Outlined.MenuBook, selected == DockItem.JOURNAL, Modifier.weight(1f), onJournal)
        DockTab("Settings", Icons.Outlined.Tune, selected == DockItem.SETTINGS, Modifier.weight(1f), onSettings)
    }
}

@Composable
private fun DockTab(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit = {}) {
    val c = Calm.colors
    Column(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier.width(56.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
                .background(if (on) c.selected else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = label, tint = if (on) c.onAction else c.ink3, modifier = Modifier.size(24.dp)) }
        Text(label, style = Calm.type.pill, color = if (on) c.ink else c.ink3)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val sampleProjects = listOf(
    ProjectCardUi("1", "Loan for my brother", "Today · 3 topics", MomentumStatus.IMPROVING,
        listOf(50f, 54f, 58f, 55f, 62f, 66f), listOf(60f, 56f, 54f, 50f, 46f, 42f)),
    ProjectCardUi("2", "Co-founder deadlock", "2 days ago · 1 topic", MomentumStatus.STEADY,
        listOf(50f, 51f, 49f, 50f, 50f), listOf(50f, 49f, 51f, 50f, 50f)),
    ProjectCardUi("3", "Saying no to my manager about the weekend rota", "Last week · 2 topics", MomentumStatus.TENSE,
        listOf(55f, 52f, 48f, 44f, 40f), listOf(45f, 50f, 56f, 61f, 66f))
)

@Preview(name = "Projects • light", showBackground = true)
@Composable
private fun PreviewProjectsLight() {
    StrategosTheme(darkTheme = false) {
        ProjectsScreen(ProjectsUiState(projects = sampleProjects, archivedCount = 1, hasKey = true, loading = false), ProjectsActions())
    }
}

@Preview(name = "Projects • dark", showBackground = true)
@Composable
private fun PreviewProjectsDark() {
    StrategosTheme(darkTheme = true) {
        ProjectsScreen(ProjectsUiState(projects = sampleProjects, archivedCount = 1, hasKey = false, loading = false), ProjectsActions())
    }
}

@Preview(name = "Projects • first run", showBackground = true)
@Composable
private fun PreviewProjectsEmpty() {
    StrategosTheme(darkTheme = false) {
        ProjectsScreen(ProjectsUiState(projects = emptyList(), archivedCount = 0, hasKey = true, loading = false), ProjectsActions())
    }
}
