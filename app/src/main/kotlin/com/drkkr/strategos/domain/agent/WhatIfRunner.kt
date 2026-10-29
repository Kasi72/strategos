package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.agent.ScenarioDigest.round2
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.abs

data class WhatIfOutcome(val label: String, val comparison: JsonObject, val changed: Boolean, val variant: Scenario)

/** Runs hypothetical ops on a copy of the scenario and compares the result (blueprint §8.6). Never changes [base]. */
object WhatIfRunner {
    private const val MAX_DELTAS = 5
    private const val CACHE_SIZE = 4
    private const val RECOMMENDED = "Recommended"

    // BLUEPRINT-QUESTION: §8.6 keys the baseline cache by id + version. Unsaved edits don't bump version, so the key also
    // includes the scenario's hashCode to avoid comparing against a stale baseline.
    private val baselines = object : LinkedHashMap<String, PipelineResult>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PipelineResult>?) = size > CACHE_SIZE
    }

    suspend fun run(base: Scenario, ops: List<ChangeOp>, pipeline: AnalysisPipeline, label: String): WhatIfOutcome {
        val validated = ChangeSetValidator.validate(base, ops)
        val decisions = validated.accepted.map { OpDecision(it.index, Decision.ACCEPT) }
        val applied = ChangeSetApplier.apply(base, ops, decisions, validated.refIds, emptyList(), "What-if: $label")
        val rejected = validated.rejected.map { it.index to it.reason } +
            applied.skipped.filter { (i, _) -> validated.accepted.any { it.index == i } }
        val variant = applied.scenario

        val (before, after) = withContext(Dispatchers.Default) {
            baseline(base, pipeline) to pipeline.run(variant)
        }
        return compare(label, before, after, rejected, variant)
    }

    private fun baseline(base: Scenario, pipeline: AnalysisPipeline): PipelineResult {
        val key = "${base.id}:${base.version}:${base.hashCode()}"
        synchronized(baselines) { baselines[key]?.let { return it } }
        val result = pipeline.run(base)
        synchronized(baselines) { baselines[key] = result }
        return result
    }

    private fun compare(label: String, before: PipelineResult, after: PipelineResult, rejected: List<Pair<Int, String>>, variant: Scenario): WhatIfOutcome {
        val recBefore = before.report?.pathways?.firstOrNull { it.label == RECOMMENDED }
        val recAfter = after.report?.pathways?.firstOrNull { it.label == RECOMMENDED }
        val statusBefore = before.report?.executiveStatus?.name
        val statusAfter = after.report?.executiveStatus?.name
        val changed = recBefore?.actionId != recAfter?.actionId || statusBefore != statusAfter

        val strengthBefore = strengths(before)
        val strengthAfter = strengths(after)
        val labels = labels(before) + labels(after)
        val deltas = (strengthBefore.keys + strengthAfter.keys)
            .map { id -> Triple(id, strengthBefore[id], strengthAfter[id]) }
            .sortedByDescending { (_, b, a) -> abs((a ?: 0.0) - (b ?: 0.0)) }
            .take(MAX_DELTAS)
        val excludedBefore = excluded(before)
        val excludedAfter = excluded(after)
        val actionsAfter = strengthAfter.keys

        val comparison = buildJsonObject {
            put("label", label)
            put("recommended_before", recBefore?.action)
            put("recommended_after", recAfter?.action)
            put("changed", changed)
            put("status_before", statusBefore)
            put("status_after", statusAfter)
            putJsonArray("pathway_deltas") {
                deltas.forEach { (id, b, a) ->
                    addJsonObject {
                        put("action", labels[id] ?: id)
                        put("before", b?.let(::round2) ?: JsonNull)
                        put("after", a?.let(::round2) ?: JsonNull)
                    }
                }
            }
            putJsonArray("newly_excluded") { (excludedAfter - excludedBefore).forEach { add(labels[it] ?: it) } }
            putJsonArray("newly_feasible") {
                (excludedBefore - excludedAfter).filter { it in actionsAfter }.forEach { add(labels[it] ?: it) }
            }
            putJsonArray("rejected_ops") {
                rejected.sortedBy { it.first }.forEach { (i, reason) -> addJsonObject { put("index", i); put("reason", reason) } }
            }
        }
        return WhatIfOutcome(label, comparison, changed, variant)
    }

    /** Best strength per action id across all pathways. */
    private fun strengths(pr: PipelineResult): Map<String, Double> =
        pr.report?.pathways.orEmpty().mapNotNull { p -> p.actionId?.let { it to p.strength } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.max() }

    private fun labels(pr: PipelineResult): Map<String, String> =
        pr.report?.pathways.orEmpty().mapNotNull { p -> p.actionId?.let { it to p.action } }.toMap()

    private fun excluded(pr: PipelineResult): Set<String> =
        pr.report?.pathways.orEmpty().filter { it.excluded }.mapNotNull { it.actionId }.toSet()
}
