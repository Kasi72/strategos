package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    /** A second non-reactive move that scores within 1.0 of the first, to be blended in rather than swapped for it. */
    val complementaryMove: String?,
    val blockedOrDominatedMoves: List<BlockedMove>,
    val dominantDynamic: List<String>,
    /** Plain-English name of the top behavioral pattern, shown in the badge. */
    val dynamicLabel: String?,
    val influencersInPlay: List<String>,
    val stressVerdict: String,                 // ROBUST | FRAGILE
    val stressFlip: String?,
    /** The recommended move, or the fallback when the pick is fragile. */
    val moveToImplement: String?
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("move_to_implement", moveToImplement)
        put("complementary_move", complementaryMove)
        put("recommended_move", recommendedMove)
        put("recommended_why", recommendedWhy)
        putJsonArray("supporting_frameworks") { supportingFrameworks.forEach { add(JsonPrimitive(it)) } }
        put("safe_fallback_move", safeFallbackMove)
        putJsonArray("blocked_or_dominated_moves") {
            blockedOrDominatedMoves.forEach { b -> addJsonObject { put("move", b.move); put("reason", b.reason); put("triggers", b.triggers) } }
        }
        putJsonArray("dominant_dynamic") { dominantDynamic.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("influencers_in_play") { influencersInPlay.forEach { add(JsonPrimitive(it)) } }
        put("stress_verdict", stressVerdict)
        put("stress_flip", stressFlip)
    }

    /** One line from the same data, never from model text: `Engine: <move> · <pattern> · Robust`. */
    fun badge(): String {
        if (!ready || moveToImplement == null) return "Engine: not enough detail to rank your options yet"
        val move = if (moveToImplement.length > MAX_BADGE_MOVE) moveToImplement.take(MAX_BADGE_MOVE - 1).trimEnd() + "…" else moveToImplement
        return "Engine: $move · ${dynamicLabel ?: "no single dominant pattern"} · ${if (stressVerdict == "FRAGILE") "Fragile" else "Robust"}"
    }

    companion object { private const val MAX_BADGE_MOVE = 60 }
}

object EngineDirectiveBuilder {
    /** Behavioral and strategic patterns, in plain words. Generic equilibrium is only a last resort. */
    private val PLAIN_LABEL = linkedMapOf(
        "relational-repair" to "Relationship repair",
        "stakeholder-trust-dynamics" to "Trust building over time",
        "chicken-game" to "Standoff over who gives way",
        "stag-hunt" to "Mutual trust needed",
        "prisoners-dilemma" to "Trust trap"
    )
    private const val GENERIC = "pure-nash"
    private const val GENERIC_LABEL = "A stable pattern"
    private const val RECOMMENDED = "Recommended"
    private const val FALLBACK = "Safe Fallback"
    private const val MAX_BLOCKED = 2
    private const val MAX_DYNAMICS = 2
    private const val MAX_TEXT = 200
    private const val MAX_INFLUENCERS = 4
    private const val COMPLEMENT_WITHIN = 1.0
    private const val MIN_DYNAMIC_SCORE = 0.5

    /**
     * The model the engine analyses: the saved one, read as a relationship that continues, with trust drawn from the trajectory.
     * Trust 60+ means the two trust each other, 40 to 59 means the user trusts the other person, and high tension (70+) or
     * low trust means neither is recorded as trusting. Nothing is saved from this view.
     */
    fun analysisView(s: Scenario, trust: Double?, tension: Double?): Scenario {
        val owner = IntakeChecklist.self(s) ?: return s.copy(repeated = true)
        val other = s.players().firstOrNull { it.id != owner.id } ?: return s.copy(repeated = true)
        val t = trust ?: 50.0
        val calm = (tension ?: 50.0) < 70.0
        return s.copy(repeated = true, stakeholders = s.stakeholders.map { p ->
            when (p.id) {
                owner.id -> p.copy(trustRelationships = if (calm && t >= 40.0) listOf(other.id) else emptyList())
                other.id -> p.copy(trustRelationships = if (calm && t >= 60.0) listOf(owner.id) else emptyList())
                else -> p
            }
        })
    }

