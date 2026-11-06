package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.game.AnalysisIntegrity
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Confidence in the engine's recommendation, derived from analysis integrity and archetype classification. */
enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

/** Highest-stakes dimension present in the scenario's weight map. */
enum class StakesTier { HIGH, MEDIUM, LOW }

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
    val moveToImplement: String?,
    /**
     * Phase F.5: fears, wants and limits already on record, plus the previous turn's move when it differs from this
     * one's. Computed by Kotlin from the saved model, never from model text. Call 2 must check this before writing
     * advice, so it stops re-asking a question whose answer is already here (seen live: Mark's stated fear of eating
     * dinner alone being ignored in favor of "ask how he's feeling").
     */
    val knownFacts: List<String> = emptyList(),
    /** Phase F.6: the strongest pattern [BayesianUpdater] has learned about the counterpart's track record, or null if nothing has moved off the defaults yet. */
    val bayesianLearningNote: String? = null,
    /** The top behavioral module id (e.g. "prisoners-dilemma"), so the Overview can look it up in [PatternCatalog]. Null when no single pattern dominates. */
    val dynamicArchetype: String? = null,
    /** Integrity of the game-theoretic analysis that produced this directive. */
    val analysisIntegrity: AnalysisIntegrity = AnalysisIntegrity.COMPLETE,
    /**
     * Non-null when payoff data is missing; carries the single highest-value question to ask next.
     * Populated by [EvsiClarifier] so call 2 can surface a targeted clarification instead of generic advice.
     */
    val clarification: Clarification? = null,
    /** Named game-theory archetype detected by [GameArchetypeDetector], e.g. "Prisoner's Dilemma". Null for generic games. */
    val gameTheoreticPattern: String? = null,
    /** Strategies eliminated by IEDS, e.g. ["Alice: Cooperate (dominated)", "Bob: Cooperate (dominated)"]. */
    val dominatedStrategies: List<String> = emptyList(),
    /** SPE action path from backward induction when a game tree exists, e.g. ["Negotiate", "Accept"]. */
    val extensiveFormPath: List<String> = emptyList(),
    /** One-line rationality note from [OpponentModel], e.g. "mostly rational, patient". Null when priors not yet learned. */
    val opponentRationality: String? = null,
    /** Strongest counterargument surfaced by the red-team call. Null when not run or nothing significant found. */
    val redTeamChallenge: String? = null,
    /** How confident we are in this directive, based on analysis integrity and whether a dominant-strategy archetype was detected. */
    val confidenceLevel: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    /** Highest-stakes dimension in the scenario — used to scale response depth. */
    val stakesTier: StakesTier = StakesTier.MEDIUM,
    /** Note when one player's perspective is much better known than the other's. Null when info is balanced or absent. */
    val infoAsymmetry: String? = null,
    /** Shapley value allocation for 3–8 player games. Key = player id, value = marginal contribution. */
    val shapleyValues: Map<String, Double>? = null,
    /** Mechanism design hint when the user wants to design rules rather than play a move. */
    val mechanismHint: String? = null,
    /** Human Layer: how to deliver the pick (null without a human context, or when there is a safety need). See DeliveryPlanner. */
    val delivery: com.drkkr.strategos.domain.human.Delivery? = null,
    /** Human Layer: patterns being used on the user, with the protective move. Filled by the radar. */
    val watchFor: List<com.drkkr.strategos.domain.human.WatchFor> = emptyList(),
    /** Human Layer: one plain line for the X-ray sheet. Never sent to the coach. */
    val humanLens: String? = null,
    /** Human Layer: the move that was held back last time; the coach opens by asking how it went. */
    val pendingCooling: String? = null,
    /** Negotiation Numbers: the figures the coach may use, and the walk-away for checking only. Null without a deal frame (I-N1). */
    val deal: com.drkkr.strategos.domain.negotiation.DealBlock? = null,
    /** Power Mode: a fixed line declining a manipulative request (`DeclineNotes`), set in any mode. */
    val declineNote: String? = null,
    /** Power Mode is on for this conversation (I-P1). The coach gets the flag and the hints, never an id, a law or a source. */
    val powerMode: Boolean = false
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
        // A robust pick needs no "if X were better, the pick would move" line; only a fragile one shows what would flip it.
        if (stressVerdict == "FRAGILE") put("stress_flip", stressFlip)
        putJsonArray("known_stakeholder_facts") { knownFacts.forEach { add(JsonPrimitive(it)) } }
        put("bayesian_learning_note", bayesianLearningNote)
        put("dynamic_archetype", dynamicArchetype)
        // analysis_integrity is kept as a Kotlin field for certificate/UI use; intentionally excluded
        // from the LLM request JSON to stay within the lean token budget.
        clarification?.let { put("clarification_question", it.question) }
        gameTheoreticPattern?.let { put("game_theoretic_pattern", it) }
        if (dominatedStrategies.isNotEmpty()) putJsonArray("dominated_strategies") { dominatedStrategies.forEach { add(JsonPrimitive(it)) } }
        if (extensiveFormPath.isNotEmpty()) putJsonArray("extensive_form_path") { extensiveFormPath.forEach { add(JsonPrimitive(it)) } }
        opponentRationality?.let { put("opponent_rationality", it) }
        redTeamChallenge?.let { put("red_team_challenge", it) }
        put("confidence_level", confidenceLevel.name)
        put("stakes_tier", stakesTier.name)
        infoAsymmetry?.let { put("info_asymmetry", it) }
        shapleyValues?.let { sv -> putJsonObject("shapley_values") { sv.forEach { (k, v) -> put(k, v) } } }
        mechanismHint?.let { put("mechanism_hint", it) }
        // Human Layer: the coach gets the plain-language hints and the how/when/where, never a tactic's name or where it comes from.
        delivery?.takeIf { !it.isEmpty }?.let { d ->
            putJsonObject("delivery") {
                putJsonArray("tactics") { d.tactics.forEach { add(JsonPrimitive(it.hint)) } }
                put("timing", d.timing.name)
                d.channel?.let { put("channel", it.name) }
                // not "say_less": that is a tactic name, and no tactic name may reach the coach (TC_HL_DIR_01)
                if (d.sayLess) put("keep_it_short", true)
                d.defaultOffer?.let { put("default_offer", it) }
                if (d.optionsToOffer.isNotEmpty()) putJsonArray("options_to_offer") { d.optionsToOffer.forEach { add(JsonPrimitive(it)) } }
            }
        }
        pendingCooling?.let { put("pending_cooling", it) }
        if (watchFor.isNotEmpty()) putJsonArray("watch_for") { watchFor.forEach { w -> addJsonObject { put("sign", w.sign); put("protect", w.protect) } } }
        deal?.let { put("deal", it.toJson()) }
        if (powerMode) put("power_mode", true)
        declineNote?.let { put("decline_note", it) }
    }

    /**
     * One line from the same data, never from model text: `Engine: <move> · <pattern> · Robust`.
     * The move is shown in full: a hard character cap previously chopped it mid-word (e.g. "share small t…"
     * instead of "share small things"), which read as a cut-off or broken answer. Nothing here is ever trimmed.
     */
    fun badge(): String =
        if (!ready || moveToImplement == null) "Engine: not enough detail to rank your options yet"
        else {
            val confidence = when (confidenceLevel) {
                ConfidenceLevel.HIGH -> "High confidence"
                ConfidenceLevel.MEDIUM -> "Medium confidence"
                ConfidenceLevel.LOW -> "Limited info"
            }
            "Engine: $moveToImplement · ${dynamicLabel ?: "no single dominant pattern"} · ${if (stressVerdict == "FRAGILE") "Fragile" else "Robust"} · $confidence"
        }
}

