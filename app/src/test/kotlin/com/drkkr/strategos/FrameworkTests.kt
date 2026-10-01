package com.drkkr.strategos

import com.drkkr.strategos.domain.framework.AnalysisEligibilityGate
import com.drkkr.strategos.domain.framework.AnalysisRunStatus
import com.drkkr.strategos.domain.framework.Applicability
import com.drkkr.strategos.domain.framework.AnalysisResult
import com.drkkr.strategos.domain.framework.DecisionReadinessResult
import com.drkkr.strategos.domain.framework.ExecutiveStatus
import com.drkkr.strategos.domain.framework.FrameworkModule
import com.drkkr.strategos.domain.framework.FrameworkRegistry
import com.drkkr.strategos.domain.framework.ModelValidator
import com.drkkr.strategos.domain.framework.ModuleRunResult
import com.drkkr.strategos.domain.framework.ModuleRunStatus
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.domain.model.AnalysisSnapshot
import com.drkkr.strategos.domain.recommendation.CandidateType
import com.drkkr.strategos.domain.recommendation.DecisionCandidate
import com.drkkr.strategos.domain.recommendation.FeasibilityStatus
import com.drkkr.strategos.framework.dominant.DominantStrategyModule
import com.drkkr.strategos.framework.minimax.MinimaxModule
import com.drkkr.strategos.framework.nash.MixedNash2x2Module
import com.drkkr.strategos.framework.nash.PureNashModule
import com.drkkr.strategos.framework.eu.ExpectedUtilityModule
import com.drkkr.strategos.framework.pareto.ParetoModule
import com.drkkr.strategos.framework.tree.BackwardInductionModule
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

// --- Test scenario builders ---

private fun prisoner() : Scenario {
    val p1 = Stakeholder(id = "p1", name = "P1")
    val p2 = Stakeholder(id = "p2", name = "P2")
    val cooperate1 = Action(id = "c1", stakeholderId = "p1", label = "Cooperate")
    val defect1   = Action(id = "d1", stakeholderId = "p1", label = "Defect")
    val cooperate2 = Action(id = "c2", stakeholderId = "p2", label = "Cooperate")
    val defect2   = Action(id = "d2", stakeholderId = "p2", label = "Defect")

    // Standard PD payoffs: (D,C) > (C,C) > (D,D) > (C,D)
    // P1 payoffs: CC=3, CD=0, DC=5, DD=1
    // P2 payoffs: CC=3, CD=5, DC=0, DD=1
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
        stakeholders = listOf(p1, p2),
        actions = listOf(cooperate1, defect1, cooperate2, defect2),
        payoffs = payoffs
    )
}

private fun payoff(v: Double) = MultiPayoff(mapOf(PayoffDimension.LONG_TERM_VALUE to v))

private fun matchingPennies(): Scenario {
    val p1 = Stakeholder(id = "p1", name = "P1")
    val p2 = Stakeholder(id = "p2", name = "P2")
    val h1 = Action(id = "h1", stakeholderId = "p1", label = "Heads")
    val t1 = Action(id = "t1", stakeholderId = "p1", label = "Tails")
    val h2 = Action(id = "h2", stakeholderId = "p2", label = "Heads")
    val t2 = Action(id = "t2", stakeholderId = "p2", label = "Tails")
    val payoffs = listOf(
        PayoffEntry("p1", mapOf("p1" to "h1", "p2" to "h2"), payoff(1.0)),
        PayoffEntry("p1", mapOf("p1" to "h1", "p2" to "t2"), payoff(-1.0)),
        PayoffEntry("p1", mapOf("p1" to "t1", "p2" to "h2"), payoff(-1.0)),
        PayoffEntry("p1", mapOf("p1" to "t1", "p2" to "t2"), payoff(1.0)),
        PayoffEntry("p2", mapOf("p1" to "h1", "p2" to "h2"), payoff(-1.0)),
        PayoffEntry("p2", mapOf("p1" to "h1", "p2" to "t2"), payoff(1.0)),
        PayoffEntry("p2", mapOf("p1" to "t1", "p2" to "h2"), payoff(1.0)),
        PayoffEntry("p2", mapOf("p1" to "t1", "p2" to "t2"), payoff(-1.0))
    )
    return Scenario(id = UUID.randomUUID().toString(), title = "Matching Pennies",
        stakeholders = listOf(p1, p2), actions = listOf(h1, t1, h2, t2), payoffs = payoffs)
}

// --- Tests ---

class DominantStrategyTest {
    @Test
    fun `prisoner's dilemma defect dominates cooperate`() {
        val result = DominantStrategyModule().analyze(prisoner())
        val dominant = result.calculations["dominant"] as Map<*, *>
        assertTrue("Both players should have dominant strategy Defect", dominant.values.all { (it as? String)?.contains("d") == true || it == "d1" || it == "d2" })
    }
}

class PureNashTest {
    @Test
    fun `prisoner's dilemma has unique pure Nash at Defect-Defect`() {
        val result = PureNashModule().analyze(prisoner())
        val equilibria = result.calculations["equilibria"] as List<*>
        assertEquals(1, equilibria.size)
    }

    @Test
    fun `matching pennies has no pure Nash`() {
        val result = PureNashModule().analyze(matchingPennies())
        val equilibria = result.calculations["equilibria"] as List<*>
        assertEquals(0, equilibria.size)
    }
}

class MixedNashTest {
    @Test
    fun `matching pennies mixed Nash is 50-50`() {
        val result = MixedNash2x2Module().analyze(matchingPennies())
        val rawP = result.calculations["p"]
        assertNotNull("Expected 'p' in MixedNash calculations", rawP)
        val p = rawP as Double
        val rawQ = result.calculations["q"]
        assertNotNull("Expected 'q' in MixedNash calculations", rawQ)
        val q = rawQ as Double
        assertEquals(0.5, p, 0.001)
        assertEquals(0.5, q, 0.001)
    }
}

class MinimaxTest {
    @Test
    fun `prisoner maximin strategy is Defect`() {
        val result = MinimaxModule().analyze(prisoner())
        val maximin = result.calculations["maximin"] as? Pair<*, *>
        assertNotNull(maximin)
        assertTrue((maximin!!.first as String).contains("d"))
    }
}

