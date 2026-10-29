package com.drkkr.strategos.domain.analysis

import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.AssumptionEntry
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario

data class QuickCheckOutcome(
    val trust: Double,
    val tension: Double,
    val streak: Int,
    val archetype: String,
    val trustDelta: Double,
    val tensionDelta: Double,
    val scenario: Scenario?
)

/** Turns the three taps of a quick-check into trust / tension / streak / archetype changes. No LLM, no I/O. */
object QuickCheckUpdater {
    private const val CHICKEN = "CHICKEN_GAME"
    private const val STAG = "STAG_HUNT"
    private const val REPAIR = "RELATIONAL_REPAIR"
    private const val HIGH_TENSION = 70.0

    fun update(
        scenario: Scenario?, trust: Double, tension: Double, streak: Int, archetype: String,
        executionChoice: String, counterpartReaction: String, selfFeeling: String
    ): QuickCheckOutcome {
        val grounded = selfFeeling == "GROUNDED"
        // BLUEPRINT-QUESTION: §7.2 lists some rows only for certain feelings or choices; the nearest row is used for the rest.
        val (dTrust, dTension, keepsStreak) = when (executionChoice) {
            "SKIPPED_REACTIVE" ->
                if (counterpartReaction == "ESCALATED_ATTACKED" && selfFeeling == "ANXIOUS_REGRETFUL") Triple(-9.0, 14.0, false)
                else Triple(-4.0, 6.0, false)
            else -> when (counterpartReaction) {                                   // DID_AS_SUGGESTED | TRIED_VARIATION
                "DE_ESCALATED" ->
                    if (executionChoice == "DID_AS_SUGGESTED" && grounded) Triple(8.0, -12.0, true) else Triple(5.0, -7.0, true)
                "ESCALATED_ATTACKED" -> Triple(-3.0, 10.0, true)                 // held the boundary
                else -> Triple(2.0, -4.0, true)
            }
        }
        val newTrust = (trust + dTrust).coerceIn(0.0, 100.0)
        val newTension = (tension + dTension).coerceIn(0.0, 100.0)
        val newStreak = if (keepsStreak) streak.coerceAtLeast(0) + 1 else 0
        val newArchetype = when {
            executionChoice == "SKIPPED_REACTIVE" && counterpartReaction == "ESCALATED_ATTACKED" && selfFeeling == "ANXIOUS_REGRETFUL" ->
                if (newTension >= HIGH_TENSION) REPAIR else CHICKEN
            executionChoice != "SKIPPED_REACTIVE" && counterpartReaction == "ESCALATED_ATTACKED" ->
                if (newTension >= HIGH_TENSION) REPAIR else archetype
            executionChoice == "DID_AS_SUGGESTED" && counterpartReaction == "DE_ESCALATED" && grounded && archetype == CHICKEN -> STAG
            else -> archetype
        }
        return QuickCheckOutcome(
            newTrust, newTension, newStreak, newArchetype, newTrust - trust, newTension - tension,
            scenario?.let { withTrustBaseline(it, newTrust, executionChoice, counterpartReaction) }
        )
    }

    /** Writes trust/100 to the counterpart and records the observation. Only the first other player is the counterpart. */
    private fun withTrustBaseline(s: Scenario, trust: Double, execution: String, reaction: String): Scenario {
        val me = s.stakeholders.firstOrNull { it.gameRole == GameRole.PLAYER }
        val counterpart = s.stakeholders.firstOrNull { it.gameRole == GameRole.PLAYER && it.id != me?.id } ?: return s
        return s.copy(
            stakeholders = s.stakeholders.map { if (it.id == counterpart.id) it.copy(trustBaseline = trust / 100.0) else it },
            assumptionLog = s.assumptionLog + AssumptionEntry(
                label = "Quick-check: ${counterpart.name} trust baseline",
                status = FactStatus.USER_ESTIMATE,
                note = "$execution / $reaction -> trust ${trust.toInt()}/100"
            )
        )
    }
}
