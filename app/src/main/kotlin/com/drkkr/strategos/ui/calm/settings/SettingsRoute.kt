package com.drkkr.strategos.ui.calm.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.AiSettingsUi
import com.drkkr.strategos.ui.StrategosViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/** Set when the app sends someone to Settings because no key is saved yet; shows a short explanation there. */
object SettingsNudge { var on: Boolean by androidx.compose.runtime.mutableStateOf(false) }

/** Binds Settings to the AI settings controller, Reveal-the-math and the wipe. */
@Composable
fun SettingsRoute(vm: StrategosViewModel) {
    val c = vm.aiSettings
    val ai by (c?.state ?: remember { MutableStateFlow(AiSettingsUi()) }).collectAsState()
    val reveal by vm.revealMath.collectAsState()
    LaunchedEffect(Unit) { c?.load() }
    val uri = LocalUriHandler.current
    val ui = SettingsUi.from(ai, reveal, available = c != null)
    LaunchedEffect(ai.hasKey) { if (ai.hasKey) SettingsNudge.on = false }
    SettingsScreen(
        ui,
        SettingsActions(
            onToggleAi = { c?.setEnabled(it) },
            onSaveKey = { c?.saveAndConnect(it) },
            onRemoveKey = { c?.remove() },
            onAcceptConsent = { c?.acceptConsent() },
            onDeclineConsent = { c?.setEnabled(false) },
            onPickModel = { c?.selectModel(it) },
            onRefreshModels = { c?.refreshModels() },
            onReveal = vm::setRevealMath,
            onOpenKeyHelp = { runCatching { uri.openUri(AgentConfig.CONSOLE_URL) } },
            onDeleteEverything = vm::deleteEverything
        ),
        nudge = SettingsNudge.on
    )
}
