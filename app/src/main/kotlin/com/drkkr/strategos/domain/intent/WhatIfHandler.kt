package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.WhatIfRunner
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.negotiation.WalkAwayMask
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The `what_if` block (BLUEPRINT §5.1). Every word is Kotlin's, except [ifText], the change as the user put it (masked). */
data class WhatIfBlock(val ifText: String, val pickNow: String?, val pickThen: String?, val changed: Boolean, val whatShifted: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("if", ifText)
        pickNow?.let { put("pick_now", it) }
        pickThen?.let { put("pick_then", it) }
        put("changed", changed)
        put("what_shifted", whatShifted)
    }
}

/**
 * ID3.1: the hypothetical runs on a copy ([WhatIfRunner], which never changes its base), then through the main turn's own steps
 * ([RouteInput.prepare]: the walk-away view, the fallback shift, the Human Layer) and the directive's guards ([RouteInput.decide]),
 * so an option past the walk-away is excluded in the variant exactly as it is on a real turn. Nothing is saved (I-D3).
 */
object WhatIfHandler {
    const val DEFAULT_IF = "the change you described"

    /** The fixed `what_shifted` lines, filled from the comparison. */
    fun shifted(now: String?, then: String?): String = when {
        then == null -> "With that change, no move clearly comes out ahead."
        now == null -> "With that change, $then would be the move to make."
        now == then -> "With that change, $now would still be the better move."
        else -> "With that change, $then would become the better move instead of $now."
    }

    suspend fun run(input: RouteInput, pipeline: AnalysisPipeline = AnalysisPipeline()): IntentResult {
        if (input.whatIfOps.isEmpty()) return IntentResult.None
        val label = input.detail?.change?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_IF
        val outcome = WhatIfRunner.run(input.base, input.whatIfOps, pipeline, label)
        // nothing of the hypothetical could be applied: no block, rather than "the advice holds" about a change that never ran
        val rejected = (outcome.comparison["rejected_ops"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        if (rejected >= input.whatIfOps.size) return IntentResult.None
        val now = input.decide(input.view)
        val then = input.decide(input.prepare(outcome.variant))
        return IntentResult.WhatIf(WhatIfBlock(
            ifText = WalkAwayMask.text(label, input.deal).take(MAX_IF), pickNow = now, pickThen = then,
            changed = now != then, whatShifted = shifted(now, then)
        ))
    }

    private const val MAX_IF = 200
}
