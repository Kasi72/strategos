package com.drkkr.strategos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.ui.theme.NeoChip
import com.drkkr.strategos.ui.theme.NeoTokens

/** Where a value came from (blueprint §11.4): confirmed, the user's own estimate, an AI guess, or unknown. */
@Composable
fun ProvenanceChip(status: FactStatus, modifier: Modifier = Modifier) {
    val (label, color) = provenanceLook(status)
    NeoChip(label = label, color = color, modifier = modifier)
}

fun provenanceLook(status: FactStatus): Pair<String, Color> = when (status) {
    FactStatus.CONFIRMED -> "Confirmed" to NeoTokens.green
    FactStatus.USER_ESTIMATE -> "Your estimate" to NeoTokens.blueCore
    FactStatus.AI_INFERRED -> "AI" to NeoTokens.amber
    FactStatus.UNKNOWN -> "Unknown" to Color(0xFF8A8F9C)
}
