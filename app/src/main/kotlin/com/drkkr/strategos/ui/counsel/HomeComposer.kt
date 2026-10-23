package com.drkkr.strategos.ui.counsel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.rememberPalette

/**
 * "What decision are you facing?" box (blueprint §11.2). With AI ready it starts a conversation; with AI off it takes the
 * 1.x parser path in Expert mode, and in Guided mode (which has no model editor) it leads to the AI setup instead.
 */
@Composable
fun HomeComposer(vm: StrategosViewModel, expert: Boolean, onStarted: (toCounsel: Boolean) -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val p = rememberPalette()
    val ui by vm.counselState.collectAsState()
    var text by remember { mutableStateOf("") }
    val ready = ui.aiReady
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.raised).border(1.dp, p.border, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (expert) "What decision are you facing?" else "What's on your mind?", color = p.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().testTag("home_input"), minLines = 3, maxLines = 8,
            placeholder = { Text("Describe it in your own words…") }
        )
        val voice = com.drkkr.strategos.ui.components.rememberVoiceInput(onText = { spoken -> text = (text + " " + spoken).trim() })
        androidx.compose.material3.TextButton(onClick = voice.start, modifier = Modifier.testTag("home_mic")) {
            androidx.compose.material3.Icon(Icons.Default.Mic, "Speak")
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 6.dp))
            Text(if (voice.listening) "Listening…" else "Speak")
        }
        if (!ready) Text(if (expert) "AI off · works offline" else "AI counsel is off. Set it up to talk this through.", color = p.muted, style = MaterialTheme.typography.labelMedium)
        Button(
            onClick = {
                when {
                    ready || expert -> { vm.startCounselFromText(text) { toCounsel -> onStarted(toCounsel) }; text = "" }
                    else -> onOpenSettings()
                }
            },
            enabled = text.isNotBlank() || (!ready && !expert),
            modifier = Modifier.fillMaxWidth().testTag("home_start")
        ) { Text(if (ready) "Start counsel →" else if (expert) "Build model →" else "Set up AI counsel") }
    }
}
