package com.drkkr.strategos.ui.calm.talk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.domain.model.NegotiationContext

/**
 * Bottom-sheet wizard that elicits BATNA / ZOPA / anchor before the first extraction turn.
 * Only required fields are BATNA and aspiration; the rest are optional.
 * Calling [onSave] with the result lets [TalkRoute] persist it via [saveScenarioInternal].
 * Calling [onDismiss] skips the wizard (user can open it again from the thread menu later).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NegotiationWizardSheet(
    onSave: (NegotiationContext) -> Unit,
    onDismiss: () -> Unit
) {
    var batna by remember { mutableStateOf("") }
    var reservation by remember { mutableStateOf("") }
    var aspiration by remember { mutableStateOf("") }
    var openingAnchor by remember { mutableStateOf("") }
    var zopaNote by remember { mutableStateOf("") }
    var counterpartBatna by remember { mutableStateOf("") }
    var counterpartReservation by remember { mutableStateOf("") }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Negotiation prep",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Tell me your limits and targets so the engine can work with them as hard constraints.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            WizardField(
                label = "Your BATNA *",
                hint = "What you'll do if this negotiation fails",
                value = batna, onValueChange = { batna = it }
            )
            WizardField(
                label = "Your target (aspiration) *",
                hint = "Your ideal outcome — what success looks like",
                value = aspiration, onValueChange = { aspiration = it }
            )
            WizardField(
                label = "Walk-away point (reservation)",
                hint = "The worst deal you'd still accept",
                value = reservation, onValueChange = { reservation = it }
            )
            WizardField(
                label = "Opening offer / anchor",
                hint = "Your planned first position",
                value = openingAnchor, onValueChange = { openingAnchor = it }
            )
            WizardField(
                label = "ZOPA note",
                hint = "Your sense of the overlap zone (optional)",
                value = zopaNote, onValueChange = { zopaNote = it }
            )
            WizardField(
                label = "Their estimated BATNA",
                hint = "What they'll do if talks fail (optional)",
                value = counterpartBatna, onValueChange = { counterpartBatna = it }
            )
            WizardField(
                label = "Their estimated walk-away",
                hint = "The worst deal they'd still accept (optional)",
                value = counterpartReservation, onValueChange = { counterpartReservation = it }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) { Text("Skip") }
                Button(
                    onClick = {
                        onSave(
                            NegotiationContext(
                                batna = batna.trim(),
                                reservationPoint = reservation.trim(),
                                aspirationPoint = aspiration.trim(),
                                openingAnchor = openingAnchor.trim(),
                                zopaNote = zopaNote.trim(),
                                counterpartBatna = counterpartBatna.trim(),
                                counterpartReservation = counterpartReservation.trim()
                            )
                        )
                    },
                    enabled = batna.isNotBlank() && aspiration.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun WizardField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        maxLines = 3
    )
}

private val NEGOTIATION_KEYWORDS = setOf(
    "negotiat", "salary", "offer", "contract", "deal", "agreement", "counter", "anchor",
    "batna", "zopa", "settlement", "terms", "price", "bid", "raise", "renew", "wage"
)

/** Returns true when the scenario description looks like a negotiation scenario. */
fun looksLikeNegotiation(description: String): Boolean {
    val lower = description.lowercase()
    return NEGOTIATION_KEYWORDS.any { lower.contains(it) }
}
