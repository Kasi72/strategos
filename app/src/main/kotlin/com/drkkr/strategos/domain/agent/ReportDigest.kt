package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.agent.ScenarioDigest.round2
import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Digest of an analysis for `run_analysis` and the RecommendationCard (blueprint §8.12). Requires `pr.report`. */
object ReportDigest {
    const val MAX_PATHWAYS = 6
    const val MAX_FRAMEWORKS = 20
    const val MAX_VOI = 3
    const val MAX_RISKS = 3
    const val MAX_LIST = 5

    fun build(pr: PipelineResult, s: Scenario): JsonObject {
        val r = requireNotNull(pr.report) { "ReportDigest needs a report" }
        return buildJsonObject {
            put("analysis_scenario_version", s.version)
            put("executive_status", r.executiveStatus.name)
            put("executive_status_label", r.executiveStatus.displayLabel())
            putJsonArray("status_reasons") {
                val reason = r.executiveStatusReason
                (reason?.blockingReasons.orEmpty() + reason?.requiredActions.orEmpty()).take(MAX_LIST).forEach { add(it) }
            }
            putJsonObject("confidence") {
                val c = r.confidenceProfile
                if (c != null) {
                    put("overall", round2(c.overall())); put("label", c.label())
                    put("evidence", round2(c.evidenceQuality)); put("completeness", round2(c.modelCompleteness))
                    put("robustness", round2(c.analyticalRobustness)); put("readiness", round2(c.implementationReadiness))
                } else {
                    put("overall", round2(r.overallConfidence))
                }
            }
            putJsonArray("pathways") {
                r.pathways.take(MAX_PATHWAYS).forEach { p ->
                    addJsonObject {
                        put("label", p.label); put("action_id", p.actionId); put("action", p.action); put("owner", p.ownerName)
                        put("strength", round2(p.strength)); put("fragile", p.fragile); put("excluded", p.excluded)
                        putJsonArray("constraint_violations") { p.constraintViolations.forEach { add(it) } }
                        put("basis", p.calculationBasis); put("rationale", p.rationale.take(300))
                    }
                }
            }
            putJsonArray("frameworks") {
                pr.results.take(MAX_FRAMEWORKS).forEach { f ->
                    addJsonObject {
                        put("id", f.moduleId); put("name", f.moduleName); put("category", f.frameworkCategory.name)
                        put("status", f.calculationStatus.name); put("headline", f.findings.firstOrNull()?.title.orEmpty().take(120))
                    }
                }
            }
            putJsonArray("disagreements") { r.frameworkDisagreements.take(MAX_LIST).forEach { add(it) } }
            putJsonArray("missing_info") { r.missingInfo.take(MAX_LIST).forEach { add(it.description) } }
            putJsonArray("voi") {
                r.voiItems.take(MAX_VOI).forEach { v ->
                    addJsonObject { put("fact", v.missingFact); put("why", v.whyItMatters); put("pct_scenarios_changed", v.percentageScenariosChanged) }
                }
            }
            putJsonArray("risks") {
                r.riskRegister.take(MAX_RISKS).forEach { k ->
                    addJsonObject {
                        put("failure_mode", k.failureMode); put("likelihood", k.likelihood)
                        put("impact", k.impact); put("early_signal", k.earlySignal)
                    }
                }
            }
            putJsonObject("provenance") {
                put("payoffs_total", pr.provenance.payoffsTotal)
                put("payoffs_ai_inferred", pr.provenance.payoffsAi)
                put("ai_share", round2(pr.provenance.aiPayoffShare))
                put("gate", pr.gate.name)
            }
            put("matrix_coverage", r.matrixCoverage)
        }
    }
}