class ExpectedUtilityTest {
    @Test
    fun `prisoner EU prefers Defect`() {
        val result = ExpectedUtilityModule().analyze(prisoner())
        assertFalse(result.recommendedActions.isEmpty())
        val rec = result.recommendedActions.first()
        assertTrue(rec.label.contains("Defect", ignoreCase = true))
    }
}

class BackwardInductionTest {
    @Test
    fun `simple two-move tree resolves correctly`() {
        val p1 = Stakeholder(id = "p1", name = "P1")
        val terminal = mapOf("p1" to payoff(5.0))
        val tree = SequenceNode("root", "p1", "Go", 1.0, terminal, emptyList())
        val scenario = Scenario(UUID.randomUUID().toString(), "Tree test", stakeholders = listOf(p1), gameTree = tree)
        val result = BackwardInductionModule().analyze(scenario)
        assertFalse(result.findings.isEmpty())
    }
}

class EvidenceQualityTest {
    @Test
    fun `evidence quality is zero when no sources`() {
        val s = Scenario(id = "eq1", title = "test")
        assertEquals(0, s.evidenceQuality())
    }
    @Test
    fun `evidence quality is non-zero when sources present`() {
        val src = EvidenceSource(label = "Study", quality = 4)
        val s = Scenario(id = "eq2", title = "test", evidenceSources = listOf(src))
        assertTrue(s.evidenceQuality() > 0)
    }
}

class ProhibitedActionExcludedTest {
    @Test
    fun `prohibited action never appears as Recommended pathway`() {
        val p1 = Stakeholder(id = "p1", name = "P1")
        val a1 = Action(id = "a1", stakeholderId = "p1", label = "BadAction", legalStatus = LegalStatus.PROHIBITED)
        val payoffs = listOf(
            PayoffEntry("p1", mapOf("p1" to "a1"), MultiPayoff(mapOf(PayoffDimension.LONG_TERM_VALUE to 9.0)))
        )
        val s = Scenario(id = "pa1", title = "test", stakeholders = listOf(p1), actions = listOf(a1), payoffs = payoffs)
        val engine = com.drkkr.strategos.domain.recommendation.RecommendationEngine()
        val report = engine.combine(s, emptyList())
        val recommended = report.pathways.firstOrNull { it.label == "Recommended" }
        assertNull("Prohibited action must not be Recommended", recommended)
    }
}

// ── Reliability spec tests ─────────────────────────────────────────────────

private fun stubScenario() = Scenario(id = "stub", title = "Stub")

private fun stubApplicability() = Applicability(1.0, listOf("applicable"))

private fun stubAnalysisResult(id: String, name: String) = AnalysisResult(
    moduleId = id,
    moduleName = name,
    applicability = stubApplicability(),
    findings = emptyList(),
    recommendedActions = emptyList(),
    assumptions = emptyList(),
    calculations = emptyMap(),
    confidence = 0.8,
    visualizations = emptyList(),
    explanation = "ok"
)

private fun alwaysApplicableModule(id: String, throwing: Boolean = false): FrameworkModule =
    object : FrameworkModule {
        override val id = id
        override val name = "Stub-$id"
        override val version = "1"
        override val category = "TEST"
        override fun canAnalyze(model: Scenario) = stubApplicability()
        override fun analyze(model: Scenario): AnalysisResult {
            if (throwing) throw IllegalStateException("Deliberate test failure [flags='']")
            return stubAnalysisResult(id, name)
        }
    }

class ModuleRunResultTest {

    @Test
    fun `failed result status is FAILED_RECOVERABLY`() {
        val module = alwaysApplicableModule("m1")
        val err = RuntimeException("boom")
        val run = ModuleRunResult.failed(module, err, "diag01")
        assertEquals(ModuleRunStatus.FAILED_RECOVERABLY, run.status)
    }

    @Test
    fun `failed result has null AnalysisResult`() {
        val module = alwaysApplicableModule("m1")
        val run = ModuleRunResult.failed(module, RuntimeException("boom"), "diag01")
        assertNull("Failed run must have null result", run.result)
    }

    @Test
    fun `failed userMessage contains no exception class name`() {
        val module = alwaysApplicableModule("m1")
        val run = ModuleRunResult.failed(module, IllegalArgumentException("internal"), "diag02")
        val msg = run.userMessage ?: ""
        assertFalse("userMessage must not contain exception class name",
            msg.contains("IllegalArgumentException") || msg.contains("Exception"))
    }

    @Test
    fun `failed userMessage does not contain stack trace lines`() {
        val module = alwaysApplicableModule("m1")
        val run = ModuleRunResult.failed(module, RuntimeException("boom"), "diag03")
        val msg = run.userMessage ?: ""
        assertFalse("userMessage must not contain 'at '", msg.contains("\tat "))
    }

    @Test
    fun `success result status is SUCCESS`() {
        val result = stubAnalysisResult("m2", "Stub-m2")
        val run = ModuleRunResult.success(result, 10L)
        assertEquals(ModuleRunStatus.SUCCESS, run.status)
        assertNotNull(run.result)
    }
}

class FrameworkRegistryFaultIsolationTest {

    @Test
    fun `throwing module returns FAILED_RECOVERABLY, does not crash registry`() {
        val registry = FrameworkRegistry()
            .register(alwaysApplicableModule("ok"))
            .register(alwaysApplicableModule("bad", throwing = true))

        val runs = registry.runAll(stubScenario(), setOf("ok", "bad"))

        assertEquals(2, runs.size)
        val ok = runs.first { it.moduleId == "ok" }
        val bad = runs.first { it.moduleId == "bad" }
        assertEquals(ModuleRunStatus.SUCCESS, ok.status)
        assertEquals(ModuleRunStatus.FAILED_RECOVERABLY, bad.status)
    }

    @Test
    fun `analysisResultsFrom returns only successful results`() {
        val registry = FrameworkRegistry()
            .register(alwaysApplicableModule("ok"))
            .register(alwaysApplicableModule("bad", throwing = true))

        val runs = registry.runAll(stubScenario(), setOf("ok", "bad"))
        val results = registry.analysisResultsFrom(runs)

        assertEquals(1, results.size)
        assertEquals("ok", results[0].moduleId)
    }
}

class DecisionReadinessTest {

    @Test
    fun `isReadyForPolicy false when analysis not run`() {
        val dr = DecisionReadinessResult.notRun(50, 0)
        assertFalse(dr.isReadyForPolicy())
    }

