package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.*
import kotlin.math.max
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────
//  Trust archetype labels (Axelrod 1984 + Press & Dyson 2012)
// ─────────────────────────────────────────────────────────────────
enum class TrustArchetype {
    COPYCAT,           // Tit-for-Tat: nice, provocable, forgiving, clear
    COPYKITTEN,        // TfT-2: forgives first defection — good under noise
    GRUDGER,           // Cooperates until first defection, never forgives
    DETECTIVE,         // Tests first, then adapts based on response
    ALWAYS_COOPERATE,  // Unconditional cooperator — exploitable
    ALWAYS_DEFECT,     // Unconditional defector — structurally unworkable
    EXTORTIONER,       // Zero-determinant: controls payoff ratio (high authority + veto)
    PAVLOV,            // Win-stay lose-switch: robust under noise
    UNKNOWN
}

// ─────────────────────────────────────────────────────────────────
//  Per-stakeholder trust analysis output
// ─────────────────────────────────────────────────────────────────
data class StakeholderTrustProfile(
    val stakeholderId: String,
    val stakeholderName: String,
    // Axelrod 4 dimensions (0.0–1.0)
    val niceness: Double,        // tendency to cooperate first
    val provocability: Double,   // speed of retaliation to defection
    val forgiveness: Double,     // recovery rate after retaliation
    val clarity: Double,         // predictability / consistency
    // Derived
    val archetype: TrustArchetype,
    // Shadow of the future (0.0 = will defect short-term, 1.0 = long-term commitment)
    val shadowOfFuture: Double,
    // Mitchell salience = power × legitimacy × urgency (derived from authority/influence + structural signals)
    val salienceScore: Double,
    // How hard to restart cooperation if broken (stakeholder-level)
    val cooperationInertia: CooperationInertia,
    // Concrete engagement prescription
    val engagementPrescription: EngagementPrescription,
    // Whether this stakeholder is a priority engagement risk
    val isHighRisk: Boolean
)

enum class CooperationInertia { LOW, MEDIUM, HIGH, PERMANENT }

enum class EngagementPrescription {
    STANDARD_RECIPROCAL,    // Copycat/Copykitten: cooperate first, respond proportionately
    GRIT_UNILATERAL,        // Grudger: make small public unilateral concession first
    CHALLENGE_PROBE,        // Detective: push back clearly on first test
    RESTRUCTURE_GAME,       // Extortioner/Always-Defect: change payoff structure
    FORMALIZE_COMMITMENT,   // Low shadow: lock in commitment before they exit
    PROTECT_FROM_EXPLOITATION // Always-Cooperate: set limits or they'll be used
}

// ─────────────────────────────────────────────────────────────────
//  Structural win-win assessment (no payoffs needed)
// ─────────────────────────────────────────────────────────────────
data class WinWinAssessment(
    val isStructurallyZeroSum: Boolean,
    val signals: List<String>,
    val cooperationPotential: Double  // 0.0–1.0
)

// ─────────────────────────────────────────────────────────────────
//  Full module output — stored in calculations["trustDynamics"]
// ─────────────────────────────────────────────────────────────────
data class TrustDynamicsOutput(
    val profiles: List<StakeholderTrustProfile>,
    val winWin: WinWinAssessment,
    // Three trust conditions (ncase.me / Axelrod)
    val repeatInteractions: Boolean,          // scenario.repeated
    val winWinPotential: Boolean,             // !isStructurallyZeroSum
    val lowMiscommunication: Boolean,         // !informationAsymmetry && < 2 Always-Defect archetype
    val overallTrustScore: Double             // 0.0–1.0
)

// ─────────────────────────────────────────────────────────────────
//  Module
// ─────────────────────────────────────────────────────────────────
class StakeholderTrustDynamicsModule : FrameworkModule {
    override val id = "stakeholder-trust-dynamics"
    override val name = "Stakeholder Trust Dynamics"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        if (model.stakeholders.isEmpty()) {
            return Applicability(0.0, listOf("No stakeholders defined."))
        }
        val score = when {
            model.stakeholders.size >= 3 && model.repeated -> 0.92
            model.stakeholders.size >= 2 && model.repeated -> 0.85
            model.stakeholders.size >= 2 -> 0.70
            else -> 0.45
        }
        val reasons = mutableListOf<String>()
        reasons += "${model.stakeholders.size} stakeholders defined."
        if (model.repeated) reasons += "Repeated interaction flag set — Axelrod dynamics fully applicable."
        if (model.informationAsymmetry) reasons += "Information asymmetry detected — miscommunication risk elevated."
        if (model.zeroSum) reasons += "Zero-sum flag set — cooperation potential structurally limited."
        return Applicability(score, reasons)
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val profiles = model.stakeholders.map { buildProfile(it, model) }
        val winWin = assessWinWin(model)
        val lowMiscom = !model.informationAsymmetry &&
            profiles.count { it.archetype == TrustArchetype.ALWAYS_DEFECT } < 2

