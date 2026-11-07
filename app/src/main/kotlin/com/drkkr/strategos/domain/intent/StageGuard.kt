package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.ModelSketch
import com.drkkr.strategos.domain.human.DeliveryTags
import com.drkkr.strategos.domain.human.RuledOut
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The pick is a move that was ruled out before ([earlier], its saved wording); [changed] is what the user said since, if anything. */
data class Revisited(val earlier: String, val changed: String?) {
    fun toJson(): JsonObject = buildJsonObject { put("earlier", earlier); changed?.let { put("changed", it) } }
}

/**
 * Two checks on the options Call 1 wrote, so a move that raises the stakes is not advised as a clear win by default (seen in testing,
 * 7 Nov: a wish to state serious intent got three options that all declared it now, and the engine
 * picked the best wording of the only move it was given).
 */
object StageGuard {
    const val STEPS_UP = "steps_up"
    const val NOT_YET = "not_yet"

    /** "You" has a move tagged [STEPS_UP] and none tagged [NOT_YET]: waiting for a better moment was never weighed. */
    fun oneWayOnly(s: Scenario): Boolean {
        val you = IntakeChecklist.self(s) ?: return false
        val tags = s.actionsFor(you.id).map { DeliveryTags.of(it) }
        return tags.any { STEPS_UP in it } && tags.none { NOT_YET in it }
    }

    /**
     * "You" has a [NOT_YET] option and the pick is not it: doing it now beat waiting, which is a judgement call, never a clear win.
     * This does not rely on Call 1 tagging the step-up move itself (seen live, 7 Nov: the not-yet option was tagged, the declaration
     * was not, and it was picked at HIGH confidence).
     */
    fun nowOverWait(s: Scenario, pick: String?): Boolean {
        val you = IntakeChecklist.self(s) ?: return false
        val mine = s.actionsFor(you.id)
        if (mine.none { NOT_YET in DeliveryTags.of(it) }) return false
        val p = pick?.let(::norm) ?: return false
        val picked = mine.firstOrNull { norm(it.label) == p } ?: return false
        return NOT_YET !in DeliveryTags.of(picked)
    }

    private fun norm(t: String) = t.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.')

    /** This turn's sketch marked the pick as a ruled-out move again (extraction rule 25). Null otherwise. */
    fun revisited(sketch: ModelSketch?, s: Scenario, pick: String?): Revisited? {
        val p = pick?.let(::norm)?.takeIf { it.isNotEmpty() } ?: return null
        val option = sketch?.options.orEmpty().filter { norm(it.owner) == "you" }.firstOrNull { o ->
            val l = norm(o.label)
            l == p || (minOf(l.length, p.length) >= 8 && (l.contains(p) || p.contains(l)))
        } ?: return null
        val entry = RuledOut.find(s.humanContext?.ruledOut.orEmpty(), option.revisits) ?: return null
        return Revisited(entry.label, option.changed?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "null" })
    }
}