    fun build(s: Scenario, pr: PipelineResult, stress: StressResult?): EngineDirective {
        val report = pr.report
        val owner = IntakeChecklist.self(s)
        val ownerActions = owner?.let { s.actionsFor(it.id) }.orEmpty()
        val byId = ownerActions.associateBy { it.id }
        val pathways = report?.pathways.orEmpty().filter { it.actionId == null || ownerActions.isEmpty() || it.actionId in byId }
        val cov = PayoffCoverage.of(s)
        val rankedPick = pathways.firstOrNull { it.label == RECOMMENDED && !it.excluded }
        if (report == null || owner == null || cov.filled == 0 || rankedPick == null) return notReady(s)

        fun posture(a: Action?) = a?.let { Postures.of(it) }
        fun reactive(a: Action?) = posture(a) == Postures.REACTIVE
        val replies = ownerActions.associate { it.id to bestReply(s, owner.id, it.id) }
        fun value(a: Action) = replies[a.id]?.ownerValue ?: Double.NEGATIVE_INFINITY
        val calmActions = ownerActions.filter { !reactive(it) }

        // The recommendation. A reactive move can never be it: if the ranking put one first, take the best calm move instead.
        val rankedAction = rankedPick.actionId?.let { byId[it] }
        val recommended: Action = (rankedAction?.takeIf { !reactive(it) } ?: calmActions.maxByOrNull { value(it) }) ?: return notReady(s)
        val recommendedPathway = pathways.firstOrNull { it.actionId == recommended.id }

        val supporting = report.moduleResults.filter { r ->
            r.recommendedActions.any { it.actionId == recommended.id || it.label.equals(recommended.label, ignoreCase = true) }
        }.map { it.moduleName }.distinct().take(3)

        // What the stress test says could flip the pick, and whether that matters.
        val flip = stress?.flips?.firstOrNull()
        val flipTargets = stress?.flips?.mapNotNull { f -> f.newRecommended?.let { l -> ownerActions.firstOrNull { it.label.equals(l, ignoreCase = true) } } }.orEmpty()
        val excludedIds = pathways.filter { it.excluded }.mapNotNull { it.actionId }.toSet()
        val flipsToBad = flipTargets.any { reactive(it) || it.id in excludedIds }
        val guessedOutcomes = cov.filled < cov.expected
        val fragile = flipsToBad || guessedOutcomes
        val flipText = flip?.let { f ->
            "if ${f.stakeholder}'s outcome for \"${f.profileLabel}\" were ${if (f.direction == StressDirection.IMPROVE) "better" else "worse"}, the pick would move to ${f.newRecommended ?: "no clear pick"}"
        }

        // A second calm move: close in score, or the one the stress test flips to. It is blended in, not swapped for the first.
        val others = calmActions.filter { it.id != recommended.id }
        val closest = others.maxByOrNull { value(it) }
        val complementary = (flipTargets.firstOrNull { !reactive(it) && it.id != recommended.id && it.id !in excludedIds }
            ?: closest?.takeIf { value(recommended) - value(it) <= COMPLEMENT_WITHIN && value(it) > Double.NEGATIVE_INFINITY })
        val rankedFallback = pathways.firstOrNull { it.label == FALLBACK && !it.excluded }?.actionId?.let { byId[it] }?.takeIf { !reactive(it) && it.id != recommended.id }
        val fallback = rankedFallback ?: complementary ?: closest

        // Moves to warn about: every reactive move first, then the weakest of the rest.
        val keep = setOfNotNull(recommended.id, fallback?.id, complementary?.id)
        val warnCandidates = ownerActions.filter { it.id !in keep }
        val blocked = warnCandidates.sortedWith(compareByDescending<Action> { reactive(it) }.thenByDescending { it.id in excludedIds }.thenBy { value(it) })
            .take(MAX_BLOCKED).map { a ->
                val pathway = pathways.firstOrNull { it.actionId == a.id }
                val reason = when {
                    reactive(a) -> "a reactive move; in a relationship that continues it costs trust later"
                    a.id in excludedIds -> pathway?.constraintViolations?.firstOrNull()?.let { "it breaks \"$it\"" } ?: "ruled out by a limit"
                    else -> pathway?.rationale?.take(MAX_TEXT) ?: "ranks below the recommended move"
                }
                BlockedMove(a.label, reason, replies[a.id]?.text)
            }

        val (dynamicTexts, dynamicLabel) = dynamics(s, report.moduleResults.map { it.moduleId to it })
        return EngineDirective(
            ready = true, recommendedMove = recommended.label, recommendedWhy = (recommendedPathway?.rationale ?: rankedPick.rationale).take(MAX_TEXT),
            supportingFrameworks = supporting, safeFallbackMove = fallback?.label, complementaryMove = complementary?.label,
            blockedOrDominatedMoves = blocked, dominantDynamic = dynamicTexts, dynamicLabel = dynamicLabel,
            influencersInPlay = s.stakeholders.filter { it.gameRole == GameRole.INFLUENCER }.map { it.name }.distinct().take(MAX_INFLUENCERS),
            stressVerdict = if (fragile) "FRAGILE" else "ROBUST", stressFlip = flipText,
            moveToImplement = if (fragile && fallback != null) fallback.label else recommended.label
        )
    }

