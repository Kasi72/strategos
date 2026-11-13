package com.drkkr.strategos.ui.calm.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.domain.a2ui.Surface
import com.drkkr.strategos.domain.a2ui.UiAction
import com.drkkr.strategos.domain.a2ui.UiNode
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.calmCard

/**
 * Draws a card from its A2UI description with the app's own calm components: the description says what is on the card,
 * this file alone says how it looks. Once the user has [answered], anything that would send a reply retires (a button
 * goes away, an option stays readable but stops being tappable); taps that only open a place in the app keep working.
 */
@Composable
fun SurfaceView(surface: Surface, answered: Boolean, onAction: (UiAction) -> Unit) {
    // A bare row of answers has nothing left to show once one was given; a framed card stays as a record.
    if (answered && surface.root !is UiNode.Card && surface.actions().let { a -> a.isNotEmpty() && a.all { it.isReply } }) return
    Box(Modifier.fillMaxWidth().testTag("card-${surface.id}")) { Node(surface.root, answered, onAction) }
}

private fun live(action: UiAction?, answered: Boolean): Boolean = action != null && !(answered && action.isReply)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Node(n: UiNode, answered: Boolean, onAction: (UiAction) -> Unit) {
    val c = Calm.colors
    when (n) {
        is UiNode.Card -> Box(Modifier.fillMaxWidth().calmCard(c).padding(16.dp)) { Node(n.child, answered, onAction) }
        is UiNode.Column -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { n.children.forEach { Node(it, answered, onAction) } }
        is UiNode.Row -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { n.children.forEach { Node(it, answered, onAction) } }
        is UiNode.Text -> when (n.variant) {
            "h1", "h2", "h3", "h4", "h5" -> Text(n.text, style = Calm.type.subtitle, color = c.ink)
            "caption" -> Text(n.text, style = Calm.type.caption, color = c.ink3)
            else -> Text(emphasis(n.text), style = Calm.type.body, color = c.ink2)
        }
        is UiNode.Divider -> Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        is UiNode.Button -> if (live(n.action, answered)) {
            val shape = RoundedCornerShape(24.dp)
            val base = Modifier.heightIn(min = 48.dp).clip(shape)
            val look = if (n.primary) base.background(c.action) else base.background(c.card).border(1.5.dp, c.selectedBorder, shape)
            Box(look.clickable(role = Role.Button) { n.action?.let(onAction) }.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(n.label, color = if (n.primary) c.actionText else c.onAction, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        is UiNode.Option -> {
            val tappable = live(n.action, answered)
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(c.paper).border(1.dp, c.line, shape)
                    .then(if (tappable) Modifier.clickable(role = Role.Button) { n.action?.let(onAction) } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (n.tag.isNotBlank()) Text(n.tag, style = Calm.type.pill, color = c.rigor)
                    Text(n.title, style = Calm.type.body, color = c.ink, fontWeight = FontWeight.Medium)
                    if (n.detail.isNotBlank()) Text(n.detail, style = Calm.type.caption, color = c.ink3)
                }
                if (tappable) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.ink3, modifier = Modifier.size(20.dp))
            }
        }
        is UiNode.Step -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The rail: a dot for this point and a line running down to the next one.
            Column(Modifier.width(10.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(c.rigorGraphic))
                Box(Modifier.padding(top = 4.dp).width(1.5.dp).weight(1f).background(c.line))
            }
            Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(n.label, style = Calm.type.caption, color = c.ink3)
                Text(n.title, style = Calm.type.body, color = c.ink, fontWeight = FontWeight.Medium)
                if (n.detail.isNotBlank()) Text(n.detail, style = Calm.type.caption, color = c.ink2)
            }
        }
    }
}