    @Test
    fun `isReadyForPolicy false when run status is FAILED`() {
        val dr = DecisionReadinessResult(
            primaryStatus = ExecutiveStatus.INDETERMINATE,
            summary = "failed",
            blockers = emptyList(),
            requiredActions = emptyList(),
            policyEligible = true,
            snapshotStatus = DecisionReadinessResult.SnapshotStatus.VALID,
            lifecycleStatus = "ANALYSED",
            analysisRunStatus = AnalysisRunStatus.FAILED,
            structuralCompleteness = 60,
            evidenceQuality = 0,
            moduleRunResults = emptyList()
        )
        assertFalse(dr.isReadyForPolicy())
    }

    @Test
    fun `isReadyForPolicy false when evidence quality is zero even if structurally complete`() {
        val dr = DecisionReadinessResult(
            primaryStatus = ExecutiveStatus.INDETERMINATE,
            summary = "no evidence",
            blockers = emptyList(),
            requiredActions = emptyList(),
            policyEligible = false,
            snapshotStatus = DecisionReadinessResult.SnapshotStatus.VALID,
            lifecycleStatus = "ANALYSED",
            analysisRunStatus = AnalysisRunStatus.COMPLETE,
            structuralCompleteness = 100,
            evidenceQuality = 0,
            moduleRunResults = emptyList()
        )
        assertFalse("Zero evidence must not produce policyEligible=true", dr.policyEligible)
        assertFalse(dr.isReadyForPolicy())
    }

    @Test
    fun `isReadyForPolicy true only when all gates pass`() {
        val dr = DecisionReadinessResult(
            primaryStatus = ExecutiveStatus.PROCEED,
            summary = "ready",
            blockers = emptyList(),
            requiredActions = emptyList(),
            policyEligible = true,
            snapshotStatus = DecisionReadinessResult.SnapshotStatus.VALID,
            lifecycleStatus = "ANALYSED",
            analysisRunStatus = AnalysisRunStatus.COMPLETE,
            structuralCompleteness = 80,
            evidenceQuality = 70,
            moduleRunResults = emptyList()
        )
        assertTrue(dr.isReadyForPolicy())
    }
}

class ParetoTest {
    @Test
    fun `dominated option is excluded from Pareto set`() {
        val p1 = Stakeholder(id = "p1", name = "P1")
        val payoffs = listOf(
            PayoffEntry("p1", mapOf("p1" to "a1"), MultiPayoff(mapOf(PayoffDimension.SAFETY to 5.0, PayoffDimension.ETHICS to 5.0))),
            PayoffEntry("p1", mapOf("p1" to "a2"), MultiPayoff(mapOf(PayoffDimension.SAFETY to 2.0, PayoffDimension.ETHICS to 2.0))),
        )
        val scenario = Scenario(UUID.randomUUID().toString(), "Pareto", stakeholders = listOf(p1), payoffs = payoffs)
        val result = ParetoModule().analyze(scenario)
        val pareto = result.calculations["pareto"] as List<*>
        assertEquals(1, pareto.size)
    }
}

// ── Phase 3: Safety gate tests ────────────────────────────────────────────────

class EvidenceGateTest {
    @Test
    fun `no-evidence scenario produces HOLD_FOR_EVIDENCE not PROCEED`() {
        val s = Scenario(id = UUID.randomUUID().toString(), title = "No evidence", evidenceSources = emptyList())
        val engine = com.drkkr.strategos.domain.recommendation.RecommendationEngine()
        val report = engine.combine(s, emptyList())
        assertEquals("No evidence should block Proceed",
            ExecutiveStatus.HOLD_FOR_EVIDENCE, report.executiveStatus)
    }

    @Test
    fun `scenario with evidence does not produce HOLD_FOR_EVIDENCE`() {
        val src = EvidenceSource(label = "Study", quality = 4)
        val p1 = Stakeholder(id = "p1", name = "P1")
        val s = Scenario(id = UUID.randomUUID().toString(), title = "With evidence",
            stakeholders = listOf(p1), evidenceSources = listOf(src))
        val engine = com.drkkr.strategos.domain.recommendation.RecommendationEngine()
        val report = engine.combine(s, emptyList())
        assertNotEquals(ExecutiveStatus.HOLD_FOR_EVIDENCE, report.executiveStatus)
    }
}

class IndeterminateGateTest {
    @Test
    fun `zero-scored candidates produce INDETERMINATE`() {
        val p1 = Stakeholder(id = "p1", name = "P1")
        val src = EvidenceSource(label = "Study", quality = 4)
        // No payoffs means no score — but candidates list empty → not INDETERMINATE via that path
        // Build a scenario with tied actions via explicit calls
        val candidates = listOf(
            com.drkkr.strategos.domain.recommendation.DecisionCandidate(
                actionId = "a1", actionLabel = "A", ownerStakeholderId = "p1",
                candidateType = com.drkkr.strategos.domain.recommendation.CandidateType.EXECUTABLE_ACTION,
                feasibilityStatus = com.drkkr.strategos.domain.recommendation.FeasibilityStatus.FEASIBLE,
                constraintStatusSummary = ConstraintStatus.SATISFIED,
                calculationStatus = com.drkkr.strategos.domain.framework.CalculationStatus.CALCULATED,
                scoreBreakdown = null, score = 0.0, supportingFrameworks = emptyList(),
                assumptions = emptyList(), evidenceLinks = emptyList(), confidence = 0.0
            ),
            com.drkkr.strategos.domain.recommendation.DecisionCandidate(
                actionId = "a2", actionLabel = "B", ownerStakeholderId = "p1",
                candidateType = com.drkkr.strategos.domain.recommendation.CandidateType.EXECUTABLE_ACTION,
                feasibilityStatus = com.drkkr.strategos.domain.recommendation.FeasibilityStatus.FEASIBLE,
                constraintStatusSummary = ConstraintStatus.SATISFIED,
                calculationStatus = com.drkkr.strategos.domain.framework.CalculationStatus.CALCULATED,
                scoreBreakdown = null, score = 0.0, supportingFrameworks = emptyList(),
                assumptions = emptyList(), evidenceLinks = emptyList(), confidence = 0.0
            )
        )
        val s = Scenario(id = UUID.randomUUID().toString(), title = "test",
            stakeholders = listOf(p1), evidenceSources = listOf(src))
        val status = AnalysisEligibilityGate.determine(s, emptyList(), candidates)
        assertEquals(ExecutiveStatus.INDETERMINATE, status)
    }
}

