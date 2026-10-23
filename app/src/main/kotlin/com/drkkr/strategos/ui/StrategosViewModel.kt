package com.drkkr.strategos.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.ScenarioRepository
import com.drkkr.strategos.data.storage.RecoverableRecord
import com.drkkr.strategos.domain.framework.AnalysisResult
import com.drkkr.strategos.domain.framework.DecisionReadinessResult
import com.drkkr.strategos.domain.framework.ExecutiveStatus
import com.drkkr.strategos.domain.framework.FrameworkRegistry
import com.drkkr.strategos.domain.framework.ModelValidityReport
import com.drkkr.strategos.domain.framework.ModelValidator
import com.drkkr.strategos.domain.framework.ModuleRunResult
import com.drkkr.strategos.domain.framework.ModuleRunStatus
import com.drkkr.strategos.domain.framework.AnalysisRunStatus
import com.drkkr.strategos.domain.model.AnalysisSnapshot
import com.drkkr.strategos.domain.model.ApprovalDecision
import com.drkkr.strategos.domain.model.ApprovalStage
import com.drkkr.strategos.domain.model.ApprovalStatus
import com.drkkr.strategos.domain.model.ChangeRecord
import com.drkkr.strategos.domain.model.ImplementationPlan
import com.drkkr.strategos.domain.model.LifecycleStage
import com.drkkr.strategos.domain.model.LifecycleTransition
import com.drkkr.strategos.domain.model.ConstraintAssessment
import com.drkkr.strategos.domain.model.MonitoringEntry
import com.drkkr.strategos.domain.model.MonitoringSignal
import com.drkkr.strategos.domain.model.NavigationTarget
import com.drkkr.strategos.domain.model.PolicyOption
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.TransitionResult
import com.drkkr.strategos.domain.model.WorkflowOrchestrator
import com.drkkr.strategos.domain.model.WorkflowStage
import com.drkkr.strategos.domain.recommendation.DecisionReport
import com.drkkr.strategos.domain.recommendation.RecommendationEngine
import com.drkkr.strategos.framework.AnalysisPlan
import com.drkkr.strategos.framework.FrameworkSelector
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.drkkr.strategos.framework.behavioral.BehavioralResponseModule
import com.drkkr.strategos.framework.behavioral.ChickenGameModule
import com.drkkr.strategos.framework.behavioral.CoalitionAnalysisModule
import com.drkkr.strategos.framework.behavioral.CommonPoolResourceModule
import com.drkkr.strategos.framework.behavioral.LevelKModule
import com.drkkr.strategos.framework.behavioral.PrincipalAgentModule
import com.drkkr.strategos.framework.behavioral.PrisonersDilemmaModule
import com.drkkr.strategos.framework.strategic.AuctionModule
import com.drkkr.strategos.framework.strategic.IteratedDominanceModule
import com.drkkr.strategos.framework.strategic.RedTeamModule
import com.drkkr.strategos.framework.strategic.RobustDecisionFrontierModule
import com.drkkr.strategos.framework.strategic.ScenarioEnsembleModule
import com.drkkr.strategos.framework.strategic.BayesianUpdatingModule
import com.drkkr.strategos.framework.strategic.DynamicSimulationModule
import com.drkkr.strategos.framework.strategic.SignallingModule
import com.drkkr.strategos.framework.strategic.StackelbergModule
import com.drkkr.strategos.framework.strategic.ValueOfInformationModule
import com.drkkr.strategos.framework.behavioral.PublicGoodsModule
import com.drkkr.strategos.framework.behavioral.StagHuntModule
import com.drkkr.strategos.framework.behavioral.StakeholderTrustDynamicsModule
import com.drkkr.strategos.framework.behavioral.TrustGameModule
import com.drkkr.strategos.framework.behavioral.UltimatumModule
import com.drkkr.strategos.framework.behavioral.VirtualBargainingModule
import com.drkkr.strategos.framework.dominant.DominantStrategyModule
import com.drkkr.strategos.framework.eu.ExpectedUtilityModule
import com.drkkr.strategos.framework.minimax.MinimaxModule
import com.drkkr.strategos.framework.nash.MixedNash2x2Module
import com.drkkr.strategos.framework.nash.PureNashModule
import com.drkkr.strategos.framework.pareto.ParetoModule
import com.drkkr.strategos.framework.sensitivity.SensitivityModule
import com.drkkr.strategos.framework.tree.BackwardInductionModule
import com.drkkr.strategos.nlp.ScenarioParser
import com.drkkr.strategos.templates.InstitutionalHealthcareTemplate
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.analysis.ProvenanceReconciler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

