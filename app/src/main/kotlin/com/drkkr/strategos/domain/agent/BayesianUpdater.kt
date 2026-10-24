package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Scenario
import kotlin.math.roundToInt

/**
 * Phase F.6: how likely the counterpart is to soften on repair, respect a boundary, or escalate under a reactive
 * move — learned turn by turn from what Call 1 observed in the user's own account ([ModelSketch.userLastMovePosture],
 * [ModelSketch.counterpartObservedReaction]). The classification comes from the model; every number here is computed
 * by Kotlin, deterministic and clamped, so one surprising turn never swings the pick on its own.
 */
object BayesianUpdater {
    const val DEFAULT_SOFTENS = 0.60
    const val DEFAULT_BOUNDARY = 0.55
    const val DEFAULT_ESCALATES = 0.75

    private const val SOFTENS_MIN = 0.15
    private const val SOFTENS_MAX = 0.90
    private const val BOUNDARY_MIN = 0.10
    private const val BOUNDARY_MAX = 0.90
    private const val ESCALATES_MIN = 0.10
    private const val ESCALATES_MAX = 0.95
    private const val UP = 0.08
    private const val DOWN_REPAIR = 0.10
    private const val DOWN_BOUNDARY = 0.08

    /** The three priors after one observed turn. An unrecognised posture/reaction, "none", or a null leaves them unchanged. */
    fun update(softens: Double, boundary: Double, escalates: Double, posture: String?, reaction: String?): Triple<Double, Double, Double> {
        if (posture.isNullOrBlank() || reaction.isNullOrBlank() || posture == "none" || reaction == "none") return Triple(softens, boundary, escalates)
        return when (posture) {
            "repair" -> when (reaction) {
                "softened" -> Triple((softens + UP).coerceAtMost(SOFTENS_MAX), boundary, escalates)
                "withdrew_or_escalated" -> Triple((softens - DOWN_REPAIR).coerceAtLeast(SOFTENS_MIN), boundary, escalates)
                else -> Triple(softens, boundary, escalates)
            }
            "boundary" -> when (reaction) {
                "softened" -> Triple(softens, (boundary + UP).coerceIn(BOUNDARY_MIN, BOUNDARY_MAX), escalates)
                "withdrew_or_escalated" -> Triple(softens, (boundary - DOWN_BOUNDARY).coerceIn(BOUNDARY_MIN, BOUNDARY_MAX), escalates)
                else -> Triple(softens, boundary, escalates)
            }
            "reactive" -> if (reaction == "withdrew_or_escalated") Triple(softens, boundary, (escalates + UP).coerceAtMost(ESCALATES_MAX))
                          else Triple(softens, boundary, escalates)
            else -> Triple(softens, boundary, escalates)
        }
    }

    /** Applies [update] to the saved counterpart (the PLAYER who is not "You") and returns the scenario with the new priors stored. No-op without an owner and a counterpart. */
    fun applyToScenario(s: Scenario, posture: String?, reaction: String?): Scenario {
        val owner = IntakeChecklist.self(s) ?: return s
        val other = s.players().firstOrNull { it.id != owner.id } ?: return s
        val before = Triple(other.probSoftensOnRepair ?: DEFAULT_SOFTENS, other.probRespectsBoundary ?: DEFAULT_BOUNDARY, other.probEscalatesOnReactive ?: DEFAULT_ESCALATES)
        val after = update(before.first, before.second, before.third, posture, reaction)
        if (after == before) return s
        return s.copy(stakeholders = s.stakeholders.map {
            if (it.id == other.id) it.copy(probSoftensOnRepair = after.first, probRespectsBoundary = after.second, probEscalatesOnReactive = after.third) else it
        })
    }

    /** One line naming the strongest learned pattern, for the directive and the coach. Null until something has moved off the defaults. */
    fun learningNote(s: Scenario): String? {
        val owner = IntakeChecklist.self(s) ?: return null
        val other = s.players().firstOrNull { it.id != owner.id } ?: return null
        val softens = other.probSoftensOnRepair; val boundary = other.probRespectsBoundary; val escalates = other.probEscalatesOnReactive
        if (softens == null && boundary == null && escalates == null) return null
        fun pct(d: Double) = (d * 100).roundToInt()
        return when {
            escalates != null && escalates >= 0.85 -> "Learned pattern: a reactive move reliably makes ${other.name} withdraw or escalate (${pct(escalates)}% confidence)."
            softens != null && softens <= 0.40 -> "Learned pattern: ${other.name} has not been softening on repair lately (${pct(softens)}% confidence) — a firmer boundary may serve better."
            softens != null && softens >= 0.70 -> "Learned pattern: ${other.name} softens when reassured (${pct(softens)}% confidence)."
            boundary != null && boundary >= 0.70 -> "Learned pattern: ${other.name} tends to respect a clear boundary (${pct(boundary)}% confidence)."
            else -> null
        }
    }
}