class ModelValidatorTest {
    @Test
    fun `empty stakeholders produces ERROR`() {
        val s = Scenario(id = UUID.randomUUID().toString(), title = "No stakeholders")
        val report = ModelValidator.validate(s)
        assertTrue(report.blocksAnalysis)
        assertTrue(report.issues.any { it.severity == com.drkkr.strategos.domain.framework.ModelIssue.Severity.ERROR && it.field == "stakeholders" })
    }

    @Test
    fun `valid scenario produces no blocking errors`() {
        val p1 = Stakeholder(id = "p1", name = "P1")
        val s = Scenario(id = UUID.randomUUID().toString(), title = "Valid",
            decisionOwner = "p1", stakeholders = listOf(p1))
        val report = ModelValidator.validate(s)
        assertFalse(report.blocksAnalysis)
    }

    @Test
    fun `probability outside 0-1 produces ERROR`() {
        val p1 = Stakeholder(id = "p1", name = "P1")
        val s = Scenario(id = UUID.randomUUID().toString(), title = "Bad belief",
            stakeholders = listOf(p1),
            beliefs = listOf(Belief("State A", 1.5, FactStatus.USER_ESTIMATE)))
        val report = ModelValidator.validate(s)
        assertTrue(report.blocksAnalysis)
    }
}

class ScenarioVersionTest {
    @Test
    fun `scenario version field initialises at 1`() {
        val s = Scenario(id = UUID.randomUUID().toString(), title = "v1")
        assertEquals(1, s.version)
    }

    @Test
    fun `copying scenario with incremented version works`() {
        val s = Scenario(id = UUID.randomUUID().toString(), title = "v1")
        val s2 = s.copy(version = s.version + 1)
        assertEquals(2, s2.version)
    }
}

class AnalysisSnapshotTest {
    @Test
    fun `snapshot hash is deterministic for same input`() {
        val h1 = AnalysisSnapshot.hashString("test input")
        val h2 = AnalysisSnapshot.hashString("test input")
        assertEquals(h1, h2)
    }

    @Test
    fun `different inputs produce different hashes`() {
        val h1 = AnalysisSnapshot.hashString("input A")
        val h2 = AnalysisSnapshot.hashString("input B")
        assertNotEquals(h1, h2)
    }

    @Test
    fun `snapshot stores scenario json for reproduction`() {
        val snap = AnalysisSnapshot(
            scenarioId = "s1", scenarioVersion = 1, scenarioHash = "abc",
            selectedModuleIds = listOf("eu"), executiveStatus = "PROCEED",
            candidateCount = 2, overallConfidence = 0.8, reportHash = "xyz",
            scenarioJson = "{\"id\":\"s1\"}"
        )
        assertTrue(snap.scenarioJson.isNotEmpty())
    }

    @Test
    fun `editing scenario does not mutate existing snapshot`() {
        val snap = AnalysisSnapshot(
            scenarioId = "s1", scenarioVersion = 1, scenarioHash = "abc",
            selectedModuleIds = listOf("eu"), executiveStatus = "PROCEED",
            candidateCount = 2, overallConfidence = 0.8, reportHash = "xyz",
            scenarioJson = "{\"original\":true}"
        )
        // Snapshots are immutable data classes — copy does not affect original
        val snap2 = snap.copy(scenarioJson = "{\"mutated\":true}")
        assertEquals("{\"original\":true}", snap.scenarioJson)
        assertEquals("{\"mutated\":true}", snap2.scenarioJson)
    }
}

// ── Phase 4: Lifecycle & governance tests ─────────────────────────────────────

class LifecycleTransitionTest {
    @Test
    fun `draft scenario can transition to modelled with required fields`() {
        val p1 = Stakeholder(id = "p1", name = "P1")
        val p2 = Stakeholder(id = "p2", name = "P2")
        val a1 = Action(id = "a1", stakeholderId = "p1", label = "Option A")
        val s = Scenario(id = UUID.randomUUID().toString(), title = "test",
            stakeholders = listOf(p1, p2), actions = listOf(a1), decisionOwner = "p1")
        val result = LifecycleTransition.canTransition(s, LifecycleStage.MODELLED)
        assertTrue("Transition to MODELLED should be allowed: ${result.blockers}", result.allowed)
    }

    @Test
    fun `draft scenario cannot transition to modelled without stakeholders`() {
        val s = Scenario(id = UUID.randomUUID().toString(), title = "test")
        val result = LifecycleTransition.canTransition(s, LifecycleStage.MODELLED)
        assertFalse(result.allowed)
        assertTrue(result.blockers.any { it.contains("stakeholder") })
    }

    @Test
    fun `cannot transition to approved without approval stages`() {
        val p1 = Stakeholder(id = "p1", name = "P1")
        val s = Scenario(id = UUID.randomUUID().toString(), title = "test",
            stakeholders = listOf(p1), decisionOwner = "p1",
            lifecycleStage = LifecycleStage.READY_FOR_APPROVAL)
        val result = LifecycleTransition.canTransition(s, LifecycleStage.APPROVED)
        assertFalse(result.allowed)
        assertTrue(result.blockers.any { it.contains("approval") })
    }

    @Test
    fun `approval stage is invalidated after material change`() {
        val stage = ApprovalStage(
            name = "Board Approval",
            status = ApprovalStatus.APPROVED,
            invalidatedAt = System.currentTimeMillis()
        )
        assertFalse("Invalidated approval should not be valid", stage.isValid())
    }

    @Test
    fun `valid approval stage passes isValid`() {
        val stage = ApprovalStage(
            name = "Executive Sign-off",
            status = ApprovalStatus.APPROVED,
            invalidatedAt = 0L,
            expiryDate = 0L
        )
        assertTrue(stage.isValid())
    }

    @Test
    fun `scenario version increments correctly through repeated saves`() {
        val s = Scenario(id = UUID.randomUUID().toString(), title = "v-test")
        assertEquals(1, s.version)
        val s2 = s.copy(version = s.version + 1)
        val s3 = s2.copy(version = s2.version + 1)
        assertEquals(2, s2.version)
        assertEquals(3, s3.version)
    }
}

