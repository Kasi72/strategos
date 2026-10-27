package com.drkkr.strategos.ui.calm.settings

import com.drkkr.strategos.agent.AiSettingsUi
import com.drkkr.strategos.agent.KeyState
import com.drkkr.strategos.agent.ModelRow
import com.drkkr.strategos.data.repo.RevealMath

enum class KeyStatus { CONNECTED, MISSING, ERROR, CHECKING }

data class SettingsUiState(
    val available: Boolean = true,
    val aiOn: Boolean = false,
    val keyStatus: KeyStatus = KeyStatus.MISSING,
    val keyLast4: String? = null,
    val keyMessage: String? = null,
    val needsConsent: Boolean = false,
    val model: String = "",
    val models: List<ModelRow> = emptyList(),
    val modelMessage: String? = null,
    val reveal: RevealMath = RevealMath.ON_TAP
)

data class SettingsActions(
    val onToggleAi: (Boolean) -> Unit = {},
    val onSaveKey: (String) -> Unit = {},
    val onRemoveKey: () -> Unit = {},
    val onAcceptConsent: () -> Unit = {},
    val onDeclineConsent: () -> Unit = {},
    val onPickModel: (String) -> Unit = {},
    val onRefreshModels: () -> Unit = {},
    val onReveal: (RevealMath) -> Unit = {},
    val onOpenKeyHelp: () -> Unit = {},
    val onDeleteEverything: () -> Unit = {}
)

object SettingsUi {
    fun from(ai: AiSettingsUi, reveal: RevealMath, available: Boolean = true) = SettingsUiState(
        available = available,
        aiOn = ai.ready,
        keyStatus = when {
            ai.keyState == KeyState.CHECKING -> KeyStatus.CHECKING
            ai.keyInvalid || ai.keyState in setOf(KeyState.REJECTED, KeyState.ERROR, KeyState.OFFLINE) -> KeyStatus.ERROR
            ai.hasKey -> KeyStatus.CONNECTED
            else -> KeyStatus.MISSING
        },
        keyLast4 = ai.keyLast4, keyMessage = ai.message, needsConsent = ai.needsConsent,
        model = ai.model, models = ai.models, modelMessage = ai.modelMessage, reveal = reveal
    )

    fun revealNote(m: RevealMath) = when (m) {
        RevealMath.ALWAYS -> "The label stays under every reply, and the sheet opens when you tap it."
        RevealMath.ON_TAP -> "A small label sits under each reply. Tap it to see the numbers behind the advice. Nothing else changes."
        RevealMath.NEVER -> "No label under replies. The numbers are still reachable from the Overview's pattern card."
    }

    /** The destructive button unlocks only on the typed word. */
    fun canDelete(typed: String) = typed.trim().equals("DELETE", ignoreCase = true)
}
