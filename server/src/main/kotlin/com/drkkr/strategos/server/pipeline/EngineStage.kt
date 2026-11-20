package com.drkkr.strategos.server.pipeline

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.EngineDirective
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.IntentDetail
import com.drkkr.strategos.domain.agent.InferredStressTest
import com.drkkr.strategos.domain.agent.ModelSketch
import com.drkkr.strategos.domain.agent.StatedFloor
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.game.AnalysisIntegrity
import com.drkkr.strategos.domain.game.DecisionState
import com.drkkr.strategos.domain.game.GameAnalysisResult
import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.game.GameModelService
import com.drkkr.strategos.domain.game.LabelChecks
import com.drkkr.strategos.domain.human.DeliveryPlanner
import com.drkkr.strategos.domain.human.FallbackShift
import com.drkkr.strategos.domain.human.HotStateGate
import com.drkkr.strategos.domain.human.HumanSensitivity
import com.drkkr.strategos.domain.human.HumanizedView
import com.drkkr.strategos.domain.human.ManipulationRadar
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.human.ResponseModel
import com.drkkr.strategos.domain.intent.ConfidenceSummary
import com.drkkr.strategos.domain.intent.ExclusionFirst
import com.drkkr.strategos.domain.intent.IdentifyGaps
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.intent.IntentResult
import com.drkkr.strategos.domain.intent.IntentRouter
import com.drkkr.strategos.domain.intent.RecommendationDiff
import com.drkkr.strategos.domain.intent.RouteInput
import com.drkkr.strategos.domain.intent.StageGuard
import com.drkkr.strategos.domain.intent.TopOptions
import com.drkkr.strategos.domain.intent.TurnChain
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.WalkAwayMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Everything Stage 2 worked out for one turn. Nothing here is saved by Stage 2 itself. */
data class EngineOutcome(
    val directive: EngineDirective,
    /** The analysis of the human view (what the directive was built from). */
    val viewResult: PipelineResult,
    /** The analysis of the saved scenario, for the report digest and the engine's own text when the coach is unusable. */
    val savedResult: PipelineResult,
    val deal: DealFrame?,
    val view: Scenario,
    val gameResult: GameAnalysisResult,
)

/**
 * Stage 2 (SDD §1): the deterministic Kotlin engine. It runs the same steps, in the same order, as the Android app's turn
 * (OneShotTurn.engineGroundedReply): the analysis view with trust/tension, the deal frame and walk-away mask, the fallback
 * shift, the Human Layer, the R0–R5 pipeline, the stress test, the game solvers (Nash, IEDS, archetype, Shapley, mechanism
 * design), the intent router on copies, and EngineDirectiveBuilder with its guards.
 *
 * Not ported yet (app-only for now): Power Mode, the red-team challenge, the cross-project matcher and the plan-ahead close.
 */
object EngineStage {
    const val ENGINE_ANALYSIS_TIMEOUT_MS = 8_000L