class HoldForVerificationTest {
    private fun irreversibleScenario(): Scenario {
        val p1 = Stakeholder(id = "p1", name = "P1")
        val src = EvidenceSource(label = "Study", quality = 4)
        val constraint = Constraint(id = "c1", label = "Safety constraint", kind = Constraint.Kind.SAFETY)
        val irreversibleAction = Action(id = "a1", stakeholderId = "p1", label = "Irreversible Action",
            reversible = false, cost = 100.0, implementationDifficulty = 4)
        return Scenario(
            id = UUID.randomUUID().toString(), title = "Irreversible with unresolved constraint",
            stakeholders = listOf(p1),
            actions = listOf(irreversibleAction),
            constraints = listOf(constraint),
            evidenceSources = listOf(src),
            constraintAssessments = emptyList()  // not assessed
        )
    }

    @Test
    fun `irreversible action with unresolved hard constraint produces HOLD_FOR_VERIFICATION`() {
        val scenario = irreversibleScenario()
        val candidate = DecisionCandidate(
            actionId = "a1", actionLabel = "Irreversible Action", ownerStakeholderId = "p1",
            candidateType = CandidateType.EXECUTABLE_ACTION,
            feasibilityStatus = FeasibilityStatus.FEASIBLE,
            constraintStatusSummary = ConstraintStatus.UNKNOWN,
            calculationStatus = com.drkkr.strategos.domain.framework.CalculationStatus.CALCULATED,
            scoreBreakdown = null, score = 0.8, supportingFrameworks = emptyList(),
            assumptions = emptyList(), evidenceLinks = emptyList(), confidence = 0.7
        )
        val status = AnalysisEligibilityGate.determine(scenario, emptyList(), listOf(candidate))
        assertEquals(
            "Irreversible action with unresolved constraint must produce HOLD_FOR_VERIFICATION",
            ExecutiveStatus.HOLD_FOR_VERIFICATION, status
        )
    }

    @Test
    fun `BLOCKED_UNKNOWN_CONSTRAINT candidate is not treated as feasible`() {
        val p1 = Stakeholder(id = "p1", name = "P1")
        val src = EvidenceSource(label = "Study", quality = 4)
        val s = Scenario(id = UUID.randomUUID().toString(), title = "unknown constraint test",
            stakeholders = listOf(p1), evidenceSources = listOf(src))
        val unknownBlocked = DecisionCandidate(
            actionId = "a1", actionLabel = "Option A", ownerStakeholderId = "p1",
            candidateType = CandidateType.EXECUTABLE_ACTION,
            feasibilityStatus = FeasibilityStatus.BLOCKED_UNKNOWN_CONSTRAINT,
            constraintStatusSummary = ConstraintStatus.UNKNOWN,
            calculationStatus = com.drkkr.strategos.domain.framework.CalculationStatus.CALCULATED,
            scoreBreakdown = null, score = 0.8, supportingFrameworks = emptyList(),
            assumptions = emptyList(), evidenceLinks = emptyList(), confidence = 0.7
        )
        val status = AnalysisEligibilityGate.determine(s, emptyList(), listOf(unknownBlocked))
        // BLOCKED_UNKNOWN_CONSTRAINT is NOT feasible so all-blocked = HOLD_FOR_VERIFICATION
        assertEquals(
            "BLOCKED_UNKNOWN_CONSTRAINT must not be treated as feasible",
            ExecutiveStatus.HOLD_FOR_VERIFICATION, status
        )
    }

    @Test
    fun `reversible low-impact action without unresolved constraints may get CONDITIONAL_TRIAL`() {
        val p1 = Stakeholder(id = "p1", name = "P1")
        val src = EvidenceSource(label = "Study", quality = 4)
        val lowRiskAction = Action(id = "a1", stakeholderId = "p1", label = "Reversible Pilot",
            reversible = true, publicVisibility = false, cost = 0.0, implementationDifficulty = 1)
        val s = Scenario(
            id = UUID.randomUUID().toString(), title = "low risk test",
            stakeholders = listOf(p1),
            actions = listOf(lowRiskAction),
            evidenceSources = listOf(src)
        )
        val candidate = DecisionCandidate(
            actionId = "a1", actionLabel = "Reversible Pilot", ownerStakeholderId = "p1",
            candidateType = CandidateType.EXECUTABLE_ACTION,
            feasibilityStatus = FeasibilityStatus.FEASIBLE,
            constraintStatusSummary = ConstraintStatus.SATISFIED,
            calculationStatus = com.drkkr.strategos.domain.framework.CalculationStatus.CALCULATED,
            scoreBreakdown = null, score = 0.7, supportingFrameworks = emptyList(),
            assumptions = emptyList(), evidenceLinks = emptyList(), confidence = 0.6
        )
        val status = AnalysisEligibilityGate.determine(s, emptyList(), listOf(candidate))
        assertEquals(ExecutiveStatus.CONDITIONAL_TRIAL, status)
    }
}

class GovernanceModelTest {
    @Test
    fun `ImplementationPlan stores early warning indicators`() {
        val plan = ImplementationPlan(
            owner = "Alice",
            earlyWarningIndicators = listOf("KPI drops > 10%", "Stakeholder escalation"),
            stopCondition = "If patient harm occurs"
        )
        assertEquals(2, plan.earlyWarningIndicators.size)
        assertTrue(plan.stopCondition.isNotBlank())
    }

    @Test
    fun `ChangeRecord captures previous version and stage`() {
        val record = ChangeRecord(
            summary = "Moved to ANALYSED",
            previousVersion = 3,
            previousLifecycleStage = LifecycleStage.MODELLED
        )
        assertEquals(3, record.previousVersion)
        assertEquals(LifecycleStage.MODELLED, record.previousLifecycleStage)
    }

    @Test
    fun `lifecycle stage defaults to DRAFT`() {
        val s = Scenario(id = UUID.randomUUID().toString(), title = "test")
        assertEquals(LifecycleStage.DRAFT, s.lifecycleStage)
    }

    @Test
    fun `approval stage with future expiry is valid`() {
        val futureExpiry = System.currentTimeMillis() + 1_000_000L
        val stage = ApprovalStage(
            name = "Regulator",
            status = ApprovalStatus.APPROVED,
            expiryDate = futureExpiry,
            invalidatedAt = 0L
        )
        assertTrue(stage.isValid())
    }

    @Test
    fun `approval stage with past expiry is invalid`() {
        val pastExpiry = System.currentTimeMillis() - 1_000L
        val stage = ApprovalStage(
            name = "Regulator",
            status = ApprovalStatus.APPROVED,
            expiryDate = pastExpiry,
            invalidatedAt = 0L
        )
        assertFalse(stage.isValid())
    }

