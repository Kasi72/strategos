package com.drkkr.strategos.ui.counsel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.agent.ThreadUi

/** `[Main Thread ▾] [+ Thread]`: switch between the named threads of this project, or start another. */
@Composable
fun ThreadSwitcherBar(threads: List<ThreadUi>, activeId: String?, enabled: Boolean, onSelect: (String) -> Unit, onNew: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = threads.firstOrNull { it.id == activeId }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open = true }, enabled = enabled && threads.size > 1, modifier = Modifier.testTag("thread_switcher")) {
            Text(active?.title ?: "Main Thread", maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            threads.forEach { t -> DropdownMenuItem(text = { Text(t.title) }, onClick = { open = false; onSelect(t.id) }) }
        }
        TextButton(onClick = onNew, enabled = enabled, modifier = Modifier.testTag("new_thread_button")) {
            Icon(Icons.Default.Add, null); Text("Thread")
        }
    }
}

@Composable
fun NewThreadDialog(onDismiss: () -> Unit, onCreate: (title: String, topicTag: String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New thread") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name (e.g. Weekend Plan)") }, singleLine = true, modifier = Modifier.testTag("new_thread_title"))
                OutlinedTextField(tag, { tag = it }, label = { Text("Topic (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title, tag.trim().uppercase().ifEmpty { "GENERAL" }) }, enabled = title.isNotBlank(), modifier = Modifier.testTag("new_thread_create")) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
