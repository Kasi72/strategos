package com.drkkr.strategos.domain.model

/**
 * The two badges the Overview's Memory shows on every fact: `yours` (the user said it) or
 * `inferred` (the app guessed it). The four-value [FactStatus] collapses to these two for display.
 */
enum class Provenance { USER, INFERRED }

/** CONFIRMED / USER_ESTIMATE are the user's; AI_INFERRED / UNKNOWN are the app's guess. */
fun FactStatus.provenance(): Provenance = when (this) {
    FactStatus.CONFIRMED, FactStatus.USER_ESTIMATE -> Provenance.USER
    FactStatus.AI_INFERRED, FactStatus.UNKNOWN -> Provenance.INFERRED
}
