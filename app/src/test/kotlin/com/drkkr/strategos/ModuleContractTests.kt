package com.drkkr.strategos

import com.drkkr.strategos.domain.framework.FrameworkModule
import com.drkkr.strategos.domain.framework.FrameworkRegistry
import com.drkkr.strategos.domain.framework.ModuleRunStatus
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.framework.behavioral.*
import com.drkkr.strategos.framework.dominant.DominantStrategyModule
import com.drkkr.strategos.framework.eu.ExpectedUtilityModule
import com.drkkr.strategos.framework.minimax.MinimaxModule
import com.drkkr.strategos.framework.nash.MixedNash2x2Module
import com.drkkr.strategos.framework.nash.PureNashModule
import com.drkkr.strategos.framework.pareto.ParetoModule
import com.drkkr.strategos.framework.sensitivity.SensitivityModule
import com.drkkr.strategos.framework.strategic.*
import com.drkkr.strategos.framework.tree.BackwardInductionModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Contract-conformance suite covering EVERY registered framework module at once.
 *
 * Rather than guess exact numeric outputs for 30+ modules, this asserts the
 * universal invariants every module must honour: well-formed metadata, total
 * canAnalyze(), and — driven through the registry — that no scenario (however
 * malformed) can crash analysis. This is the safety net that lets the
 * MatrixSupport "!!" sites be converted to graceful skips with confidence.
 */
class ModuleContractTests {

    // Mirrors the registration list in StrategosViewModel.
    private fun allModules(): List<FrameworkModule> = listOf(
        DominantStrategyModule(), PureNashModule(), MixedNash2x2Module(),
        ExpectedUtilityModule(), MinimaxModule(), BackwardInductionModule(),
        ParetoModule(), SensitivityModule(), PrisonersDilemmaModule(),
        StagHuntModule(), VirtualBargainingModule(), UltimatumModule(),
        TrustGameModule(), PublicGoodsModule(), LevelKModule(),
        IteratedDominanceModule(), AuctionModule(), ChickenGameModule(),
        PrincipalAgentModule(), CommonPoolResourceModule(), RedTeamModule(),
        RobustDecisionFrontierModule(), ValueOfInformationModule(),
        BehavioralResponseModule(), CoalitionAnalysisModule(), SignallingModule(),
        StackelbergModule(), ScenarioEnsembleModule(), BayesianUpdatingModule(),
        DynamicSimulationModule(), StakeholderTrustDynamicsModule(),
        RelationalRepairModule()
    )

    private fun registry(): FrameworkRegistry {
        val r = FrameworkRegistry()
        allModules().forEach { r.register(it) }
        return r
    }

    // ── Scenario builders (adversarial + well-formed) ─────────────────────────
    private fun payoff(v: Double) = MultiPayoff(mapOf(PayoffDimension.LONG_TERM_VALUE to v))

    private fun empty() = Scenario(id = UUID.randomUUID().toString(), title = "Empty")

    private fun oneStakeholder() = Scenario(
        id = UUID.randomUUID().toString(), title = "Solo",
        stakeholders = listOf(Stakeholder(id = "p1", name = "P1"))
    )

    private fun twoNoActions() = Scenario(
        id = UUID.randomUUID().toString(), title = "Two, no actions",
        stakeholders = listOf(Stakeholder(id = "p1", name = "P1"), Stakeholder(id = "p2", name = "P2"))
    )

    private fun prisoner(): Scenario {
        val p1 = Stakeholder(id = "p1", name = "P1"); val p2 = Stakeholder(id = "p2", name = "P2")
        val c1 = Action(id = "c1", stakeholderId = "p1", label = "Cooperate")
        val d1 = Action(id = "d1", stakeholderId = "p1", label = "Defect")
        val c2 = Action(id = "c2", stakeholderId = "p2", label = "Cooperate")
        val d2 = Action(id = "d2", stakeholderId = "p2", label = "Defect")
        val payoffs = listOf(
            PayoffEntry("p1", mapOf("p1" to "c1", "p2" to "c2"), payoff(3.0)),
            PayoffEntry("p1", mapOf("p1" to "c1", "p2" to "d2"), payoff(0.0)),
            PayoffEntry("p1", mapOf("p1" to "d1", "p2" to "c2"), payoff(5.0)),
            PayoffEntry("p1", mapOf("p1" to "d1", "p2" to "d2"), payoff(1.0)),
            PayoffEntry("p2", mapOf("p1" to "c1", "p2" to "c2"), payoff(3.0)),
            PayoffEntry("p2", mapOf("p1" to "c1", "p2" to "d2"), payoff(5.0)),
            PayoffEntry("p2", mapOf("p1" to "d1", "p2" to "c2"), payoff(0.0)),
            PayoffEntry("p2", mapOf("p1" to "d1", "p2" to "d2"), payoff(1.0))
        )
        return Scenario(
            id = UUID.randomUUID().toString(), title = "Prisoner's Dilemma",
            description = "Repeated cooperation and defection; rebuild trust.",
            repeated = true, timeHorizon = 8,
            stakeholders = listOf(p1, p2), actions = listOf(c1, d1, c2, d2), payoffs = payoffs
        )
    }

