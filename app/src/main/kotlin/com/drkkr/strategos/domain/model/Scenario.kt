package com.drkkr.strategos.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class FactStatus { CONFIRMED, USER_ESTIMATE, AI_INFERRED, UNKNOWN }

@Serializable
enum class ScenarioStatus { DRAFT, ACTIVE, ARCHIVED, COMPLETED }

@Serializable
enum class StakeholderType {
    INDIVIDUAL, DEPARTMENT, INSTITUTION, REGULATOR, PUBLIC, PATIENT_GROUP,
    VENDOR, MEDIA, POLITICAL_ACTOR, COMMITTEE, UNION, COMMUNITY, OTHER
}

@Serializable
enum class LegalStatus { PERMITTED, RESTRICTED, PROHIBITED, UNCLEAR }

@Serializable
enum class ConstraintStatus { SATISFIED, UNKNOWN, VIOLATED, NOT_APPLICABLE }

@Serializable
data class ConstraintAssessment(
    val id: String = UUID.randomUUID().toString(),
    val constraintId: String,
    val actionId: String,
    val policyOptionId: String = "",
    val status: ConstraintStatus = ConstraintStatus.UNKNOWN,
    val rationale: String = "",
    val evidenceSourceIds: List<String> = emptyList(),
    val assessedBy: AssessedBy = AssessedBy.USER,
    val assessor: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val assessedAt: Long = System.currentTimeMillis(),
    val expiryDate: Long = 0L,
    val reviewDate: Long = 0L
) {
    @Serializable
    enum class AssessedBy { USER, RULE, MODEL }
}

@Serializable
enum class DecisionRule {
    SIMPLE_MAJORITY,
    SUPERMAJORITY,
    CONSENSUS,
    SINGLE_EXECUTIVE,
    BOARD_APPROVAL,
    REGULATOR_APPROVAL,
    SEQUENTIAL_APPROVAL,
    FORMAL_VETO,
    CUSTOM_WEIGHTED,
    MULTI_STAGE_GOVERNANCE
}

@Serializable
data class BehavioralAssumption(
    val text: String,
    val status: FactStatus = FactStatus.USER_ESTIMATE,
    val evidenceNote: String = "",
    val evidenceStrength: Int = 0,   // 0 = not assessed, 1–5
    val affectedActionId: String? = null,
    val stakeholderId: String? = null,
    val evidenceSourceIds: List<String> = emptyList(),
    val reviewDate: Long = 0L
)

@Serializable
enum class GameRole { PLAYER, INFLUENCER }

@Serializable
data class Stakeholder(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: String = "",
    val type: StakeholderType = StakeholderType.INDIVIDUAL,
    val authority: Int = 0,       // formal decision rights 0–10
    val influence: Int = 0,       // informal influence 0–10
    val incentives: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
    val publicGoals: List<String> = emptyList(),
    val privateGoals: List<String> = emptyList(),
    val resourcesControlled: List<String> = emptyList(),
    val informationAvailable: List<String> = emptyList(),
    val canVeto: Boolean = false,
    val canExit: Boolean = false,
    val canDelay: Boolean = false,
    val coalitionsWith: List<String> = emptyList(),   // stakeholder ids
    val credibility: Int = 5,     // commitment credibility 0–10
    val approvalRole: String = "",                     // e.g. "Final sign-off", "Recommender"
    val fears: List<String> = emptyList(),             // losses / risks they want to avoid
    val trustRelationships: List<String> = emptyList(),// stakeholder ids they trust
    // BLUEPRINT-QUESTION: §7.2 wants a numeric trust baseline on the counterpart but the model only has the id list above. 0.0..1.0, null = never measured.
    val trustBaseline: Double? = null,
    // Phase F.6: Bayesian responsiveness priors, learned turn by turn from what the user reports (BayesianUpdater),
    // never written by the model directly. 0.10..0.90 range, null = not yet learned (the engine uses its own defaults).
    val probSoftensOnRepair: Double? = null,
    val probRespectsBoundary: Double? = null,
    val probEscalatesOnReactive: Double? = null,
    val dependencies: List<String> = emptyList(),      // what they depend on others for
    val behavioralAssumptions: List<BehavioralAssumption> = emptyList(),
    val notes: String = "",
    val gameRole: GameRole = GameRole.PLAYER,
    val factStatus: FactStatus = FactStatus.USER_ESTIMATE,
    val provenanceNote: String = ""      // AI rationale or source quote
)

@Serializable
data class Action(
    val id: String = UUID.randomUUID().toString(),
    val stakeholderId: String,
    val label: String,
    val description: String = "",
    val cost: Double = 0.0,
    val timeCost: Int = 0,        // days
    val reversible: Boolean = true,
    val legalStatus: LegalStatus = LegalStatus.UNCLEAR,
    val publicVisibility: Boolean = true,
    val implementationDifficulty: Int = 3,  // 1–5
    val preconditions: List<String> = emptyList(),
    val likelyReactions: Map<String, String> = emptyMap(),  // stakeholderId -> reaction
    val factStatus: FactStatus = FactStatus.USER_ESTIMATE,
    val provenanceNote: String = ""      // AI rationale or source quote
)

