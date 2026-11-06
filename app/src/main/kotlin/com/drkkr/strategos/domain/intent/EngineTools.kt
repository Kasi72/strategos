package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.model.Scenario

/**
 * A plain Kotlin facade over the existing engine (BLUEPRINT §4.2): the intent handlers call these functions directly. No LLM
 * tool loop; the agent loop stays off (I-D1). Each later step adds its typed function here (whatIf, compare, topOptions,
 * theirReaction, planAhead, identifyGaps).
 */
object EngineTools {
    private const val RECOMMENDED = "Recommended"

    /** The engine's full run on [view]. */
    fun analyze(view: Scenario, pipeline: AnalysisPipeline = AnalysisPipeline()): PipelineResult = pipeline.run(view)

    /** The engine's recommended move id on [view], or null when it has none. */
    fun pick(view: Scenario, pipeline: AnalysisPipeline = AnalysisPipeline()): String? =
        analyze(view, pipeline).report?.pathways?.firstOrNull { it.label == RECOMMENDED }?.actionId

    /** ID3.1: the hypothetical on a copy, through the main turn's own chain. */
    suspend fun whatIf(input: RouteInput, pipeline: AnalysisPipeline = AnalysisPipeline()): IntentResult = WhatIfHandler.run(input, pipeline)

    /** ID3.2: two moves side by side. */
    suspend fun compare(input: RouteInput, pipeline: AnalysisPipeline = AnalysisPipeline()): IntentResult = Compare.run(input, pipeline)

    /** ID3.3: best, upside and insurance on the turn's view. */
    fun topOptions(input: RouteInput): IntentResult =
        TopOptions.of(input.view, input.decide(input.view), input.dominated).takeIf { it.isNotEmpty() }?.let { IntentResult.TopOptionsList(it) } ?: IntentResult.None

    /** The handlers the router uses in the app. An intent answers [IntentResult.None] until its step. */
    val handlers: IntentHandlers = object : IntentHandlers {
        override suspend fun whatIf(input: RouteInput) = EngineTools.whatIf(input)
        override suspend fun compare(input: RouteInput) = EngineTools.compare(input)
        override suspend fun topOptions(input: RouteInput) = EngineTools.topOptions(input)
    }
}
