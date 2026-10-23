package com.drkkr.strategos.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.components.rememberPalette
import com.drkkr.strategos.ui.counsel.HomeComposer
import com.drkkr.strategos.ui.theme.StratTokens
import java.text.DateFormat
import java.util.Date

/** Guided Home (§17.5): one big prompt and a list of past conversations. No templates, no health grid. */
@Composable
fun GuidedHome(vm: StrategosViewModel, onStarted: (Boolean) -> Unit, onOpenScenario: (Scenario) -> Unit, onOpenSettings: () -> Unit) {
    val p = rememberPalette()
    val app by vm.state.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize().background(p.canvas), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = StratTokens.screenPad, end = StratTokens.screenPad, top = 20.dp, bottom = StratTokens.dockTotalBottomPad)
    ) {
        item { Text("Strategos", color = p.accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
        item { HomeComposer(vm, expert = false, onStarted = onStarted, onOpenSettings = onOpenSettings) }
        val past = app.scenarios.sortedByDescending { it.updatedAt }
        if (past.isNotEmpty()) item { Text("Earlier", color = p.muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
        items(past, key = { it.id }) { s ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.raised).border(1.dp, p.border, RoundedCornerShape(14.dp))
                    .clickable { onOpenScenario(s) }.padding(14.dp)
            ) {
                Text(s.title.ifBlank { "Untitled" }, color = p.ink, style = MaterialTheme.typography.bodyLarge)
                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.updatedAt)), color = p.muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