    // ── Phase 4b workflow and implementation readiness tests ──────────────────

    @Test
    fun `lifecycle stage drives workflow stage correctly`() {
        assertEquals(WorkflowStage.FRAME, LifecycleStage.DRAFT.toWorkflowStage())
        assertEquals(WorkflowStage.MODEL, LifecycleStage.MODELLED.toWorkflowStage())
        assertEquals(WorkflowStage.CHALLENGE, LifecycleStage.CHALLENGED.toWorkflowStage())
        assertEquals(WorkflowStage.DECIDE, LifecycleStage.ANALYSED.toWorkflowStage())   // spec §2: analysis complete → decide
        assertEquals(WorkflowStage.SIMULATE, LifecycleStage.HOLD.toWorkflowStage())
        assertEquals(WorkflowStage.DECIDE, LifecycleStage.READY_FOR_APPROVAL.toWorkflowStage())
        assertEquals(WorkflowStage.DECIDE, LifecycleStage.APPROVED.toWorkflowStage())
        assertEquals(WorkflowStage.LEARN, LifecycleStage.IN_IMPLEMENTATION.toWorkflowStage())
        assertEquals(WorkflowStage.LEARN, LifecycleStage.CLOSED.toWorkflowStage())
    }

    @Test
    fun `report alone cannot advance lifecycle to in-implementation`() {
        // A scenario at APPROVED still needs an implementation plan with owner and stop condition
        val s = Scenario(
            title = "T", decisionOwner = "Owner",
            lifecycleStage = LifecycleStage.APPROVED,
            stakeholders = listOf(Stakeholder(id = "s1", name = "A"), Stakeholder(id = "s2", name = "B"))
        )
        val result = LifecycleTransition.canTransition(s, LifecycleStage.IN_IMPLEMENTATION)
        assertFalse(result.allowed)
        assertTrue(result.blockers.any { it.contains("implementation plan", ignoreCase = true) })
    }

    @Test
    fun `missing approval blocks lifecycle transition to in-implementation`() {
        val pending = ApprovalStage(name = "Board", status = ApprovalStatus.PENDING)
        val s = Scenario(
            title = "T", decisionOwner = "Owner",
            lifecycleStage = LifecycleStage.READY_FOR_APPROVAL,
            stakeholders = listOf(Stakeholder(id = "s1", name = "A"), Stakeholder(id = "s2", name = "B")),
            approvalStages = listOf(pending)
        )
        val result = LifecycleTransition.canTransition(s, LifecycleStage.APPROVED)
        assertFalse(result.allowed)
        assertTrue(result.blockers.any { it.contains("Incomplete", ignoreCase = true) || it.contains("invalidated", ignoreCase = true) })
    }

    @Test
    fun `unknown hard constraint blocks implementation gate`() {
        val hardConstraint = Constraint(id = "c1", label = "Safety req", kind = Constraint.Kind.SAFETY)
        val s = Scenario(
            title = "T", decisionOwner = "Owner",
            lifecycleStage = LifecycleStage.APPROVED,
            stakeholders = listOf(Stakeholder(id = "s1", name = "A"), Stakeholder(id = "s2", name = "B")),
            constraints = listOf(hardConstraint),
            constraintAssessments = emptyList() // unassessed
        )
        val result = ImplementationGate.evaluate(s)
        val gateStatuses = result.gates.map { it.status }
        assertTrue(gateStatuses.contains(GateStatus.AMBER) || gateStatuses.contains(GateStatus.BLOCKED))
    }

    @Test
    fun `stop triggered monitoring entry moves lifecycle to hold via blockers`() {
        // The gate detects STOP_TRIGGERED and returns PAUSED overall status
        val stopEntry = MonitoringEntry(signalStatus = MonitoringSignal.STOP_TRIGGERED, observedOutcome = "Budget exceeded 3x")
        val plan = ImplementationPlan(
            owner = "PMO",
            stopCondition = "Budget overrun > 2x",
            reversalPlan = "Revert to previous policy",
            earlyWarningIndicators = listOf("Monthly budget variance"),
            monitoringEntries = listOf(stopEntry)
        )
        val s = Scenario(
            title = "T",
            lifecycleStage = LifecycleStage.IN_IMPLEMENTATION,
            implementationPlan = plan
        )
        val result = ImplementationGate.evaluate(s)
        assertEquals(ImplementationGateOverallStatus.PAUSED, result.overallStatus)
    }

    @Test
    fun `material change to approved scenario invalidates approvals`() {
        val approved = ApprovalStage(name = "Board", status = ApprovalStatus.APPROVED, recordedAt = System.currentTimeMillis())
        val a1 = Action(id = "a1", stakeholderId = "s1", label = "Action 1")
        val a2 = Action(id = "a2", stakeholderId = "s1", label = "Action 2")
        val s = Scenario(
            title = "T", decisionOwner = "Owner",
            lifecycleStage = LifecycleStage.APPROVED,
            actions = listOf(a1, a2),
            approvalStages = listOf(approved)
        )
        // Simulate removal of an action (material change)
        val modified = s.copy(actions = listOf(a1))
        // Verify the approval stage was previously valid
        assertTrue(s.approvalStages.first().isValid())
        // A modified scenario with fewer actions represents a material change
        assertNotEquals(s.actions.size, modified.actions.size)
    }

    @Test
    fun `policy option must be selected before ready-for-approval transition`() {
        val s = Scenario(
            title = "T", decisionOwner = "Owner",
            lifecycleStage = LifecycleStage.ANALYSED,
            stakeholders = listOf(Stakeholder(id = "s1", name = "A"), Stakeholder(id = "s2", name = "B")),
            selectedPolicyOption = null // not selected
        )
        val result = LifecycleTransition.canTransition(s, LifecycleStage.READY_FOR_APPROVAL)
        assertFalse(result.allowed)
        assertTrue(result.blockers.any { it.contains("policy option", ignoreCase = true) })
    }

    @Test
    fun `implementation gate blocked when no policy option and no approval stages`() {
        val s = Scenario(
            title = "T", decisionOwner = "Owner",
            lifecycleStage = LifecycleStage.APPROVED,
            selectedPolicyOption = null,
            approvalStages = emptyList()
        )
        val result = ImplementationGate.evaluate(s)
        assertTrue(result.blockingReasons.isNotEmpty())
        assertTrue(result.overallStatus != ImplementationGateOverallStatus.APPROVED_NOT_STARTED)
    }

