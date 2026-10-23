package com.drkkr.strategos.ui.tabs

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.agent.ChatItem
import com.drkkr.strategos.domain.agent.PlainSummary
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.rememberPalette
import com.drkkr.strategos.ui.theme.StratTokens

/** Guided "Summary": a read-only plain page of the situation, with a share button that sends text only when tapped (§17.5). */
@Composable
fun SummaryScreen(vm: StrategosViewModel) {
    val p = rememberPalette()
    val app by vm.state.collectAsState()
    val ui by vm.counselState.collectAsState()
    val context = LocalContext.current
    val scenario = app.activeScenario
    Column(Modifier.fillMaxSize().background(p.canvas).verticalScroll(rememberScrollState()).padding(StratTokens.screenPad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Summary", color = p.ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (scenario == null) {
            Text("Nothing here yet. Tell me what's on your mind on the Home page.", color = p.muted)
            return@Column
        }
        val lastAnswer = ui.items.filterIsInstance<ChatItem.AssistantText>().lastOrNull()?.text
        val text = PlainSummary.build(scenario, lastAnswer)
        Text(text, color = p.ink, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
            context.startActivity(Intent.createChooser(send, "Share with my clinician"))
        }) { Text("Share with my clinician") }
        Spacer(Modifier.height(StratTokens.dockTotalBottomPad))
    }
}