data class UiState(
    val scenarios: List<Scenario> = emptyList(),
    val activeScenario: Scenario? = null,
    val analysisResults: List<AnalysisResult> = emptyList(),
    val moduleRunResults: List<ModuleRunResult> = emptyList(),
    val decisionReadiness: DecisionReadinessResult? = null,
    val report: DecisionReport? = null,
    val analysisPlan: AnalysisPlan? = null,
    val isAnalyzing: Boolean = false,
    val errorMessage: String? = null,
    val clarificationQuestions: List<String> = emptyList(),
    val debugEntries: List<DebugEntry> = emptyList(),
    // Phase 3: storage & validity
    val storageSecure: Boolean = true,
    val storageError: String? = null,
    val recoverableScenarios: List<RecoverableRecord> = emptyList(),
    val modelValidityReport: ModelValidityReport? = null,
    val snapshot: AnalysisSnapshot? = null,
    val snapshotInvalidated: Boolean = false,
    // Phase 4: governance
    val snapshotHistory: List<AnalysisSnapshot> = emptyList(),
    val lifecycleTransitionError: String? = null,
    val pendingNavigationTarget: NavigationTarget? = null
)

class StrategosViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScenarioRepository(app)
    private val pipeline = AnalysisPipeline()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val snapshotJson = Json { ignoreUnknownKeys = true }

    init {
        _state.update { it.copy(storageSecure = repo.isStorageSecure, storageError = repo.storageError) }
        viewModelScope.launch { repo.load() }
        viewModelScope.launch { repo.scenarios.collect { scenarios -> _state.update { s -> s.copy(scenarios = scenarios) } } }
        viewModelScope.launch { repo.recoverableRecords.collect { recs -> _state.update { s -> s.copy(recoverableScenarios = recs) } } }
    }

    fun parseAndCreateScenario(rawText: String) {
        val parsed = ScenarioParser.parse(rawText)
        _state.update { it.copy(activeScenario = parsed.draft, clarificationQuestions = parsed.clarificationQuestions) }
    }

    fun loadTemplate() {
        val s = InstitutionalHealthcareTemplate.create()
        _state.update { it.copy(activeScenario = s, clarificationQuestions = emptyList()) }
    }

    fun loadResourceAllocationTemplate() {
        val s = com.drkkr.strategos.templates.ResourceAllocationTemplate.create()
        _state.update { it.copy(activeScenario = s, clarificationQuestions = emptyList()) }
    }

    fun loadCrisisTemplate() {
        val s = com.drkkr.strategos.templates.CrisisManagementTemplate.create()
        _state.update { it.copy(activeScenario = s, clarificationQuestions = emptyList()) }
    }

    fun loadProcurementTemplate() {
        val s = com.drkkr.strategos.templates.ProcurementTemplate.create()
        _state.update { it.copy(activeScenario = s, clarificationQuestions = emptyList()) }
    }

    fun setActiveScenario(s: Scenario) {
        _state.update { it.copy(activeScenario = s, analysisResults = emptyList(), moduleRunResults = emptyList(), report = null, decisionReadiness = null, snapshotInvalidated = false) }
    }

    fun updateActiveScenario(s: Scenario) {
        val hadSnapshot = _state.value.snapshot != null
        val current = _state.value.activeScenario
        val reconciled = if (current != null) ProvenanceReconciler.onManualEdit(current, s) else s
        _state.update { it.copy(activeScenario = reconciled, snapshotInvalidated = hadSnapshot) }
    }

    fun analyze() {
        viewModelScope.launch { analyzeAndAwait() }
    }

    suspend fun analyzeAndAwait(): PipelineResult? {
        val scenario = _state.value.activeScenario ?: return null
        val validity = ModelValidator.validate(scenario)
        if (validity.blocksAnalysis) {
            _state.update { it.copy(modelValidityReport = validity, errorMessage = validity.issues.first().message) }
            return null
        }
        _state.update { it.copy(
            isAnalyzing = true, errorMessage = null, analysisPlan = null,
            analysisResults = emptyList(), moduleRunResults = emptyList(),
            debugEntries = emptyList(), modelValidityReport = validity,
            snapshot = null, snapshotInvalidated = false
        ) }
        return try {
            val pr = withContext(Dispatchers.Default) { pipeline.run(scenario) }
            val plan = pr.plan!!
            val selectedIds = plan.selected().map { it.moduleId }.toSet()
            val moduleRuns = pr.moduleRuns

            // Build debug entries from FAILED modules only — no raw stack traces in state
            val debugEntries = moduleRuns
                .filter { it.status == ModuleRunStatus.FAILED_RECOVERABLY }
                .map { run ->
                    DebugEntry(
                        source = run.moduleId,
                        level = DebugEntry.Level.ERROR,
                        message = run.userMessage ?: "${run.moduleName} could not run.",
                        detail = buildString {
                            appendLine("Diagnostic ID: ${run.diagnosticId ?: "n/a"}")
                            appendLine("Module: ${run.moduleName}")
                            run.result?.calculations?.let { calc ->
                                appendLine("Class: ${calc["errorClass"] ?: "unknown"}")
                                appendLine("Message: ${calc["errorMessage"] ?: "unknown"}")
                            }
                        }
                    )
                }

            val analysisResults = pr.results
            val report = pr.report!!

            // Determine run status from ModuleRunStatus — never string matching
            val requiredFailed = moduleRuns.any {
                it.status == ModuleRunStatus.FAILED_RECOVERABLY && it.isRequired()
            }
            val requiredSkipped = moduleRuns.any {
                it.status == ModuleRunStatus.SKIPPED_INSUFFICIENT_INPUT && it.isRequired()
            }
            val anyFailed = moduleRuns.any { it.status == ModuleRunStatus.FAILED_RECOVERABLY }

            val runStatus = when {
                requiredFailed || requiredSkipped -> AnalysisRunStatus.FAILED
                anyFailed -> AnalysisRunStatus.PARTIAL
                else -> AnalysisRunStatus.COMPLETE
            }

            // Lifecycle gate: structured status, not string matching
            val targetStage = when (runStatus) {
                AnalysisRunStatus.FAILED -> scenario.lifecycleStage  // keep current, don't advance
                AnalysisRunStatus.PARTIAL -> LifecycleStage.ANALYSED // partial allowed
                AnalysisRunStatus.COMPLETE -> LifecycleStage.ANALYSED
                AnalysisRunStatus.NOT_RUN -> scenario.lifecycleStage
            }

            // Only persist a new snapshot for PARTIAL or COMPLETE runs
            val snapshot = if (runStatus != AnalysisRunStatus.FAILED) {
                val scenarioJson = try { snapshotJson.encodeToString(scenario) } catch (e: Exception) { scenario.id }
                val fullPayload = buildString {
                    append(scenarioJson)
                    append("|policy=").append(scenario.selectedPolicyOption?.actionId ?: "none")
                    append("|exec=").append(report.executiveStatus.name)
                    append("|conf=").append(report.overallConfidence)
                    append("|modules=").append(selectedIds.toList().sorted().joinToString(","))
                    append("|constraints=").append(scenario.constraints.size)
                    append("|runStatus=").append(runStatus.name)
                }
                AnalysisSnapshot(
                    scenarioId = scenario.id,
                    scenarioVersion = scenario.version,
                    scenarioHash = AnalysisSnapshot.hashString(fullPayload),
                    selectedModuleIds = selectedIds.toList().sorted(),
                    executiveStatus = report.executiveStatus.name,
                    candidateCount = report.candidates.size,
                    overallConfidence = report.overallConfidence,
                    reportHash = AnalysisSnapshot.hashString(fullPayload),
                    scenarioJson = scenarioJson
                ).also { repo.saveSnapshot(it) }
            } else {
                _state.value.snapshot // preserve last valid snapshot on failure
            }

            val analysedScenario = scenario.copy(
                lifecycleStage = targetStage,
                snapshotId = snapshot?.snapshotId ?: scenario.snapshotId
            )
            repo.save(analysedScenario)

            val readiness = buildDecisionReadiness(
                scenario = analysedScenario,
                report = report,
                moduleRuns = moduleRuns,
                runStatus = runStatus,
                snapshot = snapshot
            )

            _state.update { st ->
                st.copy(
                    isAnalyzing = false,
                    analysisResults = analysisResults,
                    moduleRunResults = moduleRuns,
                    decisionReadiness = readiness,
                    report = report,
                    analysisPlan = plan,
                    debugEntries = debugEntries,
                    snapshot = snapshot,
                    activeScenario = analysedScenario,
                    scenarios = st.scenarios.map { if (it.id == analysedScenario.id) analysedScenario else it }
                )
            }
            pr
        } catch (e: Exception) {
            val fatalEntry = DebugEntry(
                source = "analysis-pipeline",
                level = DebugEntry.Level.ERROR,
                message = "Analysis pipeline error. Please try again.",
                detail = buildString {
                    appendLine("Pipeline exception")
                    appendLine(e.stackTraceToString().take(4000))
                }
            )
            _state.update {
                it.copy(
                    isAnalyzing = false,
                    errorMessage = "Analysis could not complete. Please try again.",
                    debugEntries = listOf(fatalEntry)
                )
            }
            null
        }
    }

    private fun buildDecisionReadiness(
        scenario: com.drkkr.strategos.domain.model.Scenario,
        report: com.drkkr.strategos.domain.recommendation.DecisionReport,
        moduleRuns: List<ModuleRunResult>,
        runStatus: AnalysisRunStatus,
        snapshot: com.drkkr.strategos.domain.model.AnalysisSnapshot?
    ): DecisionReadinessResult {
        val blockers = buildBlockers(scenario, report, moduleRuns, runStatus)
            .distinctBy { it.title }
            .sortedBy { DecisionReadinessResult.priorityOf(it) }

        val snapshotStatus = when {
            snapshot == null -> DecisionReadinessResult.SnapshotStatus.MISSING
            _state.value.snapshotInvalidated -> DecisionReadinessResult.SnapshotStatus.STALE
            else -> DecisionReadinessResult.SnapshotStatus.VALID
        }

        return DecisionReadinessResult(
            primaryStatus = report.executiveStatus,
            summary = buildReadinessSummary(report, runStatus, blockers.size),
            blockers = blockers,
            requiredActions = blockers.map { it.fixLabel }.distinct(),
            policyEligible = report.executiveStatus.isApprovalSafe() && blockers.none {
                it.severity == com.drkkr.strategos.domain.framework.BlockerSeverity.CRITICAL
            },
            snapshotStatus = snapshotStatus,
            lifecycleStatus = scenario.lifecycleStage.name,
            analysisRunStatus = runStatus,
            structuralCompleteness = scenario.completenessScore(),
            evidenceQuality = scenario.evidenceQuality(),
            moduleRunResults = moduleRuns
        )
    }

    private fun buildBlockers(
        scenario: com.drkkr.strategos.domain.model.Scenario,
        report: com.drkkr.strategos.domain.recommendation.DecisionReport,
        moduleRuns: List<ModuleRunResult>,
        runStatus: AnalysisRunStatus
    ): List<com.drkkr.strategos.domain.framework.BlockerAction> {
        val blockers = mutableListOf<com.drkkr.strategos.domain.framework.BlockerAction>()

        // Safety violations — CRITICAL
        val violations = scenario.constraintAssessments.filter {
            it.status == com.drkkr.strategos.domain.model.ConstraintStatus.VIOLATED
        }
        violations.forEach { v ->
            blockers += com.drkkr.strategos.domain.framework.BlockerAction(
                severity = com.drkkr.strategos.domain.framework.BlockerSeverity.CRITICAL,
                title = "Safety constraint violated: ${v.constraintId}",
                impact = "This action is blocked until the violation is resolved.",
                currentState = "Constraint assessed as VIOLATED.",
                fixLabel = "Review constraint assessment",
                target = NavigationTarget.MODEL_CONSTRAINTS
            )
        }

        // Missing evidence — HIGH
        if (scenario.evidenceSources.isEmpty()) {
            blockers += com.drkkr.strategos.domain.framework.BlockerAction(
                severity = com.drkkr.strategos.domain.framework.BlockerSeverity.HIGH,
                title = "No evidence sources recorded",
                impact = "Decision readiness cannot be established without evidence.",
                currentState = "0 evidence sources.",
                fixLabel = "Add evidence source",
                target = NavigationTarget.MODEL_EVIDENCE
            )
        }

        // Missing policy selection — HIGH
        if (scenario.selectedPolicyOption == null && report.pathways.isNotEmpty()) {
            blockers += com.drkkr.strategos.domain.framework.BlockerAction(
                severity = com.drkkr.strategos.domain.framework.BlockerSeverity.HIGH,
                title = "No policy action selected",
                impact = "Cannot proceed to approval without a selected policy pathway.",
                currentState = "No pathway selected.",
                fixLabel = "Select policy action",
                target = NavigationTarget.REPORT_POLICY
            )
        }

        // Module failures — HIGH
        moduleRuns.filter { it.status == ModuleRunStatus.FAILED_RECOVERABLY }.forEach { run ->
            blockers += com.drkkr.strategos.domain.framework.BlockerAction(
                severity = com.drkkr.strategos.domain.framework.BlockerSeverity.HIGH,
                title = "${run.moduleName} could not run",
                impact = "Analysis results may be incomplete.",
                currentState = run.userMessage ?: "Module error.",
                fixLabel = "Retry analysis",
                target = NavigationTarget.ANALYSIS,
                technicalDetail = run.diagnosticId?.let { id -> "Diagnostic ID: $id" }
            )
        }

        // Unassessed constraints — MEDIUM
        val unassessed = scenario.constraints.filter { c ->
            scenario.constraintAssessments.none { a -> a.constraintId == c.id }
        }
        if (unassessed.isNotEmpty()) {
            blockers += com.drkkr.strategos.domain.framework.BlockerAction(
                severity = com.drkkr.strategos.domain.framework.BlockerSeverity.MEDIUM,
                title = "${unassessed.size} constraint(s) not assessed",
                impact = "Hard constraints on irreversible actions must be assessed.",
                currentState = "${unassessed.size} of ${scenario.constraints.size} constraints unassessed.",
                fixLabel = "Assess constraints",
                target = NavigationTarget.MODEL_CONSTRAINTS
            )
        }

        // Stakeholders with no goals or incentives — MEDIUM
        val noGoals = scenario.stakeholders.count { it.goals.isEmpty() && it.incentives.isEmpty() }
        if (noGoals > 0) {
            blockers += com.drkkr.strategos.domain.framework.BlockerAction(
                severity = com.drkkr.strategos.domain.framework.BlockerSeverity.MEDIUM,
                title = "$noGoals stakeholder(s) missing goals/incentives",
                impact = "Incomplete stakeholder models reduce policy legitimacy.",
                currentState = "$noGoals of ${scenario.stakeholders.size} stakeholders have no goals.",
                fixLabel = "Describe stakeholder goals",
                target = NavigationTarget.FRAME
            )
        }

        return blockers
    }

    private fun buildReadinessSummary(
        report: com.drkkr.strategos.domain.recommendation.DecisionReport,
        runStatus: AnalysisRunStatus,
        blockerCount: Int
    ): String = when {
        runStatus == AnalysisRunStatus.FAILED -> "Analysis could not complete — required modules failed. Fix blockers and re-run."
        runStatus == AnalysisRunStatus.PARTIAL -> "Partial analysis complete. Some modules could not run."
        blockerCount == 0 -> "Analysis complete. No blockers. Decision pathway ready for review."
        else -> "Analysis complete. $blockerCount blocker(s) must be resolved before proceeding."
    }

    fun saveScenario() {
        var s = _state.value.activeScenario ?: return
        viewModelScope.launch {
            // Material change detection: if scenario is APPROVED+, check for changes that
            // invalidate approval (actions, constraints, payoffs, selected policy option)
            val stored = _state.value.scenarios.find { it.id == s.id }
            val approvedOrdinal = LifecycleStage.APPROVED.ordinal
            if (stored != null && stored.lifecycleStage.ordinal >= approvedOrdinal) {
                val materialChange = detectMaterialChange(stored, s)
                if (materialChange != null) {
                    val now = System.currentTimeMillis()
                    val invalidatedApprovals = s.approvalStages.map { it.copy(invalidatedAt = now) }
                    val record = ChangeRecord(
                        summary = materialChange,
                        previousVersion = s.version,
                        previousLifecycleStage = s.lifecycleStage,
                        fieldChanged = materialChange.substringBefore(":").trim(),
                        oldValue = "see previous version",
                        newValue = "see current version",
                        reason = "Material change to approved scenario",
                        invalidatedApproval = true,
                        invalidatedSnapshot = true
                    )
                    s = s.copy(
                        lifecycleStage = LifecycleStage.HOLD,
                        approvalStages = invalidatedApprovals,
                        changeHistory = s.changeHistory + record
                    )
                    _state.update { it.copy(activeScenario = s) }
                }
            }
            when (val result = repo.save(s)) {
                is SaveResult.Success -> _state.update { it.copy(activeScenario = result.saved) }
                is SaveResult.Conflict -> _state.update {
                    it.copy(errorMessage = "Save conflict: stored version is newer (v${result.stored.version}). Your changes were not saved. Reload the scenario to see the current version.")
                }
                is SaveResult.StorageUnavailable -> _state.update {
                    it.copy(errorMessage = "Storage unavailable — scenario cannot be saved")
                }
            }
        }
    }

    private fun detectMaterialChange(stored: Scenario, current: Scenario): String? {
        // Canonical field-level material-change detection per spec section 7
        // Action content changes
        if (stored.actions.map { it.id }.toSet() != current.actions.map { it.id }.toSet())
            return "actions: set changed"
        val storedActionMap = stored.actions.associateBy { it.id }
        for (action in current.actions) {
            val prev = storedActionMap[action.id] ?: continue
            if (prev.label != action.label) return "action '${action.label}': label changed"
            if (prev.description != action.description) return "action '${action.label}': description changed"
            if (prev.stakeholderId != action.stakeholderId) return "action '${action.label}': owner changed"
        }
        // Payoff changes — PayoffEntry has no stable id; compare count and stakeholder sets
        if (stored.payoffs.size != current.payoffs.size) return "payoffs: count changed"
        val storedPayoffStakeholders = stored.payoffs.map { it.stakeholderId }.toSet()
        val currentPayoffStakeholders = current.payoffs.map { it.stakeholderId }.toSet()
        if (storedPayoffStakeholders != currentPayoffStakeholders) return "payoffs: stakeholder set changed"
        // Policy option
        if (stored.selectedPolicyOption?.actionId != current.selectedPolicyOption?.actionId)
            return "selectedPolicyOption: changed"
        // Decision owner
        if (stored.decisionOwner != current.decisionOwner) return "decisionOwner: changed"
        // Hard constraints set
        if (stored.constraints.map { it.id }.toSet() != current.constraints.map { it.id }.toSet())
            return "constraints: set changed"
        // Constraint assessments — status changes on hard constraints
        val hardKinds = setOf(com.drkkr.strategos.domain.model.Constraint.Kind.SAFETY,
            com.drkkr.strategos.domain.model.Constraint.Kind.LEGAL,
            com.drkkr.strategos.domain.model.Constraint.Kind.ETHICAL,
            com.drkkr.strategos.domain.model.Constraint.Kind.USER_HARD)
        val hardIds = current.constraints.filter { it.kind in hardKinds }.map { it.id }.toSet()
        val storedAssessMap = stored.constraintAssessments.filter { it.constraintId in hardIds }.associateBy { it.constraintId }
        for (assess in current.constraintAssessments.filter { it.constraintId in hardIds }) {
            val prev = storedAssessMap[assess.constraintId]
            if (prev?.status != assess.status) return "constraint assessment '${assess.constraintId}': status changed"
        }
        // Implementation plan risk fields
        val sp = stored.implementationPlan
        val cp = current.implementationPlan
        if (sp != null && cp != null) {
            if (sp.stopCondition != cp.stopCondition) return "plan: stopCondition changed"
            if (sp.reversalPlan != cp.reversalPlan) return "plan: reversalPlan changed"
            if (sp.owner != cp.owner) return "plan: owner changed"
        } else if ((sp == null) != (cp == null)) {
            return "plan: added or removed"
        }
        return null
    }

    fun transitionLifecycle(to: LifecycleStage) {
        val s = _state.value.activeScenario ?: return
        val result: TransitionResult = LifecycleTransition.canTransition(s, to)
        if (!result.allowed) {
            _state.update { it.copy(lifecycleTransitionError = result.blockers.joinToString("\n")) }
            return
        }
        val record = ChangeRecord(
            summary = "Lifecycle stage changed from ${s.lifecycleStage.displayLabel()} to ${to.displayLabel()}",
            previousVersion = s.version,
            previousLifecycleStage = s.lifecycleStage
        )
        val updated = s.copy(
            lifecycleStage = to,
            changeHistory = s.changeHistory + record
        )
        _state.update { it.copy(activeScenario = updated, lifecycleTransitionError = null) }
    }

    fun dismissLifecycleError() { _state.update { it.copy(lifecycleTransitionError = null) } }

    fun loadSnapshotHistory() {
        val scenarioId = _state.value.activeScenario?.id ?: return
        viewModelScope.launch {
            val history = repo.loadSnapshots(scenarioId)
            _state.update { it.copy(snapshotHistory = history) }
        }
    }

    fun deleteScenario(id: String) { viewModelScope.launch { repo.delete(id) } }

    fun newBlankScenario() {
        _state.update { it.copy(
            activeScenario = Scenario(title = "New scenario"),
            analysisResults = emptyList(), moduleRunResults = emptyList(),
            report = null, decisionReadiness = null, snapshot = null, snapshotInvalidated = false
        ) }
    }

    fun dismissError() { _state.update { it.copy(errorMessage = null) } }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun navigateTo(target: NavigationTarget) { _state.update { it.copy(pendingNavigationTarget = target) } }
    fun navigateViaOrchestrator() {
        val s = _state.value.activeScenario ?: return
        val directive = WorkflowOrchestrator.nextAction(s)
        navigateTo(directive.target)
    }
    fun clearNavigation() { _state.update { it.copy(pendingNavigationTarget = null) } }

    // ── Persist helper ────────────────────────────────────────────────────────
    private fun persistActiveScenario() {
        val s = _state.value.activeScenario ?: return
        viewModelScope.launch {
            when (val r = repo.save(s)) {
                is SaveResult.Success -> _state.update { it.copy(activeScenario = r.saved) }
                is SaveResult.Conflict -> _state.update {
                    it.copy(errorMessage = "Save conflict — reload scenario to see latest version")
                }
                is SaveResult.StorageUnavailable -> _state.update {
                    it.copy(errorMessage = "Storage unavailable — change not persisted")
                }
            }
        }
    }

    // ── Phase 4b: policy, approval, monitoring ────────────────────────────────

    fun selectPolicyOption(option: PolicyOption) {
        val s = _state.value.activeScenario ?: return
        val record = ChangeRecord(
            summary = "Policy option selected: ${option.actionLabel}",
            previousVersion = s.version,
            previousLifecycleStage = s.lifecycleStage,
            fieldChanged = "selectedPolicyOption",
            oldValue = s.selectedPolicyOption?.actionLabel ?: "none",
            newValue = option.actionLabel
        )
        _state.update {
            it.copy(activeScenario = s.copy(
                selectedPolicyOption = option,
                changeHistory = s.changeHistory + record
            ))
        }
        persistActiveScenario()
    }

    fun recordApprovalDecision(stageId: String, status: ApprovalStatus, rationale: String) {
        val s = _state.value.activeScenario ?: return
        val now = System.currentTimeMillis()
        val updated = s.copy(
            approvalStages = s.approvalStages.map { stage ->
                if (stage.stageId == stageId) stage.copy(status = status, rationale = rationale, recordedAt = now)
                else stage
            }
        )
        _state.update { it.copy(activeScenario = updated) }
        persistActiveScenario()
    }

    fun addMonitoringEntry(entry: MonitoringEntry) {
        val s = _state.value.activeScenario ?: return
        val plan = s.implementationPlan ?: return
        val updatedPlan = plan.copy(monitoringEntries = plan.monitoringEntries + entry)
        var updatedScenario = s.copy(implementationPlan = updatedPlan)
        if (entry.signalStatus == MonitoringSignal.STOP_TRIGGERED) {
            val record = ChangeRecord(
                summary = "Stop signal triggered — lifecycle paused for review",
                previousVersion = s.version,
                previousLifecycleStage = s.lifecycleStage,
                fieldChanged = "lifecycleStage",
                oldValue = s.lifecycleStage.displayLabel(),
                newValue = LifecycleStage.PAUSED.displayLabel(),
                reason = entry.observedOutcome,
                invalidatedApproval = false
            )
            updatedScenario = updatedScenario.copy(
                lifecycleStage = LifecycleStage.PAUSED,
                changeHistory = updatedScenario.changeHistory + record
            )
        }
        _state.update { it.copy(activeScenario = updatedScenario) }
        persistActiveScenario()
    }

    fun triggerStopSignal(reason: String) {
        val s = _state.value.activeScenario ?: return
        val record = ChangeRecord(
            summary = "Stop condition triggered — implementation paused",
            previousVersion = s.version,
            previousLifecycleStage = s.lifecycleStage,
            fieldChanged = "lifecycleStage",
            oldValue = s.lifecycleStage.displayLabel(),
            newValue = LifecycleStage.PAUSED.displayLabel(),
            reason = reason,
            invalidatedApproval = false
        )
        _state.update {
            it.copy(activeScenario = s.copy(
                lifecycleStage = LifecycleStage.PAUSED,
                changeHistory = s.changeHistory + record
            ))
        }
        persistActiveScenario()
    }

    // ── Approval stage management ─────────────────────────────────────────────

    fun addApprovalStage(stage: ApprovalStage) {
        val s = _state.value.activeScenario ?: return
        _state.update { it.copy(activeScenario = s.copy(approvalStages = s.approvalStages + stage)) }
        persistActiveScenario()
    }

    fun removeApprovalStage(stageId: String) {
        val s = _state.value.activeScenario ?: return
        _state.update { it.copy(activeScenario = s.copy(approvalStages = s.approvalStages.filter { it.stageId != stageId })) }
        persistActiveScenario()
    }

    fun recordApprovalDecisionFull(stageId: String, decision: ApprovalDecision) {
        val s = _state.value.activeScenario ?: return
        val updated = s.copy(
            approvalStages = s.approvalStages.map { stage ->
                if (stage.stageId == stageId)
                    stage.copy(decisions = stage.decisions + decision)
                else stage
            }
        )
        _state.update { it.copy(activeScenario = updated) }
        persistActiveScenario()
    }

    fun submitForApproval() {
        val s = _state.value.activeScenario ?: return
        val result = LifecycleTransition.canTransition(s, LifecycleStage.READY_FOR_APPROVAL)
        if (!result.allowed) {
            _state.update { it.copy(lifecycleTransitionError = result.blockers.joinToString("\n")) }
            return
        }
        val record = ChangeRecord(
            summary = "Submitted for approval",
            previousVersion = s.version,
            previousLifecycleStage = s.lifecycleStage
        )
        val updated = s.copy(
            lifecycleStage = LifecycleStage.READY_FOR_APPROVAL,
            changeHistory = s.changeHistory + record
        )
        _state.update { it.copy(activeScenario = updated, lifecycleTransitionError = null) }
        viewModelScope.launch { repo.save(updated) }
    }

    // ── Pause/resume/revert ───────────────────────────────────────────────────

    fun resumeFromPause() {
        val s = _state.value.activeScenario ?: return
        val record = ChangeRecord(
            summary = "Resumed from pause",
            previousVersion = s.version,
            previousLifecycleStage = s.lifecycleStage
        )
        _state.update {
            it.copy(activeScenario = s.copy(
                lifecycleStage = LifecycleStage.IN_IMPLEMENTATION,
                changeHistory = s.changeHistory + record
            ))
        }
        persistActiveScenario()
    }

    fun revertFromPause() {
        val s = _state.value.activeScenario ?: return
        val record = ChangeRecord(
            summary = "Reverted from pause — implementation stopped, decision closed",
            previousVersion = s.version,
            previousLifecycleStage = s.lifecycleStage,
            invalidatedApproval = true
        )
        _state.update {
            it.copy(activeScenario = s.copy(
                lifecycleStage = LifecycleStage.CLOSED,
                changeHistory = s.changeHistory + record
            ))
        }
        persistActiveScenario()
    }

    // ── Implementation plan ───────────────────────────────────────────────────

    fun createPlan(plan: ImplementationPlan) {
        val s = _state.value.activeScenario ?: return
        _state.update { it.copy(activeScenario = s.copy(implementationPlan = plan)) }
        persistActiveScenario()
    }

    fun updatePlan(plan: ImplementationPlan) {
        val s = _state.value.activeScenario ?: return
        _state.update { it.copy(activeScenario = s.copy(implementationPlan = plan)) }
        persistActiveScenario()
    }

    // ── Constraint assessments ────────────────────────────────────────────────

    fun upsertConstraintAssessment(assessment: ConstraintAssessment) {
        val s = _state.value.activeScenario ?: return
        val existing = s.constraintAssessments.indexOfFirst { it.id == assessment.id }
        val updated = if (existing >= 0) {
            s.constraintAssessments.toMutableList().also { it[existing] = assessment }
        } else {
            s.constraintAssessments + assessment
        }
        _state.update { it.copy(activeScenario = s.copy(constraintAssessments = updated)) }
        persistActiveScenario()
    }

    fun deleteConstraintAssessment(id: String) {
        val s = _state.value.activeScenario ?: return
        _state.update { it.copy(activeScenario = s.copy(
            constraintAssessments = s.constraintAssessments.filter { it.id != id }
        )) }
        persistActiveScenario()
    }

    fun upsertAttachment(attachment: com.drkkr.strategos.domain.model.ScenarioAttachment) {
        val s = _state.value.activeScenario ?: return
        val idx = s.attachments.indexOfFirst { it.id == attachment.id }
        val updated = if (idx >= 0) {
            s.attachments.toMutableList().also { it[idx] = attachment }
        } else {
            s.attachments + attachment
        }
        _state.update { it.copy(activeScenario = s.copy(attachments = updated)) }
        persistActiveScenario()
    }

    fun deleteAttachment(id: String) {
        val s = _state.value.activeScenario ?: return
        _state.update { it.copy(activeScenario = s.copy(
            attachments = s.attachments.filter { it.id != id }
        )) }
        persistActiveScenario()
    }

    fun beginImplementation() {
        val s = _state.value.activeScenario ?: return
        val result = LifecycleTransition.canTransition(s, LifecycleStage.IN_IMPLEMENTATION)
        if (!result.allowed) {
            _state.update { it.copy(lifecycleTransitionError = result.blockers.joinToString("\n")) }
            return
        }
        val record = ChangeRecord(
            summary = "Implementation started",
            previousVersion = s.version,
            previousLifecycleStage = s.lifecycleStage
        )
        val updated = s.copy(
            lifecycleStage = LifecycleStage.IN_IMPLEMENTATION,
            changeHistory = s.changeHistory + record
        )
        _state.update { it.copy(activeScenario = updated, lifecycleTransitionError = null) }
        viewModelScope.launch { repo.save(updated) }
    }

    fun closeScenario(reason: String) {
        val s = _state.value.activeScenario ?: return
        val record = ChangeRecord(
            summary = "Scenario closed: $reason",
            previousVersion = s.version,
            previousLifecycleStage = s.lifecycleStage,
            reason = reason
        )
        val updated = s.copy(
            lifecycleStage = LifecycleStage.CLOSED,
            changeHistory = s.changeHistory + record
        )
        _state.update { it.copy(activeScenario = updated) }
        viewModelScope.launch { repo.save(updated) }
    }
}
