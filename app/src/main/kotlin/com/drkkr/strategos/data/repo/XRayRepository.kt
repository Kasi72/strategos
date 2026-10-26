package com.drkkr.strategos.data.repo

import com.drkkr.strategos.domain.agent.PriorId
import com.drkkr.strategos.domain.agent.XRayPrior
import com.drkkr.strategos.domain.agent.XRayPriors

/**
 * The X-ray's what-if priors and their persistence. Sliders are view-model-private and never saved
 * while dragging; only "Keep as my estimate" writes, through [ScenarioRepository] (a user action, not
 * a ChangeSet), marking the value the user's own with a Reset that clears it (open question #2).
 * The live re-rank itself runs the existing analysis pipeline on `XRayPriors.withPriors(scenario, …)`.
 */
class XRayRepository(private val scenarios: ScenarioRepository) {

    fun priors(scenarioId: String): List<XRayPrior> =
        scenarios.find(scenarioId)?.let { XRayPriors.of(it) } ?: emptyList()

    /** "Keep as my estimate": persist the dragged priors as the user's own. */
    suspend fun keepAsMine(scenarioId: String, overrides: Map<PriorId, Double>): Boolean {
        val s = scenarios.find(scenarioId) ?: return false
        return scenarios.save(XRayPriors.withPriors(s, overrides, markUser = true)) is SaveResult.Success
    }

    /** "Reset": clear the given priors back to the engine's own defaults. */
    suspend fun reset(scenarioId: String, ids: Set<PriorId>): Boolean {
        val s = scenarios.find(scenarioId) ?: return false
        return scenarios.save(XRayPriors.reset(s, ids)) is SaveResult.Success
    }
}