object EngineDirectiveBuilder {
    private val ARCHETYPE_LABEL = mapOf(
        "PRISONERS_DILEMMA" to "Prisoner's Dilemma",
        "CHICKEN" to "Chicken (Hawk-Dove)",
        "STAG_HUNT" to "Stag Hunt",
        "BATTLE_OF_SEXES" to "Battle of the Sexes",
        "ZERO_SUM" to "Zero-Sum",
        "PURE_COORDINATION" to "Pure Coordination",
        "HARMONY" to "Harmony",
        "DEADLOCK" to "Deadlock (No-Dilemma Conflict)",
        "LEADER" to "Leader Game",
        "HERO" to "Hero Game (Exploited Cooperator)"
        // GENERIC intentionally absent — not surfaced to the LLM
    )

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
    private const val MAX_KNOWN_FACTS = 8
    private const val WALK_AWAY_REASON = "it crosses your walk-away"
    private const val WALK_AWAY_WHY = "The strongest of your options that stays within your walk-away."

    /** Archetypes with dominant-strategy (or minimax) solutions — high analytical confidence. */
    private val HIGH_CONFIDENCE_LABELS = setOf(
        "Prisoner's Dilemma", "Harmony", "Deadlock (No-Dilemma Conflict)",
        "Leader Game", "Hero Game (Exploited Cooperator)", "Zero-Sum"
    )
    private val HIGH_STAKES_DIMS = setOf("SAFETY", "LEGALITY", "PUBLIC_TRUST")
    private val MEDIUM_STAKES_DIMS = setOf("COST", "REPUTATION", "ETHICS")

