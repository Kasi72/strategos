package com.drkkr.strategos.ui.calm.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.calmCard
import com.drkkr.strategos.ui.components.rememberVoiceInput

/** The Journal tab inside a project (board 06b): a linked composer, "may relate" suggestions, and linked entries as context only. */
@Composable
fun ProjectJournalPage(
    projectName: String,
    linked: List<JournalEntryUi>,
    suggested: List<JournalEntryUi>,
    onSave: (String) -> Unit,
    onLink: (entryId: String) -> Unit,
    onDismiss: (entryId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = Calm.colors
    var draft by remember { mutableStateOf("") }
    val voice = rememberVoiceInput(onText = { draft = (draft + " " + it).trim() })
    val can = draft.isNotBlank()
    val shape = RoundedCornerShape(24.dp)
    Column(modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().calmCard(c, shape).padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
            Text("Linked to $projectName", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.yoursBg).padding(horizontal = 10.dp, vertical = 4.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp)) {
                if (draft.isEmpty()) Text("Write about this, just for you…", color = c.ink3, fontSize = 17.sp, lineHeight = 25.sp)
                BasicTextField(draft, { draft = it }, textStyle = Calm.type.body.copy(color = c.ink, fontSize = 17.sp, lineHeight = 25.sp),
                    cursorBrush = SolidColor(c.action), modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.heightIn(min = 48.dp).clickable(onClick = voice.start).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text("Speak", color = c.actionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (can) c.action else c.yoursBg)
                        .clickable(enabled = can) { onSave(draft.trim()); draft = "" }.padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Save privately", color = if (can) c.onAction else c.ink3, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }

        if (suggested.isNotEmpty()) {
            Label("May relate to this project", c.rigor, Modifier.padding(top = 20.dp))
            suggested.forEach { e ->
                EntryBlock(e) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, c.cardBorder, RoundedCornerShape(16.dp)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Link this entry to $projectName?", color = c.ink2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                        Box(Modifier.heightIn(min = 48.dp).clickable { onLink(e.id) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text("Link", color = c.actionText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.heightIn(min = 48.dp).clickable { onDismiss(e.id) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text("Not this", color = c.ink3, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        Label("Linked entries · context only", c.ink3, Modifier.padding(top = 8.dp))
        if (linked.isEmpty()) {
            Text("Nothing linked yet. Entries you link to this project will appear here.", color = c.ink2, fontSize = 15.sp, lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, c.cardBorder, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 20.dp))
        } else linked.forEach { e -> EntryBlock(e) { Text("Context only", color = c.ink3, fontSize = 13.sp, lineHeight = 18.sp) } }
    }
}

@Composable private fun Label(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Text(text.uppercase(), color = color, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
}

@Composable private fun EntryBlock(e: JournalEntryUi, footer: @Composable () -> Unit) {
    val c = Calm.colors
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth().calmCard(c, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${e.groupLabel} · ${e.timeLabel}", color = c.ink3, fontSize = 13.sp)
            e.mood?.let { Text(it.label, color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.paper2).border(1.dp, c.cardBorder, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) }
        }
        Text(e.text, color = c.ink, fontSize = 16.sp, lineHeight = 24.sp)
        footer()
    }
}
