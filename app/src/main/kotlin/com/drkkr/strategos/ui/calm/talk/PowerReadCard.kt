package com.drkkr.strategos.ui.calm.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.domain.human.HumanVocab
import com.drkkr.strategos.domain.human.power.LawNotes
import com.drkkr.strategos.domain.human.power.PowerRead
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.calmCard

/**
 * The Power Read card (blueprint §6): Leverage / Their plays / Your moves, built by Kotlin. Law labels are tappable chips that open a
 * plain explanation with a "Read more" link. "Your fallback" and "They need you" are tappable: the user's word is saved and wins over
 * what the model read from then on (I-H8).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PowerReadCard(read: PowerRead, onSetLeverage: (key: String, value: String) -> Unit, modifier: Modifier = Modifier) {
    val c = Calm.colors
    var law by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var mine by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    Column(modifier.fillMaxWidth().calmCard(c).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("⚡ POWER READ", color = c.rigor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

        Section("Leverage") {
            val l = read.leverage
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Editable("Your fallback", mine["yourAlternative"] ?: l.yourFallback, mine.containsKey("yourAlternative")) { editing = "yourAlternative" }
                Editable("They need you", mine["theyDependOnYou"] ?: l.theyNeedYou, mine.containsKey("theyDependOnYou")) { editing = "theyDependOnYou" }
            }
            val rest = l.copy(yourFallback = null, theyNeedYou = null).summary()
            if (rest.isNotBlank()) Text(rest, color = c.ink2, fontSize = 14.sp, lineHeight = 20.sp)
            editing?.let { key ->
                val values = if (key == "yourAlternative") HumanVocab.ALTERNATIVE.toList() else HumanVocab.DEPENDENCE.toList()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    values.forEach { v -> SmallChip(v) { mine = mine + (key to v); editing = null; onSetLeverage(key, v) } }
                }
            }
        }

        if (read.theirPlays.isNotEmpty()) Section("Their plays") {
            read.theirPlays.forEach { p ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("• ${p.sign}", color = c.ink, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f, fill = false))
                        LawChip(p.lawLabel) { law = p.lawLabel }
                    }
                    Text(p.counter + if (p.seenOnce) "  ·  seen once" else "", color = c.ink2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }

        Section("Your moves") {
            when {
                read.closeDomainNote != null -> Text(read.closeDomainNote, color = c.ink2, fontSize = 14.sp, lineHeight = 20.sp)
                read.yourMoves.isEmpty() -> Text("Hardball doesn't pay here: ${read.noHardballReason ?: "nothing to gain"}.", color = c.ink2, fontSize = 14.sp, lineHeight = 20.sp)
                else -> read.yourMoves.forEachIndexed { i, m ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${i + 1}. ${m.name}", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            LawChip(m.lawLabel) { law = m.lawLabel }
                            Text(evLabel(m.ev), color = c.ink2, fontSize = 13.sp)
                            if (m.closeCall) Text("close call", color = c.rigor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Text("If they see through it: ${m.ifExposed}", color = c.ink2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
    law?.let { LawSheet(it) { law = null } }
}

/** "EV +0.75" (two decimals; the card is a guide, not a ledger). */
fun evLabel(ev: Double): String = "EV " + (if (ev >= 0) "+" else "") + "%.2f".format(java.util.Locale.US, ev)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Calm.colors.ink3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Editable(label: String, value: String?, yours: Boolean, onTap: () -> Unit) {
    val c = Calm.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.height(30.dp).clip(shape).background(if (yours) c.yoursBg else c.paper2).border(1.dp, c.line, shape)
            .clickable(onClick = onTap).padding(horizontal = 12.dp).semantics { contentDescription = "$label, tap to set" },
        verticalAlignment = Alignment.CenterVertically
    ) { Text("$label: ${value ?: "not set"}" + if (yours) " (yours)" else "", color = c.ink, fontSize = 14.sp) }
}

@Composable
private fun SmallChip(label: String, onClick: () -> Unit) {
    val c = Calm.colors
    val shape = RoundedCornerShape(14.dp)
    Text(label, color = c.ink2, fontSize = 14.sp, modifier = Modifier.clip(shape).background(c.paper2).border(1.dp, c.line, shape)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp))
}

@Composable
private fun LawChip(label: String, onClick: () -> Unit) {
    if (label.isBlank()) return
    val c = Calm.colors
    val shape = RoundedCornerShape(8.dp)
    Text(label, color = c.rigor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(shape).border(1.dp, c.rigor.copy(alpha = 0.5f), shape).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LawSheet(label: String, onClose: () -> Unit) {
    val c = Calm.colors
    val uri = LocalUriHandler.current
    val notes = LawNotes.forLabel(label)
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.paper, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), scrimColor = Color(0xFF101E40).copy(alpha = 0.4f)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, color = c.rigor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (notes.isEmpty()) Text("No note for this one yet.", color = c.ink2, fontSize = 15.sp)
            notes.forEach { n ->
                Text(n.label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(n.text, color = c.ink, fontSize = 15.sp, lineHeight = 22.sp)
            }
            notes.firstOrNull()?.let { n ->
                Text("Read more", color = c.action, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { runCatching { uri.openUri(n.url) } }.padding(vertical = 4.dp).width(120.dp))
            }
        }
    }
}
