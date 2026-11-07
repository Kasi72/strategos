package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.ConfidenceLevel
import com.drkkr.strategos.domain.agent.StressResult
import com.drkkr.strategos.domain.game.LabelIssue
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.WalkAwayMask
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ConfidenceTier { HIGH, MODERATE, LOW_MISSING_KEY_FACT }

/**
 * ID3.5 (BLUEPRINT §5.5): how sure the advice is, with a fixed reason and the one missing fact, if any. It replaces nothing:
 * `confidence_level` still shapes the tone (coach rule 14); this adds the reason. Every line is a template filled from engine
 * values (I-D7).
 */
data class ConfidenceSummary(val tier: ConfidenceTier, val reason: String, val gap: String?) {
    fun toJson(): JsonObject = buildJsonObject {
        put("tier", tier.name); put("reason", reason); gap?.let { put("gap", it) }
    }

    companion object {
        const val HOLDS = "the advice holds when the assumptions are pushed both ways"
        const val FLIPS_ON = "the advice flips if the guess about {name}'s side is wrong"
        const val FLIPS_HUMAN = "the advice flips if people react more or less strongly than assumed"
        const val ONE_OPTION = "the AI's reading favoured one option everywhere"
        const val PARTLY_GUESSED = "part of the picture is still a best guess"
        const val MISSING = "one fact that would change the advice is still missing"
        /** [StageGuard.oneWayOnly]: every option took the step now. */
        const val ONE_WAY = "only doing it now was weighed, not waiting for a better moment"
        /** [StageGuard.revisited] with nothing changed: the pick was advised against before. */
        const val STILL_EARLY = "this was advised against earlier and nothing has changed since"
        /** [StageGuard.nowOverWait]: a not-yet option was weighed and doing it now came out ahead. */
        const val NOW_OR_WAIT = "doing it now rather than waiting for a better moment is a judgement call"

        val TEMPLATES = listOf(HOLDS, FLIPS_ON, FLIPS_HUMAN, ONE_OPTION, PARTLY_GUESSED, MISSING, ONE_WAY, STILL_EARLY, NOW_OR_WAIT)

        /** A gap whose answer could change the pick: the fallback gaps, the stress flip on their side, and the walk-away in a deal. */
        val KEY_GAPS = setOf(IdentifyGaps.YOUR_FALLBACK, IdentifyGaps.THEIR_FALLBACK, IdentifyGaps.THEIR_REPLY, IdentifyGaps.WALK_AWAY)

        /**
         * Tier: a key gap open → LOW_MISSING_KEY_FACT; else the AI's labels favouring one option, a FRAGILE pick or a confidence
         * level below HIGH → MODERATE; else HIGH. [counterpart] names whose side a stress flip is on. A pick that takes the step now
         * with no "not yet" option weighed ([oneWayOnly]), over one ([nowOverWait]), or a ruled-out move back unchanged
         * ([revisitedUnchanged]), is never HIGH.
         */
        fun of(
            level: ConfidenceLevel, fragile: Boolean, stress: StressResult?, issues: List<LabelIssue>, question: NextQuestion?,
            counterpart: String?, deal: DealFrame? = null, oneWayOnly: Boolean = false, revisitedUnchanged: Boolean = false,
            nowOverWait: Boolean = false
        ): ConfidenceSummary {
            val gap = question?.text
            if (question != null && question.id in KEY_GAPS) return ConfidenceSummary(ConfidenceTier.LOW_MISSING_KEY_FACT, MISSING, gap)
            val flippedOn = stress?.flips.orEmpty().map { it.stakeholder }.firstOrNull { it == counterpart }
            val reason = when {
                revisitedUnchanged -> STILL_EARLY
                oneWayOnly -> ONE_WAY
                nowOverWait -> NOW_OR_WAIT
                issues.any { it is LabelIssue.LabelDominance } -> ONE_OPTION
                fragile && flippedOn != null -> WalkAwayMask.text(FLIPS_ON.replace("{name}", flippedOn), deal)
                fragile -> FLIPS_HUMAN
                level != ConfidenceLevel.HIGH -> PARTLY_GUESSED
                else -> return ConfidenceSummary(ConfidenceTier.HIGH, HOLDS, gap)
            }
            return ConfidenceSummary(ConfidenceTier.MODERATE, reason, gap)
        }
    }
}