        val overallTrust = computeOverallTrust(model, profiles, winWin, lowMiscom)

        val output = TrustDynamicsOutput(
            profiles = profiles,
            winWin = winWin,
            repeatInteractions = model.repeated,
            winWinPotential = !winWin.isStructurallyZeroSum,
            lowMiscommunication = lowMiscom,
            overallTrustScore = overallTrust
        )

        val findings = buildFindings(model, output)
        val recs = buildRecommendations(output)

        val confProfile = ConfidenceProfile(
            evidenceQuality = if (model.evidenceSources.isEmpty()) 0.35 else 0.65,
            modelCompleteness = (model.stakeholders.count { it.behavioralAssumptions.isNotEmpty() }.toDouble() /
                max(1, model.stakeholders.size)).coerceIn(0.2, 0.9),
            analyticalRobustness = if (model.repeated) 0.75 else 0.55,
            implementationReadiness = if (profiles.any { it.isHighRisk }) 0.40 else 0.70
        )

        return AnalysisResult(
            moduleId = id,
            moduleName = name,
            applicability = canAnalyze(model),
            findings = findings,
            recommendedActions = recs,
            assumptions = listOf(
                Assumption("Trust archetypes inferred from stakeholder fields; override via behavioralAssumptions."),
                Assumption("Axelrod (1984) tournament properties: Nice, Provocable, Forgiving, Clear."),
                Assumption("Mitchell et al. (1997) salience: power × legitimacy × urgency."),
                Assumption("Shadow of the future proxied from canExit, timeHorizon, and credibility."),
                Assumption("Win-win assessment is structural — no payoff matrix required.")
            ),
            calculations = mapOf("trustDynamics" to output),
            confidence = confProfile.overall(),
            visualizations = emptyList(),
            explanation = buildExplanation(output),
            calculationStatus = if (model.repeated) CalculationStatus.CALCULATED else CalculationStatus.PROXY_ESTIMATE,
            confidenceProfile = confProfile,
            frameworkCategory = FrameworkCategory.SUPPORTING
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  Profile builder
    // ─────────────────────────────────────────────────────────────
    private fun buildProfile(s: Stakeholder, model: Scenario): StakeholderTrustProfile {
        // Niceness: cooperative tendency based on goals alignment, credibility, no veto abuse
        val niceness = run {
            var v = 0.5
            if (s.credibility >= 7) v += 0.15
            if (s.credibility <= 3) v -= 0.20
            if (s.privateGoals.isNotEmpty() && s.publicGoals.isNotEmpty() &&
                s.privateGoals.any { pg -> s.publicGoals.none { it.contains(pg.take(10), ignoreCase = true) } }) v -= 0.10
            if (s.trustRelationships.isNotEmpty()) v += 0.10
            if (s.fears.size >= 3) v -= 0.10  // fearful = defensive = less cooperative first-move
            v.coerceIn(0.0, 1.0)
        }

        // Provocability: retaliation speed — higher authority + canVeto = faster, harder retaliation
        val provocability = run {
            var v = 0.4
            v += (s.authority / 10.0) * 0.3
            if (s.canVeto) v += 0.15
            if (s.fears.isNotEmpty()) v += 0.10
            v.coerceIn(0.0, 1.0)
        }

        // Forgiveness: willingness to return to cooperation after defection
        val forgiveness = run {
            var v = 0.5
            if (s.credibility >= 7) v += 0.15
            if (s.canVeto && s.authority >= 7) v -= 0.20  // high-power actors rarely back down publicly
            if (s.trustRelationships.size >= 2) v += 0.10
            if (s.coalitionsWith.isNotEmpty()) v -= 0.05  // coalition membership = collective memory
            if (s.behavioralAssumptions.any { it.text.contains("grudge", ignoreCase = true) ||
                    it.text.contains("won't forgive", ignoreCase = true) }) v -= 0.30
            v.coerceIn(0.0, 1.0)
        }

        // Clarity: how predictable / consistent this stakeholder is
        val clarity = run {
            var v = 0.5
            if (s.behavioralAssumptions.count { it.status == FactStatus.CONFIRMED } >= 2) v += 0.20
            if (s.approvalRole.isNotBlank()) v += 0.10
            if (s.informationAvailable.isEmpty()) v -= 0.15  // opaque = unpredictable
            if (s.privateGoals.isNotEmpty()) v -= 0.10  // hidden agenda = lower clarity to others
            v.coerceIn(0.0, 1.0)
        }

        val archetype = inferArchetype(s, niceness, provocability, forgiveness, clarity)

        // Shadow of the future: how much does this stakeholder value future interaction?
        val shadowOfFuture = run {
            var v = 0.6
            if (s.canExit) v -= 0.30
            if (model.timeHorizon <= 3) v -= 0.15
            if (model.timeHorizon >= 12) v += 0.15
            if (s.credibility >= 7) v += 0.10
            v.coerceIn(0.0, 1.0)
        }

        // Mitchell salience: use authority (power) + credibility as legitimacy proxy + urgency from fears/canDelay
        val salienceScore = run {
            val power = (s.authority + s.influence) / 20.0
            val legitimacy = s.credibility / 10.0
            val urgency = if (s.canDelay) 0.3 else if (s.fears.size >= 2) 0.7 else 0.5
            (power * legitimacy * urgency).coerceIn(0.0, 1.0)
        }

        val cooperationInertia = when {
            archetype == TrustArchetype.GRUDGER && s.behavioralAssumptions.isNotEmpty() -> CooperationInertia.PERMANENT
            archetype == TrustArchetype.GRUDGER -> CooperationInertia.HIGH
            archetype == TrustArchetype.ALWAYS_DEFECT -> CooperationInertia.PERMANENT
            archetype == TrustArchetype.EXTORTIONER -> CooperationInertia.HIGH
            model.informationAsymmetry && archetype == TrustArchetype.COPYCAT -> CooperationInertia.MEDIUM
            forgiveness < 0.3 -> CooperationInertia.HIGH
            else -> CooperationInertia.LOW
        }

        val engagementPrescription = when {
            archetype == TrustArchetype.GRUDGER -> EngagementPrescription.GRIT_UNILATERAL
            archetype == TrustArchetype.DETECTIVE -> EngagementPrescription.CHALLENGE_PROBE
            archetype == TrustArchetype.EXTORTIONER || archetype == TrustArchetype.ALWAYS_DEFECT ->
                EngagementPrescription.RESTRUCTURE_GAME
            shadowOfFuture < 0.35 -> EngagementPrescription.FORMALIZE_COMMITMENT
            archetype == TrustArchetype.ALWAYS_COOPERATE -> EngagementPrescription.PROTECT_FROM_EXPLOITATION
            else -> EngagementPrescription.STANDARD_RECIPROCAL
        }

        val isHighRisk = archetype in setOf(
            TrustArchetype.GRUDGER, TrustArchetype.EXTORTIONER, TrustArchetype.ALWAYS_DEFECT
        ) || shadowOfFuture < 0.30 || (cooperationInertia == CooperationInertia.PERMANENT)

        return StakeholderTrustProfile(
            stakeholderId = s.id,
            stakeholderName = s.name,
            niceness = niceness,
            provocability = provocability,
            forgiveness = forgiveness,
            clarity = clarity,
            archetype = archetype,
            shadowOfFuture = shadowOfFuture,
            salienceScore = salienceScore,
            cooperationInertia = cooperationInertia,
            engagementPrescription = engagementPrescription,
            isHighRisk = isHighRisk
        )
    }

    private fun inferArchetype(
        s: Stakeholder,
        niceness: Double, provocability: Double, forgiveness: Double, clarity: Double
    ): TrustArchetype {
        // Extortioner: high authority + veto + low forgiveness
        if (s.canVeto && s.authority >= 8 && forgiveness < 0.3) return TrustArchetype.EXTORTIONER

        // Always Defect: very low niceness + very low forgiveness
        if (niceness < 0.25 && forgiveness < 0.25) return TrustArchetype.ALWAYS_DEFECT

        // Always Cooperate: very high niceness + very high forgiveness + very low provocability
        if (niceness > 0.80 && forgiveness > 0.75 && provocability < 0.30) return TrustArchetype.ALWAYS_COOPERATE

        // Grudger: high niceness initially but near-zero forgiveness
        if (niceness > 0.55 && forgiveness < 0.20) return TrustArchetype.GRUDGER

        // Detective: medium niceness + medium forgiveness + low clarity (tests before trusting)
        if (clarity < 0.40 && niceness in 0.35..0.65) return TrustArchetype.DETECTIVE

        // Pavlov (win-stay lose-switch): medium niceness, medium forgiveness, medium provocability, high clarity
        if (clarity > 0.65 && forgiveness in 0.35..0.65 && provocability in 0.35..0.65) return TrustArchetype.PAVLOV

        // Copykitten: high niceness + high forgiveness — forgives one defection
        if (niceness > 0.65 && forgiveness > 0.55) return TrustArchetype.COPYKITTEN

        // Copycat (TfT): moderate everything, clear
        if (niceness > 0.50 && provocability > 0.40 && forgiveness > 0.35 && clarity > 0.50) return TrustArchetype.COPYCAT

        return TrustArchetype.UNKNOWN
    }

    // ─────────────────────────────────────────────────────────────
    //  Win-Win structural assessment
    // ─────────────────────────────────────────────────────────────
    private fun assessWinWin(model: Scenario): WinWinAssessment {
        val signals = mutableListOf<String>()
        var zeroSumScore = 0.0

        if (model.zeroSum) {
            signals += "Scenario explicitly flagged as zero-sum."
            zeroSumScore += 0.5
        }
        if (model.decisionRule == DecisionRule.SINGLE_EXECUTIVE) {
            signals += "Single executive decision rule — no mutual gain structure."
            zeroSumScore += 0.15
        }
        val resourceConstraints = model.constraints.count { it.kind == Constraint.Kind.RESOURCE }
        if (resourceConstraints >= 2) {
            signals += "$resourceConstraints resource constraints — allocation may be zero-sum."
            zeroSumScore += 0.10
        }
        val exitCount = model.stakeholders.count { it.canExit }
        if (exitCount >= 2) {
            signals += "$exitCount stakeholders can exit — defection is structurally rational."
            zeroSumScore += 0.10
        }
        val complianceOnly = model.constraints.isNotEmpty() &&
            model.constraints.all { it.kind in setOf(Constraint.Kind.LEGAL, Constraint.Kind.SAFETY) }
        if (complianceOnly) {
            signals += "All constraints are legal/safety — compliance costs may fall asymmetrically."
            zeroSumScore += 0.05
        }

        val isStructurallyZeroSum = zeroSumScore >= 0.40
        val cooperationPotential = (1.0 - zeroSumScore.coerceAtMost(1.0)).coerceIn(0.0, 1.0)

        if (signals.isEmpty()) signals += "No structural zero-sum signals detected."

        return WinWinAssessment(isStructurallyZeroSum, signals, cooperationPotential)
    }

    // ─────────────────────────────────────────────────────────────
    //  Overall trust score
    // ─────────────────────────────────────────────────────────────
    private fun computeOverallTrust(
        model: Scenario,
        profiles: List<StakeholderTrustProfile>,
        winWin: WinWinAssessment,
        lowMiscom: Boolean
    ): Double {
        var v = 0.5
        if (model.repeated) v += 0.15
        if (!winWin.isStructurallyZeroSum) v += 0.10
        if (lowMiscom) v += 0.10
        val avgNiceness = profiles.map { it.niceness }.average()
        v += (avgNiceness - 0.5) * 0.20
        val highRiskCount = profiles.count { it.isHighRisk }
        v -= highRiskCount * 0.08
        return v.coerceIn(0.0, 1.0)
    }

    // ─────────────────────────────────────────────────────────────
    //  Findings
    // ─────────────────────────────────────────────────────────────
    private fun buildFindings(model: Scenario, output: TrustDynamicsOutput): List<Finding> {
        val findings = mutableListOf<Finding>()

        // Trust conditions summary
        val conditionsMet = listOf(output.repeatInteractions, output.winWinPotential, output.lowMiscommunication).count { it }
        val conditionSeverity = when (conditionsMet) {
            3 -> Finding.Severity.INFO
            2 -> Finding.Severity.NOTE
            1 -> Finding.Severity.WARNING
            else -> Finding.Severity.CRITICAL
        }
        findings += Finding(
            "Trust conditions: $conditionsMet/3 met",
            listOf(
                "Repeat interactions: ${if (output.repeatInteractions) "YES" else "NO — one-shot game limits cooperation incentive"}",
                "Win-win potential: ${if (output.winWinPotential) "YES" else "NO — zero-sum structure prevents sustained cooperation"}",
                "Low miscommunication: ${if (output.lowMiscommunication) "YES" else "NO — noise risk elevates false defection"}"
            ).joinToString("\n"),
            conditionSeverity
        )

        // Per-stakeholder archetype findings
        output.profiles.forEach { p ->
            when {
                p.archetype == TrustArchetype.EXTORTIONER -> findings += Finding(
                    "${p.stakeholderName}: Extortioner (zero-determinant leverage)",
                    "This stakeholder can unilaterally set payoff ratios — veto power combined with low forgiveness creates structural exploitation risk. Cooperation cannot be sustained through normal reciprocity. Restructure the game (side payments, issue linkage, or reduce their veto scope) before negotiating the main issue.",
                    Finding.Severity.CRITICAL
                )
                p.archetype == TrustArchetype.GRUDGER -> findings += Finding(
                    "${p.stakeholderName}: Grudger — permanent memory of defection",
                    "This stakeholder cooperates until the first defection, then never returns. If any prior incident has poisoned the relationship, standard TfT negotiation will fail. Use GRIT: make a small, named, public unilateral concession that directly addresses their historical grievance before requesting commitment. Salience: ${(p.salienceScore * 100).toInt()}%.",
                    if (p.salienceScore > 0.5) Finding.Severity.CRITICAL else Finding.Severity.WARNING
                )
                p.archetype == TrustArchetype.ALWAYS_DEFECT -> findings += Finding(
                    "${p.stakeholderName}: Always-Defect — cooperation impossible without structural change",
                    "No reciprocal strategy can sustain cooperation with an unconditional defector. Only structural interventions work: change who holds decision rights, remove their exit option, or create independent enforcement. TfT escalation will only lock in mutual defection.",
                    Finding.Severity.CRITICAL
                )
                p.archetype == TrustArchetype.DETECTIVE -> findings += Finding(
                    "${p.stakeholderName}: Detective — tests before committing",
                    "This stakeholder probes with an initial defection or unreasonable demand to classify your strategy. If you concede unconditionally, they defect permanently. Push back clearly on the first test — then cooperate if they normalize. Shadow of future: ${(p.shadowOfFuture * 100).toInt()}%.",
                    Finding.Severity.WARNING
                )
                p.shadowOfFuture < 0.30 -> findings += Finding(
                    "${p.stakeholderName}: Short shadow of the future",
                    "This stakeholder has low incentive to sustain long-term cooperation (canExit=${model.stakeholders.find { it.id == p.stakeholderId }?.canExit}, short time horizon). Rational defection is possible even for otherwise cooperative archetypes. Formalize commitment now: documented stake, MOU, or approval stage before they disengage.",
                    Finding.Severity.WARNING
                )
                p.archetype == TrustArchetype.COPYKITTEN || p.archetype == TrustArchetype.PAVLOV -> findings += Finding(
                    "${p.stakeholderName}: ${p.archetype.name} — self-correcting under noise",
                    "This strategy is more robust than TfT in environments with miscommunication. A single defection won't spiral into permanent mutual defection. Standard consistent cooperation is sufficient; no special intervention needed. Salience: ${(p.salienceScore * 100).toInt()}%.",
                    Finding.Severity.INFO
                )
                else -> {}
            }
        }

        // Win-win signals
        if (output.winWin.isStructurallyZeroSum) {
            findings += Finding(
                "Structural zero-sum: cooperation cannot be sustained",
                output.winWin.signals.joinToString("\n"),
                Finding.Severity.CRITICAL
            )
        }

        // Miscommunication risk
        if (!output.lowMiscommunication) {
            findings += Finding(
                "Miscommunication risk elevated",
                "Information asymmetry or multiple unconditional-defector archetypes increase the probability of mistaken defection cascades. Tit-for-Tat spirals are likely once started. Copykitten or GRIT-style engagement reduces this risk.",
                Finding.Severity.WARNING
            )
        }

        // Cooperation inertia pairs (most critical)
        val permanentPairs = output.profiles.filter { it.cooperationInertia == CooperationInertia.PERMANENT }
        if (permanentPairs.isNotEmpty()) {
            findings += Finding(
                "Permanent cooperation inertia: ${permanentPairs.joinToString(", ") { it.stakeholderName }}",
                "Once cooperation breaks with these stakeholders, it cannot self-repair. External intervention (mediator, governance escalation, redesigned incentive structure) is required before any recommended action can proceed.",
                Finding.Severity.CRITICAL
            )
        }

        return findings
    }

    // ─────────────────────────────────────────────────────────────
    //  Recommendations
    // ─────────────────────────────────────────────────────────────
    private fun buildRecommendations(output: TrustDynamicsOutput): List<RecommendedAction> {
        val recs = mutableListOf<RecommendedAction>()

        output.profiles.forEach { p ->
            val prescription = when (p.engagementPrescription) {
                EngagementPrescription.GRIT_UNILATERAL ->
                    "Apply GRIT (Graduated Reciprocation in Tension-Reduction): make one small, visible, named unilateral concession that directly addresses ${p.stakeholderName}'s stated historical grievance — then wait for reciprocation before escalating. Do not ask for a commitment in return."
                EngagementPrescription.CHALLENGE_PROBE ->
                    "Counter ${p.stakeholderName}'s first probe clearly. If they open with an unreasonable demand or defection-like move, push back proportionately once. If they normalize after that, cooperate consistently. Unconditional early concession will be read as exploitable."
                EngagementPrescription.RESTRUCTURE_GAME ->
                    "Normal negotiation will not work with ${p.stakeholderName}. Identify a side payment, independent benefit, or issue linkage that changes their payoff structure before engaging on the main decision. If veto scope can be formally reduced via governance, do that first."
                EngagementPrescription.FORMALIZE_COMMITMENT ->
                    "Formalize ${p.stakeholderName}'s stake in the outcome before they exit the interaction. Options: documented approval stage, MOU, explicit stakeholder recognition in the decision record. Short shadow of the future means verbal commitments carry low weight."
                EngagementPrescription.PROTECT_FROM_EXPLOITATION ->
                    "${p.stakeholderName} cooperates unconditionally — protect them from exploitation by other stakeholders. Set explicit limits on what can be asked of them and document agreed boundaries. Their compliance cannot be assumed to signal agreement."
                EngagementPrescription.STANDARD_RECIPROCAL ->
                    "Standard approach: cooperate first, respond proportionately to defection once, then return to cooperation. ${p.stakeholderName}'s archetype (${p.archetype.name}) is self-stabilizing under consistent treatment."
            }
            recs += RecommendedAction(
                forStakeholderId = p.stakeholderId,
                label = "${p.stakeholderName}: ${p.engagementPrescription.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}",
                rationale = prescription,
                lens = if (p.isHighRisk) RecommendedAction.Lens.RISK_AVERSE else RecommendedAction.Lens.RELATIONSHIP
            )
        }

        // Structural win-win rec
        if (output.winWin.isStructurallyZeroSum) {
            recs += RecommendedAction(
                forStakeholderId = null,
                label = "Restructure zero-sum dynamic before proceeding",
                rationale = "The scenario's incentive structure prevents sustained cooperation. Consider: unbundling the decision into separable issues each party values differently, introducing a shared upside that isn't currently in the payoff structure, or bringing in a neutral facilitator to identify hidden gains.",
                lens = RecommendedAction.Lens.LONG_TERM
            )
        }

        if (!output.repeatInteractions) {
            recs += RecommendedAction(
                forStakeholderId = null,
                label = "Convert one-shot to repeated game",
                rationale = "Single-interaction games eliminate TfT incentives. Frame this as the first of an ongoing relationship, establish post-decision review meetings, or tie this decision explicitly to future interactions where continued cooperation pays.",
                lens = RecommendedAction.Lens.LONG_TERM
            )
        }

        return recs
    }

    private fun buildExplanation(output: TrustDynamicsOutput): String {
        val conditionsMet = listOf(output.repeatInteractions, output.winWinPotential, output.lowMiscommunication).count { it }
        val highRisk = output.profiles.filter { it.isHighRisk }.map { it.stakeholderName }
        return buildString {
            append("Trust dynamics analysis (Axelrod 1984, Mitchell et al. 1997). ")
            append("$conditionsMet/3 trust conditions met. ")
            if (highRisk.isNotEmpty()) append("High-risk stakeholders: ${highRisk.joinToString(", ")}. ")
            append("Overall cooperation potential: ${(output.overallTrustScore * 100).toInt()}%. ")
            if (output.winWin.isStructurallyZeroSum) append("STRUCTURAL ZERO-SUM — cooperation requires game redesign. ")
            else append("Win-win structure intact — reciprocal strategies applicable.")
        }
    }
}