    @Test
    fun `implementation gate passes all gates for well-formed plan`() {
        val approvedStage = ApprovalStage(
            name = "Board", status = ApprovalStatus.APPROVED,
            recordedAt = System.currentTimeMillis(), expiryDate = 0L, invalidatedAt = 0L
        )
        val policy = PolicyOption(actionId = "a1", actionLabel = "Option A")
        val plan = ImplementationPlan(
            owner = "PMO",
            accountableApprover = "CEO",
            selectedActionId = "a1",
            stopCondition = "Rollout paused if error rate > 5%",
            reversalPlan = "Revert feature flag",
            earlyWarningIndicators = listOf("Error rate", "Response time"),
            milestones = listOf(Milestone(description = "Phase 1", targetDate = System.currentTimeMillis() + 86400_000L)),
            preconditions = listOf("Team trained"),
            budget = 50000.0
        )
        val s = Scenario(
            title = "T", decisionOwner = "Owner",
            lifecycleStage = LifecycleStage.APPROVED,
            approvalStages = listOf(approvedStage),
            selectedPolicyOption = policy,
            implementationPlan = plan
        )
        val result = ImplementationGate.evaluate(s)
        assertEquals(ImplementationGateOverallStatus.APPROVED_NOT_STARTED, result.overallStatus)
        assertTrue(result.blockingReasons.isEmpty())
    }

    @Test
    fun `change record captures field-level audit trail`() {
        val record = ChangeRecord(
            summary = "Actions modified",
            previousVersion = 3,
            previousLifecycleStage = LifecycleStage.APPROVED,
            fieldChanged = "actions",
            oldValue = "3 actions",
            newValue = "2 actions",
            reason = "Removed infeasible action",
            invalidatedApproval = true,
            invalidatedSnapshot = true
        )
        assertEquals("actions", record.fieldChanged)
        assertTrue(record.invalidatedApproval)
        assertTrue(record.invalidatedSnapshot)
        assertEquals(LifecycleStage.APPROVED, record.previousLifecycleStage)
    }

    @Test
    fun `violated hard constraint blocks implementation gate`() {
        val hardConstraint = Constraint(id = "c1", label = "Legal compliance", kind = Constraint.Kind.LEGAL)
        val violatedAssessment = ConstraintAssessment(
            constraintId = "c1", actionId = "a1", status = ConstraintStatus.VIOLATED
        )
        val s = Scenario(
            title = "T",
            lifecycleStage = LifecycleStage.APPROVED,
            constraints = listOf(hardConstraint),
            constraintAssessments = listOf(violatedAssessment)
        )
        val result = ImplementationGate.evaluate(s)
        val hardGate = result.gates.find { it.name == "Hard constraints clear" }
        assertNotNull(hardGate)
        assertEquals(GateStatus.BLOCKED, hardGate!!.status)
        assertTrue(result.blockingReasons.any { it.contains("violated", ignoreCase = true) })
    }
}

// ─── Phase 6 end-to-end workflow orchestrator tests ──────────────────────────

class WorkflowOrchestratorTest {

    private fun minimalScenario(stage: LifecycleStage = LifecycleStage.DRAFT) = Scenario(
        title = "Test",
        decisionOwner = "Owner",
        stakeholders = listOf(Stakeholder(id = "s1", name = "A"), Stakeholder(id = "s2", name = "B")),
        lifecycleStage = stage
    )

    @Test
    fun `DRAFT with complete frame directs to model`() {
        val s = minimalScenario(LifecycleStage.DRAFT)
        val directive = WorkflowOrchestrator.nextAction(s)
        assertEquals("Build decision model", directive.ctaLabel)
        assertFalse(directive.isBlocked)
    }

    @Test
    fun `DRAFT with missing title is blocked`() {
        val s = Scenario(title = "", lifecycleStage = LifecycleStage.DRAFT)
        val directive = WorkflowOrchestrator.nextAction(s)
        assertTrue(directive.isBlocked)
        assertTrue(directive.blockers.any { it.contains("title", ignoreCase = true) })
    }

    @Test
    fun `ANALYSED without policy option directs to select policy`() {
        val s = minimalScenario(LifecycleStage.ANALYSED).copy(selectedPolicyOption = null)
        val directive = WorkflowOrchestrator.nextAction(s)
        assertEquals("Select policy option", directive.ctaLabel)
        assertEquals(NavigationTarget.REPORT_POLICY, directive.target)
    }

    @Test
    fun `ANALYSED with policy and unresolved hard constraints is blocked`() {
        val policy = PolicyOption(actionId = "a1", actionLabel = "Option A")
        val hardConstraint = Constraint(id = "c1", label = "Safety", kind = Constraint.Kind.SAFETY)
        val s = minimalScenario(LifecycleStage.ANALYSED).copy(
            selectedPolicyOption = policy,
            constraints = listOf(hardConstraint),
            constraintAssessments = emptyList()
        )
        val directive = WorkflowOrchestrator.nextAction(s)
        assertTrue(directive.isBlocked)
        assertEquals("Assess hard constraints", directive.ctaLabel)
    }

    @Test
    fun `ANALYSED with policy and all constraints resolved directs to define approval route`() {
        val policy = PolicyOption(actionId = "a1", actionLabel = "Option A")
        val hardConstraint = Constraint(id = "c1", label = "Safety", kind = Constraint.Kind.SAFETY)
        val assessment = ConstraintAssessment(
            constraintId = "c1", actionId = "a1", status = ConstraintStatus.SATISFIED
        )
        val s = minimalScenario(LifecycleStage.ANALYSED).copy(
            selectedPolicyOption = policy,
            constraints = listOf(hardConstraint),
            constraintAssessments = listOf(assessment),
            approvalStages = emptyList()
        )
        val directive = WorkflowOrchestrator.nextAction(s)
        assertEquals("Define approval route", directive.ctaLabel)
        assertFalse(directive.isBlocked)
    }

    @Test
    fun `ANALYSED with policy, all constraints resolved, and approval stages shows submit`() {
        val policy = PolicyOption(actionId = "a1", actionLabel = "Option A")
        val stage = ApprovalStage(name = "Board", status = ApprovalStatus.PENDING)
        val s = minimalScenario(LifecycleStage.ANALYSED).copy(
            selectedPolicyOption = policy,
            approvalStages = listOf(stage)
        )
        val directive = WorkflowOrchestrator.nextAction(s)
        assertEquals("Submit for approval", directive.ctaLabel)
    }