@Serializable
data class EvidenceSource(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val kind: Kind = Kind.EXPERT_OPINION,
    val url: String = "",
    val quality: Int = 3   // 1–5
) {
    @Serializable enum class Kind { RCT, SYSTEMATIC_REVIEW, OBSERVATIONAL, EXPERT_OPINION, POLICY_DOCUMENT, INTERNAL_DATA, OTHER }
}

@Serializable
data class Constraint(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val kind: Kind,
    val description: String = "",
    val factStatus: FactStatus = FactStatus.USER_ESTIMATE,
    val provenanceNote: String = ""      // AI rationale or source quote
) {
    @Serializable enum class Kind { LEGAL, ETHICAL, SAFETY, POLICY, PHYSICAL, RESOURCE, USER_HARD }
}

@Serializable
data class Belief(
    val label: String,
    val probability: Double,
    val status: FactStatus = FactStatus.USER_ESTIMATE,
    val note: String = ""
)

@Serializable
data class PayoffEntry(
    val stakeholderId: String,
    val actionProfile: Map<String, String>, // stakeholderId -> actionId
    val payoff: MultiPayoff,
    val factStatus: FactStatus = FactStatus.USER_ESTIMATE,
    val provenanceNote: String = ""      // AI rationale or source quote
)

@Serializable
data class SequenceNode(
    val id: String = UUID.randomUUID().toString(),
    val stakeholderId: String?,     // null = chance node
    val actionLabel: String,
    val probability: Double = 1.0,
    val terminalPayoffs: Map<String, MultiPayoff> = emptyMap(),
    val children: List<SequenceNode> = emptyList()
)

@Serializable
data class Scenario(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val language: String = "en",
    val status: ScenarioStatus = ScenarioStatus.DRAFT,
    val tags: List<String> = emptyList(),
    val decisionOwner: String = "",
    val location: String = "",
    val institution: String = "",
    val stakes: String = "",
    val timeHorizonLabel: String = "",
    val stakeholders: List<Stakeholder> = emptyList(),
    val actions: List<Action> = emptyList(),
    val payoffs: List<PayoffEntry> = emptyList(),
    val constraints: List<Constraint> = emptyList(),
    val beliefs: List<Belief> = emptyList(),
    val evidenceSources: List<EvidenceSource> = emptyList(),
    val gameTree: SequenceNode? = null,
    val constraintAssessments: List<ConstraintAssessment> = emptyList(),
    val timeHorizon: Int = 1,
    val repeated: Boolean = false,
    val informationAsymmetry: Boolean = false,
    val zeroSum: Boolean = false,
    val decisionRule: DecisionRule = DecisionRule.SIMPLE_MAJORITY,
    val weights: Map<PayoffDimension, Double> = defaultWeights(),
    val assumptionLog: List<AssumptionEntry> = emptyList(),
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Phase 4: governance lifecycle
    val lifecycleStage: LifecycleStage = LifecycleStage.DRAFT,
    val selectedPolicyOption: PolicyOption? = null,
    val snapshotId: String? = null,
    val approvalStages: List<ApprovalStage> = emptyList(),
    val implementationPlan: ImplementationPlan? = null,
    val changeHistory: List<ChangeRecord> = emptyList(),
    val attachments: List<ScenarioAttachment> = emptyList()
) {
    fun actionsFor(sid: String) = actions.filter { it.stakeholderId == sid }

    /** Stakeholders who choose actions and appear in the payoff matrix. */
    fun players(): List<Stakeholder> = stakeholders.filter { it.gameRole == GameRole.PLAYER }

    /** 0–100 model completeness score used by health card */
    fun completenessScore(): Int {
        var score = 0
        if (title.isNotBlank()) score += 10
        if (description.length > 30) score += 10
        if (decisionOwner.isNotBlank()) score += 5
        if (stakeholders.size >= 2) score += 15
        if (actions.size >= 2) score += 15
        if (payoffs.isNotEmpty()) score += 15
        if (constraints.isNotEmpty()) score += 10
        if (evidenceSources.isNotEmpty()) score += 5
        if (beliefs.isNotEmpty()) score += 5
        if (stakes.isNotBlank()) score += 5
        if (assumptionLog.isNotEmpty()) score += 5
        return score.coerceAtMost(100)
    }

    /** Evidence quality 0–100 based on evidence sources */
    fun evidenceQuality(): Int {
        if (evidenceSources.isEmpty()) return 0
        val avg = evidenceSources.map { it.quality }.average()
        return ((avg / 5.0) * 100).toInt().coerceIn(0, 100)
    }
    companion object {
        fun defaultWeights(): Map<PayoffDimension, Double> = mapOf(
            PayoffDimension.SAFETY to 3.0,
            PayoffDimension.ETHICS to 3.0,
            PayoffDimension.LEGALITY to 3.0,
            PayoffDimension.COST to 1.0,
            PayoffDimension.REPUTATION to 1.5,
            PayoffDimension.RELATIONSHIPS to 1.5,
            PayoffDimension.WELLBEING to 2.5,
            PayoffDimension.EFFICIENCY to 1.0,
            PayoffDimension.PUBLIC_TRUST to 2.0,
            PayoffDimension.LONG_TERM_VALUE to 2.0
        )
    }
}

@Serializable
data class AssumptionEntry(
    val label: String,
    val status: FactStatus,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