    suspend fun run(
        saved: Scenario,
        trust: Double?, tension: Double?,
        text: String,
        recentUserText: String,
        previousReportJson: String?,
        intent: Intent = Intent.STANDARD,
        intentDetail: IntentDetail? = null,
        whatIfOps: List<ChangeOp> = emptyList(),
        turnOps: List<ChangeOp> = emptyList(),
        turnBefore: Scenario = saved,
        sketch: ModelSketch? = null,
        support: Boolean = false,
        holdCourse: Boolean = false,
        earlierReplies: List<String> = emptyList(),
    ): EngineOutcome = withContext(Dispatchers.Default) {
        val raw = EngineDirectiveBuilder.analysisView(saved, trust, tension)
        val deal = DealFrame.build(raw.negotiationContext, raw.humanContext, text, recent = recentUserText)
        val negotiated = TurnChain.negotiated(raw, deal)
        val fallback = FallbackShift.effect(negotiated, deal, support, false, HumanSensitivity.pick(AnalysisPipeline()))
        val view = HumanizedView.apply(fallback.view)

        val (viewResult, stress, gameContext) = coroutineScope {
            val a = async { AnalysisPipeline().run(view) }
            val b = async { runCatching { InferredStressTest.run(view, pipeline = AnalysisPipeline()) }.getOrNull() }
            val c = async {
                withTimeoutOrNull(ENGINE_ANALYSIS_TIMEOUT_MS) { GameModelService.analyzeWithContext(view, contextText = recentUserText) }
                    ?: GameModelService.AnalysisContext(
                        result = GameAnalysisResult.Partial(ir = null, partialOutcome = null, reason = "Engine analysis timed out",
                            decisionState = DecisionState.EXPLORATORY, integrity = AnalysisIntegrity.INCOMPLETE),
                        pruneResult = null,
                    )
            }
            Triple(a.await(), b.await(), c.await())
        }
        val savedResult = AnalysisPipeline().run(saved)

        val exclusionFirst = ExclusionFirst.filtered(view)
        val prune = if (exclusionFirst === view) gameContext.pruneResult else runCatching { GameModelService.pruneOnly(exclusionFirst) }.getOrNull()
        val ownerId = IntakeChecklist.self(view)?.id
        val dominatedIds = prune?.eliminated?.get(ownerId).orEmpty().toSet()
        val beta = ResponseModel.betaFrom(gameContext.opponentModel)

        val intentResult = if (support || intent == Intent.STANDARD) IntentResult.None else runCatching {
            val prepare = TurnChain.prepare(deal, fallback.sides)
            val decide = TurnChain.decide(deal, StatedFloor.from(view.negotiationContext?.reservationPoint, text))
            IntentRouter().route(RouteInput(intent, view, whatIfOps, intentDetail, base = raw, deal = deal, sides = fallback.sides,
                prepare = prepare, decide = decide, dominated = dominatedIds, beta = beta))
        }.getOrDefault(IntentResult.None)

        val humanFragile = runCatching { HumanSensitivity.pickChanges(negotiated, AnalysisPipeline(), fallback.sides) }.getOrDefault(false)
        val gameResult = gameContext.result
        val previousMove = EngineDirectiveBuilder.previousRecommendedMove(previousReportJson)
        val solvedIr = (gameResult as? GameAnalysisResult.Solved)?.ir
        val pattern = EngineDirectiveBuilder.archetypeLabel(solvedIr?.archetype?.name)
            ?: solvedIr?.takeIf { it.archetype == GameArchetype.GENERIC }?.let { "${it.playerCount}-player game (${it.strategyCounts.joinToString("×")})" }
        // Readable labels only: a stakeholder or action id never reaches the coach (SDD-05).
        val dominated = prune?.eliminated.orEmpty().flatMap { (pid, acts) ->
            val name = view.stakeholders.firstOrNull { it.id == pid }?.name ?: return@flatMap emptyList()
            acts.mapNotNull { act -> view.actions.firstOrNull { it.id == act }?.label?.let { "$name: $it (dominated)" } }
        }

        val base = EngineDirectiveBuilder.build(
            view, viewResult, stress, previousMove, gameResult.integrity, pattern, dominated,
            gameContext.biResult?.equilibriumPath.orEmpty(), gameContext.opponentModel?.coachNote(),
            gameContext.coalitionResult?.values, gameContext.mechanismHint?.let { "${it.type.name.lowercase()}: ${it.insight}" },
            statedFloor = StatedFloor.from(view.negotiationContext?.reservationPoint, text), humanFragile = humanFragile,
            opponentModel = gameContext.opponentModel, deal = deal, labelIssues = LabelChecks.check(negotiated),
            archetype = solvedIr?.archetype, fallbackLine = fallback.line, regretResult = gameContext.regretResult,
            beliefState = gameContext.beliefState, epistemicAdvice = gameContext.epistemicResult,
            selfModelAdvice = gameContext.selfModel?.let { "They likely see your approach as ${it.inferredStrategyLabel}" },
            bayesianNEUsed = gameContext.bayesianNE != null, extractionConfidence = gameContext.extractionConfidence,
            alternativeInterpretations = gameContext.alternativeInterpretations, focalSelection = gameContext.focalSelection,
            strategicNarrative = gameContext.strategicNarrative, sensitivityResult = gameContext.sensitivityResult,
            cvoiResult = gameContext.cvoiResult, bayesianCompletion = gameContext.bayesianCompletion, icSynthesis = gameContext.icSynthesis,
            surplusResult = gameContext.surplusResult, commitmentLadder = gameContext.commitmentLadder,
            infoAsymmetryScore = gameContext.infoAsymmetryScore,
        )
        val maskedRaw = WalkAwayMask.apply(raw, deal)
        val delivered = withDelivery(base, maskedRaw, gameContext.opponentModel, support, text)

        val diff = if (!delivered.ready) null else RecommendationDiff.of(intent, previousMove, delivered.recommendedMove, turnOps, turnBefore,
            saved, IntakeChecklist.self(saved)?.id, deal)
        val nextQuestion = if (support) null else runCatching {
            val gapPick = HumanSensitivity.pick(AnalysisPipeline())
            val flips = buildSet {
                if (FallbackShift.unknownFallbackMatters(negotiated, deal, support, false, gapPick)) add(IdentifyGaps.YOUR_FALLBACK)
                if (FallbackShift.unknownTheirFallbackMatters(negotiated, deal, support, false, gapPick)) add(IdentifyGaps.THEIR_FALLBACK)
            }
            IdentifyGaps.next(negotiated, IntakeChecklist.gaps(saved), stress, deal, flips, earlierReplies,
                advised = setOfNotNull(delivered.recommendedMove, delivered.moveToImplement))
        }.getOrNull()
        val revisited = if (!delivered.ready) null else StageGuard.revisited(sketch, saved, delivered.moveToImplement)
        val oneWayOnly = delivered.ready && StageGuard.oneWayOnly(saved)
        val nowOverWait = delivered.ready && StageGuard.nowOverWait(saved, delivered.moveToImplement)

        val directive = delivered.copy(
            changedSinceLast = diff, nextQuestion = nextQuestion.takeIf { delivered.ready },
            whatIf = (intentResult as? IntentResult.WhatIf)?.block?.takeIf { delivered.ready },
            compare = (intentResult as? IntentResult.Compare)?.block?.takeIf { delivered.ready },
            theirReaction = (intentResult as? IntentResult.TheirReaction)?.block?.takeIf { delivered.ready },
            confidence = if (!delivered.ready) null else ConfidenceSummary.of(
                delivered.confidenceLevel, delivered.stressVerdict == "FRAGILE", stress, delivered.labelIssues, nextQuestion,
                view.players().firstOrNull { it.id != ownerId }?.name, deal,
                oneWayOnly = oneWayOnly, revisitedUnchanged = revisited != null && revisited.changed == null, nowOverWait = nowOverWait),
            revisited = revisited,
            holdCourse = holdCourse && delivered.ready,
            topOptions = if (!delivered.ready || support) null else (intentResult as? IntentResult.TopOptionsList)?.options
                ?: if (TopOptions.wanted(intent, delivered.stressVerdict == "FRAGILE", delivered.autoTopOptions))
                    TopOptions.of(view, delivered.recommendedMove, dominatedIds).takeIf { it.size >= 2 } else null,
        )
        EngineOutcome(directive, viewResult, savedResult, deal, view, gameResult)
    }