    /** Translates a [GameArchetype] enum name to a human-readable label for the Coach prompt. */
    fun archetypeLabel(archetypeName: String?): String? = archetypeName?.let { ARCHETYPE_LABEL[it] }

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

    // Audit caveats from the report engine are for the Report screen; the coach should not repeat them to the user.
    private val auditCaveat = Regex("""(?i)no evidence sources recorded[^.]*\.?|one or more hard constraints are unassessed[^.]*\.?""")
    // The report's "Aligns with target: …" note is for the Report screen; the coach gets the target through the deal frame only.
    private val targetNote = Regex("""\s*Aligns with target: [^\n]*?\.(?=\s|$)""")

    private fun cleanWhy(raw: String): String =
        raw.replace(auditCaveat, " ").replace(targetNote, " ").replace(Regex("""\s*—\s*(?=\s|$)"""), "").replace(Regex("""\s{2,}"""), " ").trim()

    fun build(
        s: Scenario, pr: PipelineResult, stress: StressResult?,
        previousMove: String? = null,
        analysisIntegrity: AnalysisIntegrity = AnalysisIntegrity.COMPLETE,
        gameTheoreticPattern: String? = null,
        dominatedStrategies: List<String> = emptyList(),
        extensiveFormPath: List<String> = emptyList(),
        opponentRationality: String? = null,
        shapleyValues: Map<String, Double>? = null,
        mechanismHint: String? = null,
        /** A figure the user has said they will not go below (walk-away, or "I need X"); options that lead with less are never recommended. */
        statedFloor: Double? = null,
        /** Human Layer (I-H6): the pick changes when the Human Layer's constants are halved or doubled, so it is not a safe pick. */
        humanFragile: Boolean = false,
        /** How noisy the other side is; sets how sharply the Human Layer expects them to pick their best reply. */
        opponentModel: com.drkkr.strategos.domain.game.OpponentModel? = null,
        /** Negotiation Numbers: the user's figures, read in one unit and direction. Null keeps the human-layer directive exactly (I-N1). */
        deal: com.drkkr.strategos.domain.negotiation.DealFrame? = null
    ): EngineDirective {
        val report = pr.report
        val owner = IntakeChecklist.self(s)
        val ownerActions = owner?.let { s.actionsFor(it.id) }.orEmpty()
        val byId = ownerActions.associateBy { it.id }
        val pathways = report?.pathways.orEmpty().filter { it.actionId == null || ownerActions.isEmpty() || it.actionId in byId }
        val cov = PayoffCoverage.of(s)
        val clarification = if (cov.missingProfiles.isNotEmpty()) EvsiClarifier.clarify(s) else null
        val rankedPick = pathways.firstOrNull { it.label == RECOMMENDED && !it.excluded }
        // Negotiation Numbers: when the walk-away rules out the user's top option, the report's next pick can be the OTHER side's
        // move, which leaves the user no ranked pick. Then the best calm option within the walk-away is taken (as for a reactive pick).
        val walkAwayPick = deal != null && rankedPick == null &&
            pathways.any { p -> p.excluded && p.constraintViolations.any { com.drkkr.strategos.domain.negotiation.NegotiationView.CONSTRAINT_ID in it } }
        if (report == null || owner == null || cov.filled == 0 || (rankedPick == null && !walkAwayPick)) return notReady(s, clarification)

        fun posture(a: Action?) = a?.let { Postures.of(it) }
        fun reactive(a: Action?) = posture(a) == Postures.REACTIVE
        val replies = ownerActions.associate { it.id to bestReply(s, owner.id, it.id) }
        // Human Layer: with a human context the reply is a probability (noisy best reply); without one, today's hard best reply, unchanged.
        val human = s.humanContext != null
        val beta = com.drkkr.strategos.domain.human.ResponseModel.betaFrom(opponentModel)
        fun value(a: Action) =
            (if (human) com.drkkr.strategos.domain.human.ResponseModel.expectedValue(s, owner.id, a.id, beta) else null)
                ?: replies[a.id]?.ownerValue ?: Double.NEGATIVE_INFINITY
        // With a deal frame the walk-away is read in full and in its direction; without one, exactly as before (I-N1).
        val walk = deal?.walkAway
        fun belowFloor(a: Action?) = a != null &&
            if (deal != null && walk != null) StatedFloor.leadsBelow(a.label, walk.value, deal.direction, walk.unit) else StatedFloor.leadsBelow(a.label, statedFloor)
        val calmActions = ownerActions.filter { !reactive(it) && !belowFloor(it) }

        // The recommendation. A reactive move can never be it: if the ranking put one first, take the best calm move instead.
        val rankedAction = rankedPick?.actionId?.let { byId[it] }
        val recommended: Action = (rankedAction?.takeIf { !reactive(it) && !belowFloor(it) } ?: calmActions.maxByOrNull { value(it) }) ?: return notReady(s)
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
        val fragile = flipsToBad || guessedOutcomes || humanFragile
        val flipText = flip?.let { f ->
            val moveDesc = f.newRecommended?.let { "shift to $it" } ?: "lose its clear ranking"
            "If ${f.stakeholder}'s situation changes, the best move could $moveDesc."
        } ?: "how they take this depends on how you put it, so the pick could change if they react more or less strongly than assumed".takeIf { humanFragile }

        // A second calm move: close in score, or the one the stress test flips to. It is blended in, not swapped for the first.
        // Phase F.5: never pick a "discovery" move (asking what the other person feels or is worried about) as the
        // complement once that person's fear or want is already on record — it would just re-ask an answered question.
        val other = s.players().firstOrNull { it.id != owner.id }
        val otherAlreadyKnown = other != null && (other.fears.isNotEmpty() || other.goals.isNotEmpty())
        fun redundantDiscovery(a: Action) = otherAlreadyKnown && Postures.isDiscoveryQuestion(a.label)
        val others = calmActions.filter { it.id != recommended.id && !redundantDiscovery(it) }
        val closest = others.maxByOrNull { value(it) }
        val complementary = (flipTargets.firstOrNull { !reactive(it) && !belowFloor(it) && it.id != recommended.id && it.id !in excludedIds && !redundantDiscovery(it) }
            ?: closest?.takeIf { value(recommended) - value(it) <= COMPLEMENT_WITHIN && value(it) > Double.NEGATIVE_INFINITY })
        val rankedFallback = pathways.firstOrNull { it.label == FALLBACK && !it.excluded }?.actionId?.let { byId[it] }?.takeIf { !reactive(it) && !belowFloor(it) && it.id != recommended.id }
        val fallback = rankedFallback ?: complementary ?: closest

        // Moves to warn about: every reactive move first, then the weakest of the rest.
        val keep = setOfNotNull(recommended.id, fallback?.id, complementary?.id)
        val warnCandidates = ownerActions.filter { it.id !in keep }
        // Phase F.7: `twoByTwo()` (used below for the behavioral pattern) pairs only the FIRST reactive-tagged
        // action against the counterpart's replies. A second, unrelated reactive action this turn (e.g. "keep
        // scrolling rental listings" once the topic has moved past the relationship) is never actually part of that
        // modeled exchange, so it must not get "<counterpart> would most likely respond: ..." attributed to it --
        // seen live as an incoherent "it'll make [name] work around you through friends" on an unrelated habit.
        val canonicalReactiveId = ownerActions.firstOrNull { reactive(it) }?.id
        val blocked = warnCandidates.sortedWith(compareByDescending<Action> { reactive(it) }.thenByDescending { it.id in excludedIds }.thenBy { value(it) })
            .take(MAX_BLOCKED).map { a ->
                val pathway = pathways.firstOrNull { it.actionId == a.id }
                val reason = when {
                    reactive(a) -> "a reactive move; in a relationship that continues it costs trust later"
                    // the walk-away's figure is never named here: it reaches the coach only as walk_away_private (PLAN §3.4)
                    a.id in excludedIds -> pathway?.constraintViolations?.firstOrNull()?.let {
                        if (com.drkkr.strategos.domain.negotiation.NegotiationView.CONSTRAINT_ID in it) WALK_AWAY_REASON else "it breaks \"$it\""
                    } ?: "ruled out by a limit"
                    else -> pathway?.rationale?.take(MAX_TEXT) ?: "ranks below the recommended move"
                }
                val likely = if (human) com.drkkr.strategos.domain.human.ResponseModel.likelyReply(s, owner.id, a.id, beta) else null
                val replyText = likely?.let {
                    "${com.drkkr.strategos.domain.human.ResponseModel.otherName(s, owner.id)} would most likely respond: ${it.replyLabel}" +
                        if (it.couldGoEitherWay) " (could go either way)" else ""
                } ?: replies[a.id]?.text
                val triggers = replyText?.takeIf { !reactive(a) || a.id == canonicalReactiveId }
                BlockedMove(a.label, reason, triggers)
            }

        val (dynamicTexts, dynamicLabel, dynamicArchetype) = dynamics(s, report.moduleResults.map { it.moduleId to it })

        // Confidence: HIGH when analysis is complete AND the archetype has a dominant-strategy solution.
        val confidence = when {
            analysisIntegrity != AnalysisIntegrity.COMPLETE -> ConfidenceLevel.LOW
            gameTheoreticPattern in HIGH_CONFIDENCE_LABELS -> ConfidenceLevel.HIGH
            else -> ConfidenceLevel.MEDIUM
        }

        // Stakes tier: driven by the highest-priority dimension actually used in payoffs (not the default weight map, which always includes SAFETY).
        val activeDims = s.payoffs.flatMap { it.payoff.values.keys }.distinct().toSet()
        val tier = when {
            activeDims.any { it.name in HIGH_STAKES_DIMS } -> StakesTier.HIGH
            activeDims.any { it.name in MEDIUM_STAKES_DIMS } -> StakesTier.MEDIUM
            else -> StakesTier.LOW
        }

        // Information asymmetry: flag when one side is much better understood than the other.
        fun knownCount(p: com.drkkr.strategos.domain.model.Stakeholder) =
            p.fears.count { it.isNotBlank() } + p.goals.count { it.isNotBlank() }
        val ownerKnown = owner.let { knownCount(it) }
        val otherKnown = other?.let { knownCount(it) } ?: 0
        val asymmetry = when {
            ownerKnown >= 2 && otherKnown == 0 ->
                "Your perspective is well-captured, but nothing is known about the other person's fears or goals."
            otherKnown >= 2 && ownerKnown == 0 ->
                "More is recorded about the other person than about your own position."
            else -> null
        }

        return EngineDirective(
            ready = true, recommendedMove = recommended.label, recommendedWhy = com.drkkr.strategos.domain.negotiation.WalkAwayMask.text(cleanWhy(recommendedPathway?.rationale ?: rankedPick?.rationale ?: WALK_AWAY_WHY), deal).take(MAX_TEXT),
            supportingFrameworks = supporting, safeFallbackMove = fallback?.label, complementaryMove = complementary?.label,
            blockedOrDominatedMoves = blocked, dominantDynamic = dynamicTexts, dynamicLabel = dynamicLabel,
            influencersInPlay = s.stakeholders.filter { it.gameRole == GameRole.INFLUENCER }.map { it.name }.distinct().take(MAX_INFLUENCERS),
            stressVerdict = if (fragile) "FRAGILE" else "ROBUST", stressFlip = flipText,
            moveToImplement = if (fragile && fallback != null) fallback.label else recommended.label,
            // CP-N: the walk-away and the fallback never reach the coach as a figure (a saved fear, a limit or last turn's move can carry one).
            knownFacts = knownFacts(s, previousMove?.let { com.drkkr.strategos.domain.negotiation.WalkAwayMask.text(it, deal) }, recommended.label, deal).map { com.drkkr.strategos.domain.negotiation.WalkAwayMask.text(it, deal) },
            bayesianLearningNote = BayesianUpdater.learningNote(s),
            dynamicArchetype = dynamicArchetype,
            analysisIntegrity = analysisIntegrity,
            clarification = clarification,
            gameTheoreticPattern = gameTheoreticPattern,
            // The solver's dominance view and the pathway ranking can disagree; never tell the coach that a move it is given
            // (the pick, the safe fallback or the complementary move) is dominated.
            dominatedStrategies = listOfNotNull(recommended.label, fallback?.label, complementary?.label).let { given ->
                dominatedStrategies.filterNot { d -> given.any { d.endsWith(": $it (dominated)") || d == "$it (dominated)" } }
            },
            extensiveFormPath = extensiveFormPath,
            opponentRationality = opponentRationality,
            confidenceLevel = confidence,
            stakesTier = tier,
            infoAsymmetry = asymmetry,
            shapleyValues = shapleyValues,
            mechanismHint = mechanismHint,
            deal = deal?.let(com.drkkr.strategos.domain.negotiation.DealBlock::from)
        )
    }