    private fun threeStakeholders(): Scenario {
        val s = (1..3).map { Stakeholder(id = "p$it", name = "P$it", canExit = true) }
        val a = s.map { Action(id = "a_${it.id}", stakeholderId = it.id, label = "Act ${it.name}") }
        return Scenario(
            id = UUID.randomUUID().toString(), title = "Coalition setting",
            description = "Multi-party coalition and bargaining over resources.",
            repeated = true, timeHorizon = 10, stakeholders = s, actions = a
        )
    }

    private fun allScenarios() = listOf(
        empty(), oneStakeholder(), twoNoActions(), prisoner(), threeStakeholders()
    )

    // ── Metadata invariants ───────────────────────────────────────────────────
    @Test
    fun `every module has non-blank id name version category`() {
        allModules().forEach { m ->
            assertTrue("id blank", m.id.isNotBlank())
            assertTrue("name blank for ${m.id}", m.name.isNotBlank())
            assertTrue("version blank for ${m.id}", m.version.isNotBlank())
            assertTrue("category blank for ${m.id}", m.category.isNotBlank())
        }
    }

    @Test
    fun `module ids are unique`() {
        val ids = allModules().map { it.id }
        assertEquals("duplicate module id present", ids.size, ids.toSet().size)
    }

    // ── canAnalyze is total and bounded ───────────────────────────────────────
    @Test
    fun `canAnalyze never throws and returns a score within zero to one`() {
        for (module in allModules()) {
            for (scenario in allScenarios()) {
                val app = module.canAnalyze(scenario)
                assertNotNull("null reasons from ${module.id}", app.reasons)
                assertTrue(
                    "score ${app.score} out of range for ${module.id}",
                    app.score in 0.0..1.0
                )
            }
        }
    }

    // ── Registry resilience: nothing can crash analysis ───────────────────────
    @Test
    fun `runAll never throws and classifies every result for any scenario`() {
        val reg = registry()
        for (scenario in allScenarios()) {
            val runs = reg.runAll(scenario) // threshold-based overload
            for (run in runs) {
                // status is always one of the known enum values
                assertTrue(run.status in ModuleRunStatus.values())
                when (run.status) {
                    ModuleRunStatus.SUCCESS -> {
                        val r = run.result
                        assertNotNull("SUCCESS with null result (${run.moduleId})", r)
                        r!!
                        assertEquals(run.moduleId, r.moduleId)
                        assertTrue("confidence out of range (${run.moduleId})", r.confidence in 0.0..1.0)
                        assertNotNull(r.findings); assertNotNull(r.recommendedActions)
                        assertNotNull(r.assumptions); assertNotNull(r.calculations)
                        assertNotNull(r.visualizations); assertNotNull(r.explanation)
                    }
                    ModuleRunStatus.FAILED_RECOVERABLY -> {
                        assertTrue("FAILED must carry null result", run.result == null)
                        assertNotNull("FAILED must carry a user message", run.userMessage)
                    }
                    else -> { /* SKIPPED_* — no result required */ }
                }
            }
        }
    }

    @Test
    fun `failed module messages never leak exception internals`() {
        val reg = registry()
        for (scenario in allScenarios()) {
            reg.runAll(scenario)
                .filter { it.status == ModuleRunStatus.FAILED_RECOVERABLY }
                .forEach { run ->
                    val msg = run.userMessage ?: ""
                    assertFalse("leaks stack trace: $msg", msg.contains("\tat "))
                    assertFalse("leaks Exception class: $msg", msg.contains("Exception"))
                }
        }
    }

    // ── Tier-2 guard: matrix modules SKIP (not FAIL) on insufficient input ────
    // Force-selected via the id overload so the threshold filter can't exclude
    // them first — this exercises the analyze() path and its matrix guard.
    @Test
    fun `force-run matrix modules never FAIL on malformed input, they skip`() {
        val reg = registry()
        val matrixModuleIds = setOf(
            PureNashModule().id, DominantStrategyModule().id,
            ExpectedUtilityModule().id, MinimaxModule().id,
            StackelbergModule().id, IteratedDominanceModule().id
        )
        for (scenario in listOf(empty(), oneStakeholder(), twoNoActions())) {
            val runs = reg.runAll(scenario, matrixModuleIds)
            assertEquals("all force-selected modules should run", matrixModuleIds.size, runs.size)
            runs.forEach { run ->
                assertTrue(
                    "matrix module ${run.moduleId} must SKIP on malformed input, got ${run.status}",
                    run.status == ModuleRunStatus.SKIPPED_INSUFFICIENT_INPUT ||
                        run.status == ModuleRunStatus.SKIPPED_NOT_APPLICABLE
                )
                assertTrue("skipped run must carry no result", run.result == null)
            }
        }
    }

    // ── Direct analyze() on a well-formed 2x2 for applicable modules ──────────
    @Test
    fun `applicable modules produce well-formed results on a 2x2 game`() {
        val scenario = prisoner()
        val reg = registry()
        val runs = reg.runAll(scenario)
        // At least the core 2x2 modules must find this applicable and succeed.
        val successes = runs.filter { it.status == ModuleRunStatus.SUCCESS }
        assertTrue("no module succeeded on a standard PD", successes.isNotEmpty())
        successes.forEach { run ->
            val r = run.result!!
            assertTrue("empty explanation from ${r.moduleId}", r.explanation.isNotBlank())
        }
    }
}