    /** The Human Layer's delivery plan for the pick (the move itself is never changed). Same as the app's withDelivery. */
    private fun withDelivery(d0: EngineDirective, raw: Scenario, opponent: com.drkkr.strategos.domain.game.OpponentModel?, support: Boolean, userText: String): EngineDirective {
        val hc = raw.humanContext ?: return d0
        val d = d0.copy(pendingCooling = hc.pendingCooling)
        if (!d.ready) return d
        val owner = IntakeChecklist.self(raw) ?: return d
        val mine = raw.actionsFor(owner.id)
        val move = mine.firstOrNull { it.label.equals(d.moveToImplement, ignoreCase = true) } ?: return d
        val second = listOfNotNull(d.complementaryMove, d.safeFallbackMove)
            .firstNotNullOfOrNull { l -> mine.firstOrNull { it.label.equals(l, ignoreCase = true) && it.id != move.id } }
        val plan = DeliveryPlanner.plan(raw, hc, move, second, support, ResponseModel.betaFrom(opponent),
            userSaysNow = HotStateGate.userSaysNow(userText))
        val radar = ManipulationRadar.scan(hc, RelationshipDomains.of(hc.relationship, null))
        return d.copy(delivery = plan.takeIf { !it.isEmpty }, watchFor = radar.watchFor)
    }
}
