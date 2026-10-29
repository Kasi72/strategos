package com.drkkr.strategos.ui.calm.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.domain.agent.ReviewAction
import com.drkkr.strategos.domain.agent.ReviewItem
import com.drkkr.strategos.ui.calm.theme.Calm

/** Review AI inputs (board 12): every guessed value, with Keep (it's mine) or Remove. Nothing is filed silently. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewAiInputsSheet(items: List<ReviewItem>, onAction: (ReviewItem, ReviewAction) -> Unit, onAcceptAll: () -> Unit, onDismiss: () -> Unit) {
    val c = Calm.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.ground) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(Calm.dimens.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("What the AI guessed", style = Calm.type.title, color = c.ink)
            Text("Keep what's right, remove what isn't. Kept lines become yours.", style = Calm.type.body, color = c.ink2)
            if (items.isEmpty()) Text("Nothing left to review.", style = Calm.type.body, color = c.ink3)
            else {
                Text("Accept all as my estimates", style = Calm.type.subtitle, color = c.actionText,
                    modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(c.action).clickable(onClick = onAcceptAll).heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 12.dp))
                items.forEach { item ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Calm.dimens.cardRadius)).background(c.card).padding(Calm.dimens.cardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title, style = Calm.type.subtitle, color = c.ink)
                        if (item.note.isNotBlank()) Text(item.note, style = Calm.type.caption, color = c.ink3)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Keep", style = Calm.type.subtitle, color = c.action, modifier = Modifier.heightIn(min = 48.dp).clickable { onAction(item, ReviewAction.KEEP) }.padding(horizontal = 12.dp, vertical = 12.dp))
                            Text("Remove", style = Calm.type.subtitle, color = c.tension, modifier = Modifier.heightIn(min = 48.dp).clickable { onAction(item, ReviewAction.REMOVE) }.padding(horizontal = 12.dp, vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

/** "Add something the app should know". */
@Composable
fun AddFactDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add something the app should know") },
        text = { OutlinedTextField(text, { text = it }, placeholder = { Text("For example: he texts late at night") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Text("Add", color = Calm.colors.action, modifier = Modifier.clickable(enabled = text.isNotBlank()) { onAdd(text); onDismiss() }.padding(12.dp)) },
        dismissButton = { Text("Cancel", color = Calm.colors.ink2, modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp)) }
    )
}
