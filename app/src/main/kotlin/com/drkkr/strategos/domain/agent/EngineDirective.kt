package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A move the engine ranks low or rules out, and the reaction it is expected to draw. */
data class BlockedMove(val move: String, val reason: String, val triggers: String?)

/**
 * What the on-device engine decided, in a few plain facts for the interpreting model (call 2). Everything here is computed
 * by Kotlin from the saved model, the pipeline result and the stress test. Nothing in it is model-written text.
 */
data class EngineDirective(
    val ready: Boolean,
    val recommendedMove: String?,
    val recommendedWhy: String,
    val supportingFrameworks: List<String>,
    val safeFallbackMove: String?,
    val blockedOrDominatedMoves: List<BlockedMove>,
    val dominantDynamic: List<String>,
    val topBehavioralModule: String?,
    val stressVerdict: String,                 // ROBUST | FRAGILE
    val stressFlip: String?,
    /** The recommended move, or the fallback when the stress test says the recommendation is fragile. */
    val moveToImplement: String?
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("move_to_implement", moveToImplement)
        put("recommended_move", recommendedMove)
        put("recommended_why", recommendedWhy)
        putJsonArray("supporting_frameworks") { supportingFrameworks.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        put("safe_fallback_move", safeFallbackMove)
        putJsonArray("blocked_or_dominated_moves") {
            blockedOrDominatedMoves.forEach { b -> addJsonObject { put("move", b.move); put("reason", b.reason); put("triggers", b.triggers) } }
        }
        putJsonArray("dominant_dynamic") { dominantDynamic.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        put("stress_verdict", stressVerdict)
        put("stress_flip", stressFlip)
    }

    /** One line from the same data, never from model text: `Engine: <move> · <module> · Robust`. */
    fun badge(): String {
        if (!ready || moveToImplement == null) return "Engine: not enough detail to rank your options yet"
        val move = if (moveToImplement.length > MAX_BADGE_MOVE) moveToImplement.take(MAX_BADGE_MOVE - 1).trimEnd() + "…" else moveToImplement
        return "Engine: $move · ${topBehavioralModule ?: "no single dominant pattern"} · ${if (stressVerdict == "FRAGILE") "Fragile" else "Robust"}"
    }

    companion object { private const val MAX_BADGE_MOVE = 60 }
}

object EngineDirectiveBuilder {
    private val BEHAVIORAL = setOf("relational-repair", "chicken-game", "prisoners-dilemma", "stag-hunt", "stakeholder-trust-dynamics", "pure-nash")
    private const val RECOMMENDED = "Recommended"
    private const val FALLBACK = "Safe Fallback"
    private const val MAX_BLOCKED = 2
    private const val MAX_DYNAMICS = 2
    private const val MAX_TEXT = 160

    fun build(s: Scenario, pr: PipelineResult, stress: StressResult?): EngineDirective {
        val report = pr.report
        val owner = IntakeChecklist.self(s)
        val ownerActionIds = owner?.let { o -> s.actionsFor(o.id).map { it.id }.toSet() }.orEmpty()
        val pathways = report?.pathways.orEmpty().filter { it.actionId == null || ownerActionIds.isEmpty() || it.actionId in ownerActionIds }
        val recommended = pathways.firstOrNull { it.label == RECOMMENDED && !it.excluded }
        // The ranked Safe Fallback; failing that, the move the stress test says the pick would flip to (also the engine's own answer).
        val flipTarget = stress?.flips?.firstNotNullOfOrNull { it.newRecommended }
        val fallbackFromStress = flipTarget?.let { t -> owner?.let { o -> s.actionsFor(o.id).firstOrNull { it.label.equals(t, ignoreCase = true) } } }
            ?.takeIf { it.id != recommended?.actionId }
        val fallbackPathway = pathways.firstOrNull { it.label == FALLBACK && !it.excluded }
        val fallbackLabel = fallbackPathway?.action ?: fallbackFromStress?.label
        val fallbackId = fallbackPathway?.actionId ?: fallbackFromStress?.id
        val filled = PayoffCoverage.of(s).filled
        if (report == null || filled == 0 || recommended == null) {
            return EngineDirective(false, null, "", emptyList(), null, emptyList(), emptyList(), null, "ROBUST", null, null)
        }

        val supporting = report.moduleResults.filter { r ->
            r.recommendedActions.any { it.actionId == recommended.actionId || it.label.equals(recommended.action, ignoreCase = true) }
        }.map { it.moduleName }.distinct().take(3)

        val dynamics = report.moduleResults.filter { it.moduleId in BEHAVIORAL && it.applicability.score > 0.0 }
            .sortedByDescending { it.applicability.score }.take(MAX_DYNAMICS)
        val dynamicTexts = dynamics.map { r ->
            val f = r.findings.firstOrNull()
            "${r.moduleName}: " + (f?.let { "${it.title}. ${it.detail}" } ?: r.explanation).take(MAX_TEXT)
        }

        val fragile = stress?.verdict == StressVerdict.FRAGILE
        val flip = stress?.flips?.firstOrNull()?.let { f ->
            "if ${f.stakeholder}'s outcome for \"${f.profileLabel}\" were ${if (f.direction == StressDirection.IMPROVE) "better" else "worse"}, the pick would move to ${f.newRecommended ?: "no clear pick"}"
        }
        val toImplement = if (fragile && fallbackLabel != null) fallbackLabel else recommended.action

        // Everything else the user could do: excluded options first, then the ones with the weakest outcome after the other side replies.
        val keep = setOfNotNull(recommended.actionId, fallbackId)
        val candidates = owner?.let { o -> s.actionsFor(o.id) }.orEmpty().filter { it.id !in keep }
        val excludedIds = pathways.filter { it.excluded }.mapNotNull { it.actionId }.toSet()
        val blocked = candidates.map { a ->
            val reply = bestReply(s, owner!!.id, a.id)
            val pathway = pathways.firstOrNull { it.actionId == a.id }
            val reason = when {
                a.id in excludedIds -> pathway?.constraintViolations?.firstOrNull()?.let { "it breaks \"$it\"" } ?: "ruled out by a limit"
                pathway != null -> pathway.rationale.take(MAX_TEXT)
                else -> "ranks below the recommended move"
            }
            Triple(a, BlockedMove(a.label, reason, reply?.text), if (a.id in excludedIds) -1e9 else (reply?.ownerValue ?: 0.0))
        }.sortedBy { it.third }.take(MAX_BLOCKED).map { it.second }

        return EngineDirective(
            ready = true, recommendedMove = recommended.action, recommendedWhy = recommended.rationale.take(MAX_TEXT),
            supportingFrameworks = supporting, safeFallbackMove = fallbackLabel, blockedOrDominatedMoves = blocked,
            dominantDynamic = dynamicTexts, topBehavioralModule = dynamics.firstOrNull()?.moduleName,
            stressVerdict = if (fragile) "FRAGILE" else "ROBUST", stressFlip = flip, moveToImplement = toImplement
        )
    }

    private class Reply(val text: String, val ownerValue: Double)

    /** The other player's best answer to [actionId] (by their own weighted outcome), and what it leaves the user with. */
    private fun bestReply(s: Scenario, ownerId: String, actionId: String): Reply? {
        val other = s.players().firstOrNull { it.id != ownerId } ?: return null
        val mine = s.payoffs.filter { it.stakeholderId == ownerId && it.actionProfile[ownerId] == actionId }
        val theirs = s.payoffs.filter { it.stakeholderId == other.id && it.actionProfile[ownerId] == actionId }
        val best = theirs.maxByOrNull { it.payoff.weighted(s.weights) } ?: return null
        val theirAction = best.actionProfile[other.id] ?: return null
        val label = s.actions.firstOrNull { it.id == theirAction }?.label ?: return null
        val myValue = mine.firstOrNull { it.actionProfile[other.id] == theirAction }?.payoff?.weighted(s.weights) ?: return null
        return Reply("${other.name} would most likely respond: $label", myValue)
    }
}