    /** Fears, wants and limits already saved, plus the previous turn's move when it is not the same as this one's. */
    /**
     * The negotiation context gives only the target here (with a deal frame; without one the directive stays the baseline, I-N1).
     * The walk-away and the fallback are never facts: with a frame the walk-away reaches the coach as `walk_away_private` only.
     * Without a frame (a range, mixed units) nothing masks them, so a fact that carries one of their figures is left out.
     */
    private fun knownFacts(s: Scenario, previousMove: String?, recommendedLabel: String,
                           deal: com.drkkr.strategos.domain.negotiation.DealFrame? = null): List<String> = buildList {
        s.negotiationContext?.aspirationPoint?.takeIf { deal != null && it.isNotBlank() }?.let { add("Target outcome: ${it.take(100)}") }
        s.stakeholders.forEach { p ->
            p.fears.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add("${p.name} fears: $it") }
            p.goals.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add("${p.name} wants: $it") }
        }
        s.constraints.filter { it.id != com.drkkr.strategos.domain.negotiation.NegotiationView.CONSTRAINT_ID }.forEach { c -> add("Limit: ${c.label}") }
        if (previousMove != null && previousMove != recommendedLabel) add("Last turn's recommended move: $previousMove")
    }.let { facts -> if (deal != null) facts else facts.filterNot { carriesPrivateFigure(it, s.negotiationContext) } }.take(MAX_KNOWN_FACTS)

    private val wholeNumber = Regex("""\d[\d,.]*""")

    /** True when [fact] names a number from the walk-away or the fallback as written ("40" or "45" from "between 40 and 45k"). */
    private fun carriesPrivateFigure(fact: String, nc: com.drkkr.strategos.domain.model.NegotiationContext?): Boolean {
        nc ?: return false
        fun nums(t: String) = wholeNumber.findAll(t).map { it.value.trimEnd('.', ',').replace(",", "") }.filter { it.isNotEmpty() }.toSet()
        val private = nums(nc.reservationPoint) + nums(nc.batna)
        return private.isNotEmpty() && nums(fact).any { it in private }
    }

    /** The previous turn's recommended move, read from the report digest stored before this turn overwrites it. */
    fun previousRecommendedMove(reportJson: String?): String? {
        val o = reportJson?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        val pathways = (o["pathways"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        return pathways.firstOrNull { (it["label"] as? JsonPrimitive)?.content == RECOMMENDED }
            ?.get("action")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    private fun notReady(s: Scenario, clarification: Clarification? = null) = EngineDirective(
        ready = false, recommendedMove = null, recommendedWhy = "", supportingFrameworks = emptyList(),
        safeFallbackMove = null, complementaryMove = null, blockedOrDominatedMoves = emptyList(),
        dominantDynamic = emptyList(), dynamicLabel = null,
        influencersInPlay = s.stakeholders.filter { it.gameRole == GameRole.INFLUENCER }.map { it.name }.take(MAX_INFLUENCERS),
        stressVerdict = "ROBUST", stressFlip = null, moveToImplement = null,
        analysisIntegrity = AnalysisIntegrity.BLOCKED,
        clarification = clarification
    )

    // ── Behavioral patterns ───────────────────────────────────────────────────

    /**
     * Runs the behavioral and strategic modules on the two people's game, reduced to the choice that matters in a continuing
     * relationship: the user's calm move or their reactive one, against the other person's receptive or defensive reply.
     * Falls back to the whole model, then to generic equilibrium, only if nothing else applies.
     */
    private fun dynamics(s: Scenario, fromReport: List<Pair<String, com.drkkr.strategos.domain.framework.AnalysisResult>>): Triple<List<String>, String?, String?> {
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
            return Triple(texts, PLAIN_LABEL.getValue(scored.first().first), scored.first().first)
        }
        val generic = fromReport.firstOrNull { it.first == GENERIC }?.second ?: return Triple(emptyList(), null, null)
        return Triple(listOf("$GENERIC_LABEL: " + (generic.findings.firstOrNull()?.let { "${it.title}. ${it.detail}" } ?: generic.explanation).take(MAX_TEXT)), GENERIC_LABEL, GENERIC)
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

/** Reads a figure the user will not go below, and tests whether an option's wording leads with less than it. */
object StatedFloor {
    private val number = Regex("""\d+(?:\.\d+)?""")
    /** The words that introduce a figure the user will not go below; [com.drkkr.strategos.domain.negotiation.DealFrame] reads the same ones. */
    const val NEED_PHRASES = """i need|i must get|at least|no less than|not less than|minimum(?: of)?|walk[- ]?away(?: is| at)?"""
    private val need = Regex("""(?i)\b(?:$NEED_PHRASES)\s*(?:is\s*)?\D{0,12}?(\d+(?:\.\d+)?)""")

    /** The larger of the saved reservation point and any "I need X" / "at least X" in the message. */
    fun from(reservationPoint: String?, message: String): Double? {
        val saved = reservationPoint?.let { number.find(it)?.value?.toDoubleOrNull() }
        val said = need.findAll(message).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull()
        return listOfNotNull(saved, said).maxOrNull()
    }

    /**
     * Negotiation Numbers: the same test, aware of direction. For a buyer (LOWER_IS_BETTER) the violation is a figure ABOVE the
     * walk-away; the old two-argument form read every lower figure as "below the floor" and so blocked a buyer's lower offer.
     * Figures are read in full ("48,000", "95k") with [com.drkkr.strategos.domain.negotiation.DealNumbers], in [unit] only.
     */
    fun leadsBelow(
        label: String, floor: Double?, direction: com.drkkr.strategos.domain.negotiation.Direction,
        unit: com.drkkr.strategos.domain.negotiation.Amount.Unit = com.drkkr.strategos.domain.negotiation.Amount.Unit.AMOUNT
    ): Boolean {
        if (floor == null) return false
        val lead = com.drkkr.strategos.domain.negotiation.DealNumbers.figures(label).firstOrNull { it.unit == unit }?.value ?: return false
        return if (direction == com.drkkr.strategos.domain.negotiation.Direction.LOWER_IS_BETTER) lead > floor else lead < floor
    }

    /** True when the label's first figure is below [floor]. Labels with no figure are never judged. */
    fun leadsBelow(label: String, floor: Double?): Boolean {
        if (floor == null) return false
        val lead = number.find(label)?.value?.toDoubleOrNull() ?: return false
        return lead < floor
    }
}
