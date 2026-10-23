package com.drkkr.strategos.ui.counsel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.agent.QuestionUi
import com.drkkr.strategos.domain.agent.PlainResult
import com.drkkr.strategos.ui.components.UiPalette
import com.drkkr.strategos.ui.theme.NeoTokens
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

private val HEADINGS = setOf("What I understood", "Here's what I see", "Options to consider", "What to watch for")

/** Bold section headings and tidy bullets in the app's plain answer. No other markdown. */
internal fun formatAnswer(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, line ->
        if (i > 0) append('\n')
        when {
            line in HEADINGS -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line) }
            line == PlainResult.FOOTER -> withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append(line) }
            else -> append(line)
        }
    }
}

@Composable
internal fun MessageBubble(text: String, isUser: Boolean, p: UiPalette, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (isUser) p.accent.copy(alpha = if (p.isDark) 0.28f else 0.14f) else p.raised)
                .then(if (isUser) Modifier else Modifier.border(1.dp, p.border, RoundedCornerShape(18.dp)))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(if (isUser) AnnotatedString(text) else formatAnswer(text), color = p.ink, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun ActivityRow(steps: List<String>, p: UiPalette) {
    Text(steps.joinToString("  ·  ") { "✓ $it" }, color = p.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
internal fun EventNote(text: String, p: UiPalette) {
    Text(text, color = p.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
}

@Composable
internal fun ErrorCard(text: String, retryable: Boolean, onRetry: () -> Unit, p: UiPalette) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(BorderStroke(1.dp, NeoTokens.red.copy(alpha = 0.7f)), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text, color = p.ink, style = MaterialTheme.typography.bodyMedium)
        if (retryable) TextButton(onClick = onRetry) { Text("Try again") }
    }
}

/** A banner with one action, used for "set up AI", "key stopped working" and the support card. */
@Composable
internal fun NoticeCard(title: String, body: String, action: String?, onAction: () -> Unit, p: UiPalette, accent: Color = p.accent, secondary: String? = null, onSecondary: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.10f)).border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = p.ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, color = p.ink, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (action != null) Button(onClick = onAction) { Text(action) }
            if (secondary != null) OutlinedButton(onClick = onSecondary) { Text(secondary) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionCard(questions: List<QuestionUi>, answered: Boolean, onAnswer: (QuestionUi, String) -> Unit, p: UiPalette) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.raised).border(1.dp, p.border, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        questions.forEach { q ->
            Text(q.text, color = p.ink, style = MaterialTheme.typography.bodyLarge)
            if (q.options.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    q.options.forEach { option ->
                        Box(
                            Modifier.clip(NeoTokens.pill).background(p.accent.copy(alpha = if (answered) 0.06f else 0.16f))
                                .clickable(enabled = !answered) { onAnswer(q, option) }.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) { Text(option, color = if (answered) p.muted else p.ink, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }
        if (!answered) Text("Or type your own answer below.", color = p.muted, style = MaterialTheme.typography.labelSmall)
    }
}

/** Expert detail card. Every value comes from the stored report digest, never from model text (I1). */
@Composable
internal fun RecommendationCard(digest: JsonObject, onReviewAi: () -> Unit, p: UiPalette) {
    fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
    val pathways = (digest["pathways"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val next = pathways.firstOrNull { str(it, "label") == "Recommended" }
    val fallback = pathways.firstOrNull { str(it, "label") == "Safe Fallback" }
    val conf = digest["confidence"] as? JsonObject
    val prov = digest["provenance"] as? JsonObject
    val ai = (prov?.get("payoffs_ai_inferred") as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
    val total = (prov?.get("payoffs_total") as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.raised).border(1.dp, p.border, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Engine result", color = p.muted, style = MaterialTheme.typography.labelSmall)
        Text(str(digest, "executive_status_label").orEmpty(), color = p.ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        next?.let { Text("Next move: ${str(it, "action")}", color = p.ink, style = MaterialTheme.typography.bodyMedium) }
        fallback?.let { Text("Fallback: ${str(it, "action")}", color = p.muted, style = MaterialTheme.typography.bodySmall) }
        conf?.let { Text("Confidence: ${str(it, "label").orEmpty()}", color = p.muted, style = MaterialTheme.typography.bodySmall) }
        if (ai > 0) {
            Text("$ai of $total outcome scores are AI-inferred", color = NeoTokens.amber, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable(onClick = onReviewAi))
        }
    }
}

internal fun Modifier.spaced() = this.padding(vertical = 4.dp)
