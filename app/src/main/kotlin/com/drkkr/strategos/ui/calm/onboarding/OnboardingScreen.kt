package com.drkkr.strategos.ui.calm.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.link
import com.drkkr.strategos.ui.calm.theme.CalmFonts
import com.drkkr.strategos.ui.calm.theme.calmCard
import com.drkkr.strategos.ui.components.rememberVoiceInput

/**
 * First-run welcome (board 01) and "What's on your mind?" (board 02). The prototype's third step was a scripted
 * coach question; here the real coach asks its own, so sending hands the text to Talk instead.
 */
@Composable
fun OnboardingScreen(onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    var step by remember { mutableStateOf(1) }
    var draft by remember { mutableStateOf("") }
    if (step == 1) Welcome(onNext = { step = 2 }, modifier)
    else Describe(draft, { draft = it }, onBack = { step = 1 }, onSend = { onSend(draft.trim()) }, modifier)
}

@Composable
private fun Dots(active: Int, modifier: Modifier = Modifier) {
    val c = Calm.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..2).forEach { i -> Box(Modifier.height(8.dp).width(if (i == active) 24.dp else 8.dp).clip(CircleShape).background(if (i == active) c.action else c.line)) }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit, modifier: Modifier) {
    val c = Calm.colors
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 48.dp)) {
            Canvas(Modifier.size(width = 96.dp, height = 64.dp)) {
                drawCircle(c.coach, radius = 28.dp.toPx(), center = Offset(34.dp.toPx(), 32.dp.toPx()))
                drawCircle(c.yoursBg, radius = 28.dp.toPx(), center = Offset(62.dp.toPx(), 32.dp.toPx()), alpha = 0.9f)
                drawCircle(c.rigorGraphic, radius = 6.dp.toPx(), center = Offset(48.dp.toPx(), 32.dp.toPx()), alpha = 0.9f)
            }
            Text("Say it the way you’d tell a friend.", color = c.ink, fontFamily = CalmFonts.Serif, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 46.sp)
            Text(
                "Strategos helps you think through a hard situation with someone in your life. You describe it. It does the careful part, and tells you what to say, what to leave unsaid, and whether things are getting better.",
                color = c.ink2, fontSize = 18.sp, lineHeight = 27.sp
            )
            Text(
                "Your journal and everything the app remembers stay on this phone. Only what you send in a conversation goes to the coach.",
                color = c.ink2, fontSize = 15.sp, lineHeight = 22.sp
            )
        }
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.action).clickable(onClick = onNext), contentAlignment = Alignment.Center) {
                Text("Describe a situation  →", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Dots(1)
        }
    }
}

@Composable
private fun Describe(draft: String, onDraft: (String) -> Unit, onBack: () -> Unit, onSend: () -> Unit, modifier: Modifier) {
    val c = Calm.colors
    val shape = RoundedCornerShape(24.dp)
    androidx.activity.compose.BackHandler(onBack = onBack)
    val voice = rememberVoiceInput(onText = { onDraft((draft + " " + it).trim()) })
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Strategos", color = c.ink, fontFamily = CalmFonts.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onBack).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text("Back", color = c.ink3, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
        Text("What’s on your mind?", color = c.ink, style = Calm.type.display.copy(fontSize = 32.sp, lineHeight = 38.sp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp))
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().calmCard(c, shape).border(1.5.dp, c.selectedBorder, shape).padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 104.dp)) {
                if (draft.isEmpty()) Text("My brother wants me to co-sign a loan…", color = c.ink3, fontSize = 18.sp, lineHeight = 26.sp)
                BasicTextField(
                    draft, onDraft, textStyle = Calm.type.body.copy(color = c.ink, fontSize = 18.sp, lineHeight = 26.sp),
                    cursorBrush = SolidColor(c.action), modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(end = 8.dp)
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = voice.start).padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Mic, null, tint = c.link, modifier = Modifier.size(24.dp))
                    Text("Speak", color = c.link, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                val can = draft.isNotBlank()
                Box(Modifier.size(48.dp).clip(CircleShape).background(if (can) c.action else c.yoursBg).clickable(enabled = can, onClick = onSend), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowUpward, "Send situation", tint = if (can) Color.White else c.ink3, modifier = Modifier.size(24.dp))
                }
            }
        }
        Text("Say as much or as little as you like. I’ll ask if I need more, and I’ll never ask for more than two things.", color = c.ink3, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp))
        Spacer(Modifier.weight(1f).height(24.dp))
        Dots(2, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 32.dp))
    }
}