    private fun notReady(s: Scenario) = EngineDirective(
        false, null, "", emptyList(), null, null, emptyList(), emptyList(), null,
        s.stakeholders.filter { it.gameRole == GameRole.INFLUENCER }.map { it.name }.take(MAX_INFLUENCERS), "ROBUST", null, null
    )

    // ── Behavioral patterns ───────────────────────────────────────────────────

    /**
     * Runs the behavioral and strategic modules on the two people's game, reduced to the choice that matters in a continuing
     * relationship: the user's calm move or their reactive one, against the other person's receptive or defensive reply.
     * Falls back to the whole model, then to generic equilibrium, only if nothing else applies.
     */
    private fun dynamics(s: Scenario, fromReport: List<Pair<String, com.drkkr.strategos.domain.framework.AnalysisResult>>): Pair<List<String>, String?> {
        val dyad = twoByTwo(s) ?: s
        val modules = AnalysisPipeline.defaultRegistry().all().filter { it.id in PLAIN_LABEL }
        val scored = modules.mapNotNull { m ->
            val a = runCatching { m.canAnalyze(dyad) }.getOrNull() ?: return@mapNotNull null
            if (a.score < MIN_DYNAMIC_SCORE) return@mapNotNull null
            val r = runCatching { m.analyze(dyad) }.getOrNull() ?: return@mapNotNull null
            Triple(m.id, a.score, r)
        }.sortedByDescending { it.second }.take(MAX_DYNAMICS)
        if (scored.isNotEmpty()) {
            val texts = scored.map { (id, _, r) ->
                val f = r.findings.firstOrNull()
                "${PLAIN_LABEL.getValue(id)}: " + (f?.let { "${it.title}. ${it.detail}" } ?: r.explanation).take(MAX_TEXT)
            }
            return texts to PLAIN_LABEL.getValue(scored.first().first)
        }
        val generic = fromReport.firstOrNull { it.first == GENERIC }?.second ?: return emptyList<String>() to null
        return listOf("$GENERIC_LABEL: " + (generic.findings.firstOrNull()?.let { "${it.title}. ${it.detail}" } ?: generic.explanation).take(MAX_TEXT)) to GENERIC_LABEL
    }

    /** The user's calm move and reactive move against the other person's receptive and defensive replies, as a 2×2 game. */
    private fun twoByTwo(s: Scenario): Scenario? {
        val owner = IntakeChecklist.self(s) ?: return null
        val other = s.players().firstOrNull { it.id != owner.id } ?: return null
        val mine = s.actionsFor(owner.id); val theirs = s.actionsFor(other.id)
        val reactive = mine.firstOrNull { Postures.of(it) == Postures.REACTIVE } ?: return null
        val calm = mine.firstOrNull { Postures.of(it) == Postures.REPAIR } ?: mine.firstOrNull { it.id != reactive.id } ?: return null
        val receptive = theirs.firstOrNull { Postures.of(it) == Postures.RECEPTIVE } ?: theirs.firstOrNull() ?: return null
        val defensive = theirs.firstOrNull { Postures.of(it) == Postures.DEFENSIVE } ?: theirs.firstOrNull { it.id != receptive.id } ?: return null
        val keepMine = listOf(calm, reactive); val keepTheirs = listOf(receptive, defensive)
        val ids = (keepMine + keepTheirs).map { it.id }.toSet()
        return s.copy(
            stakeholders = listOf(owner, other), actions = s.actions.filter { it.id in ids }.sortedBy { a -> (keepMine + keepTheirs).indexOfFirst { it.id == a.id } },
            payoffs = s.payoffs.filter { e -> e.actionProfile[owner.id] in keepMine.map { it.id } && e.actionProfile[other.id] in keepTheirs.map { it.id } },
            repeated = true
        )
    }

    // ── What the other side would do ──────────────────────────────────────────

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