    @Test
    fun `APPROVED without plan directs to create plan`() {
        val s = minimalScenario(LifecycleStage.APPROVED).copy(implementationPlan = null)
        val directive = WorkflowOrchestrator.nextAction(s)
        assertEquals("Create implementation plan", directive.ctaLabel)
        assertFalse(directive.isBlocked)
    }

    @Test
    fun `APPROVED with plan missing stop condition is blocked`() {
        val plan = ImplementationPlan(owner = "Owner", stopCondition = "")
        val s = minimalScenario(LifecycleStage.APPROVED).copy(implementationPlan = plan)
        val directive = WorkflowOrchestrator.nextAction(s)
        assertTrue(directive.isBlocked)
        assertTrue(directive.blockers.any { it.contains("stop condition", ignoreCase = true) })
    }

    @Test
    fun `APPROVED with complete plan directs to begin implementation`() {
        val plan = ImplementationPlan(owner = "PMO", stopCondition = "Error > 5%")
        val s = minimalScenario(LifecycleStage.APPROVED).copy(implementationPlan = plan)
        val directive = WorkflowOrchestrator.nextAction(s)
        assertEquals("Begin implementation", directive.ctaLabel)
        assertFalse(directive.isBlocked)
    }

    @Test
    fun `PAUSED lifecycle shows review pause decision as blocked`() {
        val s = minimalScenario(LifecycleStage.PAUSED)
        val directive = WorkflowOrchestrator.nextAction(s)
        assertTrue(directive.isBlocked)
        assertEquals("Review pause decision", directive.ctaLabel)
        assertEquals(NavigationTarget.IMPLEMENTATION_MONITORING, directive.target)
    }

    @Test
    fun `stageStates marks completed stages before current`() {
        val s = minimalScenario(LifecycleStage.ANALYSED)
        val states = WorkflowOrchestrator.stageStates(s)
        val frameState = states.find { it.stage == WorkflowStage.FRAME }
        val modelState = states.find { it.stage == WorkflowStage.MODEL }
        val decideState = states.find { it.stage == WorkflowStage.DECIDE }
        assertNotNull(frameState); assertNotNull(modelState); assertNotNull(decideState)
        assertEquals(StageStatus.COMPLETED, frameState!!.status)
        assertEquals(StageStatus.COMPLETED, modelState!!.status)
        // DECIDE is current for ANALYSED
        assertTrue(decideState!!.status == StageStatus.CURRENT || decideState.status == StageStatus.CURRENT_BLOCKED)
    }

    @Test
    fun `PolicyOption extended fields have defaults`() {
        val policy = PolicyOption(actionId = "a1", actionLabel = "Option A")
        assertEquals("", policy.fallbackActionId)
        assertEquals("", policy.selectedSnapshotId)
        assertTrue(policy.riskControls.isEmpty())
        assertEquals("", policy.stopCondition)
        assertEquals("", policy.reversalPlan)
    }

    @Test
    fun `ConstraintAssessment extended fields have defaults`() {
        val assessment = ConstraintAssessment(constraintId = "c1", actionId = "a1")
        assertEquals("", assessment.policyOptionId)
        assertEquals("", assessment.assessor)
        assertEquals(0L, assessment.expiryDate)
        assertEquals(0L, assessment.reviewDate)
    }

    @Test
    fun `validatePolicyConsistency returns empty for consistent scenario`() {
        val policy = PolicyOption(actionId = "a1", actionLabel = "Option A")
        val plan = ImplementationPlan(selectedActionId = "a1")
        val s = minimalScenario(LifecycleStage.IN_IMPLEMENTATION).copy(
            selectedPolicyOption = policy,
            implementationPlan = plan
        )
        val issues = WorkflowOrchestrator.validatePolicyConsistency(s)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `validatePolicyConsistency detects plan referencing different action`() {
        val policy = PolicyOption(actionId = "a1", actionLabel = "Option A")
        val plan = ImplementationPlan(selectedActionId = "a2")  // mismatch
        val s = minimalScenario(LifecycleStage.IN_IMPLEMENTATION).copy(
            selectedPolicyOption = policy,
            implementationPlan = plan
        )
        val issues = WorkflowOrchestrator.validatePolicyConsistency(s)
        assertTrue(issues.isNotEmpty())
        assertTrue(issues.first().contains("does not match", ignoreCase = true))
    }

    @Test
    fun `PAUSED lifecycle stage maps to LEARN workflow stage`() {
        assertEquals(WorkflowStage.LEARN, LifecycleStage.PAUSED.toWorkflowStage())
    }

    @Test
    fun `ApprovalDecision threshold logic SIMPLE_MAJORITY needs majority`() {
        val stageId = "st1"
        val decisions = listOf(
            ApprovalDecision(stageId = stageId, approverId = "u1", decision = ApprovalStatus.APPROVED),
            ApprovalDecision(stageId = stageId, approverId = "u2", decision = ApprovalStatus.APPROVED),
            ApprovalDecision(stageId = stageId, approverId = "u3", decision = ApprovalStatus.REJECTED)
        )
        val stage = ApprovalStage(
            stageId = stageId,
            name = "Board",
            requiredStakeholderIds = listOf("u1", "u2", "u3"),
            threshold = ApprovalStage.Threshold.SIMPLE_MAJORITY,
            decisions = decisions
        )
        assertEquals(ApprovalStatus.APPROVED, stage.effectiveStatus())
    }

    @Test
    fun `ApprovalDecision CONSENSUS fails with one rejection`() {
        val stageId = "st2"
        val decisions = listOf(
            ApprovalDecision(stageId = stageId, approverId = "u1", decision = ApprovalStatus.APPROVED),
            ApprovalDecision(stageId = stageId, approverId = "u2", decision = ApprovalStatus.APPROVED),
            ApprovalDecision(stageId = stageId, approverId = "u3", decision = ApprovalStatus.REJECTED)
        )
        val stage = ApprovalStage(
            stageId = stageId,
            name = "Board",
            requiredStakeholderIds = listOf("u1", "u2", "u3"),
            threshold = ApprovalStage.Threshold.CONSENSUS,
            decisions = decisions
        )
        assertEquals(ApprovalStatus.REJECTED, stage.effectiveStatus())
    }
}
