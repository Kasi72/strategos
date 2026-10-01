package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.*

enum class ResponseMode(val label: String, val description: String) {
    SUPPORT("Support", "Actively cooperates and contributes resources"),
    CONDITIONAL_SUPPORT("Conditional support", "Cooperates if safeguards, status, resources, or recognition are provided"),
    PASSIVE_COMPLIANCE("Passive compliance", "Does the minimum required — no active opposition but no enthusiasm"),
    DELAY("Delay", "Requests more evidence, procedures, or approvals — slows implementation"),
    QUIET_RESISTANCE("Quiet resistance", "Appears compliant while informally undermining implementation"),
    OPEN_OPPOSITION("Open opposition", "Publicly challenges the decision — reputational and political risk"),
    ESCALATION("Escalation", "Uses regulator, media, union, court, political channel, or formal grievance"),
    COALITION_BUILDING("Coalition building", "Recruits allies to shift the power balance against the decision"),
    STRATEGIC_EXIT("Strategic exit", "Withdraws resources, participation, or legitimacy from the process"),
    REPAIR("Repair", "Accepts short-term costs to restore trust and cooperation")
}

data class StakeholderResponse(
    val stakeholder: Stakeholder,
    val action: Action,
    val mode: ResponseMode,
    val confidence: Double,
    val rationale: String,
    val behavioralHypotheses: List<String>
)

class BehavioralResponseModule : FrameworkModule {
    override val id = "behavioral-response"
    override val name = "Behavioural Response Analysis"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        if (model.stakeholders.size < 2) return Applicability(0.0, listOf("Need ≥2 stakeholders."))
        if (model.actions.isEmpty()) return Applicability(0.0, listOf("No actions to analyse."))
        return Applicability(
            0.88,
            listOf(
                "Maps each stakeholder to a predicted behavioural response mode (Support → Escalation) for each proposed action.",
                "Uses authority, influence, veto power, exit options, credibility, and coalition data.",
                "Generates falsifiable behavioural hypotheses with confidence ratings.",
                "Does not diagnose personalities — produces editable, evidence-linked predictions."
            )
        )
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()
        val allResponses = mutableListOf<StakeholderResponse>()

        // For each action by the decision owner (or all actions if no owner set)
        val ownerActions = if (model.decisionOwner.isNotBlank()) {
            val ownerStakeholder = model.stakeholders.firstOrNull {
                it.name.contains(model.decisionOwner, ignoreCase = true) ||
                it.role.contains(model.decisionOwner, ignoreCase = true)
            }
            if (ownerStakeholder != null) model.actionsFor(ownerStakeholder.id) else model.actions.take(5)
        } else model.actions.take(5)

        if (ownerActions.isEmpty()) {
            findings += Finding("No decision-owner actions found", "Assign actions to the decision owner stakeholder to generate behavioural forecasts.", Finding.Severity.NOTE)
        }

        for (action in ownerActions.take(4)) {
            val actionAuthor = model.stakeholders.firstOrNull { it.id == action.stakeholderId }
            val respondents = model.stakeholders.filter { it.id != action.stakeholderId }

            val responses = respondents.map { s -> predictResponse(s, action, actionAuthor, model) }
            allResponses += responses

            val escalators = responses.filter { it.mode in listOf(ResponseMode.ESCALATION, ResponseMode.STRATEGIC_EXIT, ResponseMode.OPEN_OPPOSITION) }
            val supporters = responses.filter { it.mode in listOf(ResponseMode.SUPPORT, ResponseMode.CONDITIONAL_SUPPORT) }
            val resisters = responses.filter { it.mode in listOf(ResponseMode.QUIET_RESISTANCE, ResponseMode.COALITION_BUILDING, ResponseMode.DELAY) }

            findings += Finding(
                "\"${action.label}\" — ${supporters.size} support, ${resisters.size} resist, ${escalators.size} escalate",
                buildString {
                    append("Stakeholder response forecast:\n")
                    for (r in responses) {
                        append("• ${r.stakeholder.name} [${r.mode.label}, ${(r.confidence * 100).toInt()}% confidence]: ${r.rationale}\n")
                        if (r.behavioralHypotheses.isNotEmpty())
                            append("  Hypotheses: ${r.behavioralHypotheses.joinToString(" | ")}\n")
                    }
                },
                when {
                    escalators.size >= 2 -> Finding.Severity.CRITICAL
                    escalators.size == 1 || resisters.size >= respondents.size / 2 -> Finding.Severity.WARNING
                    supporters.size >= respondents.size / 2 -> Finding.Severity.INFO
                    else -> Finding.Severity.NOTE
                }
            )

            // Mitigation recommendations for high-risk responses
            for (r in escalators) {
                val mitigation = mitigationFor(r)
                if (mitigation.isNotEmpty()) {
                    recs += RecommendedAction(
                        r.stakeholder.id, mitigation,
                        "Mitigation for ${r.stakeholder.name}'s predicted ${r.mode.label} response to '${action.label}'. Rationale: ${r.rationale}",
                        lens = RecommendedAction.Lens.RELATIONSHIP
                    )
                }
            }
        }

        // Cross-action pattern summary
        val escalationCount = allResponses.count { it.mode == ResponseMode.ESCALATION }
        val exitCount = allResponses.count { it.mode == ResponseMode.STRATEGIC_EXIT }
        val coalitionBuildingPairs = model.stakeholders.filter { it.coalitionsWith.isNotEmpty() }

        if (escalationCount > 0) {
            findings += Finding(
                "Escalation risk: $escalationCount predicted escalation response(s) across all actions",
                "Escalation forecasts indicate stakeholders who may use external channels (regulators, media, unions, legal) to resist the decision. These are the highest-priority relationships to manage proactively.",
                Finding.Severity.CRITICAL
            )
        }
        if (exitCount > 0) {
            findings += Finding(
                "Strategic exit risk: $exitCount stakeholder(s) may withdraw resources or legitimacy",
                "Exit is often irreversible — once a key stakeholder withdraws, bringing them back requires concessions that may set problematic precedents. Address exit risks before announcing decisions.",
                Finding.Severity.WARNING
            )
        }
        if (coalitionBuildingPairs.isNotEmpty()) {
            findings += Finding(
                "Existing coalitions detected — monitor for counter-coalition formation",
                "The following stakeholders have declared coalition links: ${coalitionBuildingPairs.joinToString("; ") { s -> "${s.name} ↔ ${s.coalitionsWith.mapNotNull { id -> model.stakeholders.firstOrNull { it.id == id }?.name }.joinToString(", ")}" }}. Under resistance, these links may activate.",
                Finding.Severity.WARNING
            )
        }

        // Behavioural hypothesis summary
        val allHypotheses = allResponses.flatMap { it.behavioralHypotheses }.distinct()
        if (allHypotheses.isNotEmpty()) {
            findings += Finding(
                "Behavioural hypotheses (editable — each requires evidence source)",
                allHypotheses.joinToString("\n") { "• $it" },
                Finding.Severity.NOTE
            )
        }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Response modes are probabilistic predictions, not diagnoses. Each hypothesis should be verified against direct evidence."),
                Assumption("Authority differential (action author vs respondent) is the primary predictor; influence, veto power, and exit options modify the response."),
                Assumption("likelyReactions data on Action overrides inferred responses when present."),
                Assumption("Behavioural hypotheses use population-level patterns — individual variance may be significant.")
            ),
            calculations = mapOf(
                "actionsAnalysed" to ownerActions.take(4).size,
                "totalResponses" to allResponses.size,
                "escalationCount" to escalationCount,
                "supportCount" to allResponses.count { it.mode in listOf(ResponseMode.SUPPORT, ResponseMode.CONDITIONAL_SUPPORT) },
                "resistanceCount" to allResponses.count { it.mode in listOf(ResponseMode.QUIET_RESISTANCE, ResponseMode.DELAY, ResponseMode.OPEN_OPPOSITION) }
            ),
            confidence = 0.60,
            visualizations = emptyList(),
            explanation = "Behavioural response forecast: ${allResponses.count { it.mode == ResponseMode.SUPPORT }} support, ${allResponses.count { it.mode in listOf(ResponseMode.QUIET_RESISTANCE, ResponseMode.DELAY) }} passive resistance, ${escalationCount + exitCount} escalation/exit risk(s). Confidence rated 60% — behavioural predictions require direct evidence to validate."
        )
    }

    private fun predictResponse(
        stakeholder: Stakeholder,
        action: Action,
        actionAuthor: Stakeholder?,
        model: Scenario
    ): StakeholderResponse {
        val hypotheses = mutableListOf<String>()

        // Check if explicit reaction is registered on the action
        val explicitReaction = action.likelyReactions[stakeholder.id]

        val authorAuthority = actionAuthor?.authority ?: 5
        val authorityGap = authorAuthority - stakeholder.authority  // positive = author has more authority
        val authorInfluence = actionAuthor?.influence ?: 5
        val influenceGap = authorInfluence - stakeholder.influence   // positive = author has more influence

        // Detect potential conflict signals
        val isNotTheirAction = action.stakeholderId != stakeholder.id
        val isPublicAction = action.publicVisibility
        val mediaOrPublic = stakeholder.type in listOf(StakeholderType.MEDIA, StakeholderType.PUBLIC, StakeholderType.REGULATOR)
        val hasVeto = stakeholder.canVeto
        val canLeave = stakeholder.canExit
        val canSlowDown = stakeholder.canDelay
        val hasAllies = stakeholder.coalitionsWith.isNotEmpty()
        val highInfluence = stakeholder.influence >= 7
        val lowAuthority = stakeholder.authority <= 3
        val highCredibility = stakeholder.credibility >= 7

        // Infer conflict level 0..1
        val conflictSignals = mutableListOf<Double>()
        if (isNotTheirAction && authorityGap < 0) conflictSignals += 0.6  // author has less authority
        if (mediaOrPublic && !isPublicAction) conflictSignals += 0.5  // non-public action involving scrutiny actors
        if (stakeholder.type == StakeholderType.UNION && action.label.contains("cost", ignoreCase = true)) conflictSignals += 0.4
        if (stakeholder.type == StakeholderType.REGULATOR) conflictSignals += 0.2  // regulators always scrutinise
        // Influence-gap dimension: informal power threat when author outflanks via influence despite low formal authority
        if (influenceGap > 3 && authorityGap < 0) conflictSignals += 0.45  // high influence + low authority = informal power threat to stakeholder
        if (influenceGap < -3 && authorityGap > 0) conflictSignals += 0.25  // low-influence action may lack grassroots support
        val conflictLevel = conflictSignals.sum().coerceIn(0.0, 1.0)

        // Generate behavioural hypotheses
        if (hasVeto && conflictLevel > 0.3) hypotheses += "Likely to resist loss of authority — veto power available"
        if (canSlowDown && conflictLevel > 0.2) hypotheses += "Likely to delay under ambiguity — procedural objections possible"
        if (hasAllies && conflictLevel > 0.4) hypotheses += "May activate coalition to shift power balance"
        if (lowAuthority && highInfluence) hypotheses += "High preference for procedural fairness — bypassing formal channels may trigger open opposition"
        if (stakeholder.type == StakeholderType.MEDIA) hypotheses += "Sensitive to public reputation and information access"
        if (stakeholder.type == StakeholderType.UNION) hypotheses += "Will prioritise member interests — concessions on conditions or recognition may unlock cooperation"
        if (highCredibility && authorityGap > 0) hypotheses += "May cooperate if given visible ownership or co-authorship of the decision"
        if (influenceGap > 3 && authorityGap < 0) hypotheses += "Author's high informal influence (${authorInfluence}/10) despite lower authority may be perceived as a power-base threat — this can trigger pre-emptive resistance even before conflict manifests"
        if (influenceGap < -3) hypotheses += "Action author has lower influence (${authorInfluence}/10) — action may lack the informal backing needed for sustained implementation; stakeholder may use delay to let it run out of momentum"

        // Determine response mode and confidence
        val (mode, confidence, rationale) = when {
            explicitReaction != null -> Triple(
                inferModeFromText(explicitReaction),
                0.75,
                "Explicit reaction registered: \"$explicitReaction\""
            )
            !isNotTheirAction -> Triple(ResponseMode.SUPPORT, 0.95, "Own action — unconditional support assumed")
            conflictLevel >= 0.7 && canLeave -> Triple(ResponseMode.STRATEGIC_EXIT, 0.65, "High conflict + exit option available — may withdraw resources or legitimacy")
            conflictLevel >= 0.6 && hasVeto -> Triple(ResponseMode.ESCALATION, 0.60, "High conflict + veto power — likely formal challenge or external escalation")
            conflictLevel >= 0.5 && highInfluence && !hasVeto -> Triple(ResponseMode.OPEN_OPPOSITION, 0.55, "High influence without formal veto — public opposition is the lever available")
            conflictLevel >= 0.4 && hasAllies -> Triple(ResponseMode.COALITION_BUILDING, 0.55, "Moderate conflict + existing alliances — may recruit allies before visible resistance")
            conflictLevel >= 0.3 && canSlowDown -> Triple(ResponseMode.DELAY, 0.65, "Procedural delay is available and preferred — low-risk resistance strategy")
            conflictLevel >= 0.2 && !hasVeto && !canLeave -> Triple(ResponseMode.QUIET_RESISTANCE, 0.50, "Limited formal options — most likely informal, non-visible resistance")
            conflictLevel < 0.2 && highCredibility -> Triple(ResponseMode.CONDITIONAL_SUPPORT, 0.70, "Low conflict, high credibility — cooperation expected if role is acknowledged")
            conflictLevel < 0.1 && authorityGap >= 0 -> Triple(ResponseMode.SUPPORT, 0.72, "Low conflict, author has sufficient authority — compliance expected")
            else -> Triple(ResponseMode.PASSIVE_COMPLIANCE, 0.55, "Moderate authority gap — minimal active engagement; will comply without enthusiasm")
        }

        return StakeholderResponse(stakeholder, action, mode, confidence, rationale, hypotheses)
    }

    private fun inferModeFromText(text: String): ResponseMode {
        val lower = text.lowercase()
        return when {
            "escalat" in lower || "legal" in lower || "report" in lower || "regulat" in lower -> ResponseMode.ESCALATION
            "resist" in lower || "oppose" in lower || "reject" in lower || "challeng" in lower -> ResponseMode.OPEN_OPPOSITION
            "exit" in lower || "withdraw" in lower || "resign" in lower || "leave" in lower -> ResponseMode.STRATEGIC_EXIT
            "delay" in lower || "defer" in lower || "evidence" in lower || "review" in lower -> ResponseMode.DELAY
            "coalition" in lower || "allies" in lower || "alliance" in lower -> ResponseMode.COALITION_BUILDING
            "support" in lower || "cooperat" in lower || "agree" in lower -> ResponseMode.SUPPORT
            "conditional" in lower || "if" in lower || "provid" in lower -> ResponseMode.CONDITIONAL_SUPPORT
            else -> ResponseMode.PASSIVE_COMPLIANCE
        }
    }

    private fun mitigationFor(response: StakeholderResponse): String = when (response.mode) {
        ResponseMode.ESCALATION -> "Pre-emptive engagement: brief ${response.stakeholder.name} before announcement — offer formal consultation role"
        ResponseMode.STRATEGIC_EXIT -> "Retention strategy: identify what ${response.stakeholder.name} needs to stay — likely autonomy, recognition, or resource guarantee"
        ResponseMode.OPEN_OPPOSITION -> "Public alignment: acknowledge ${response.stakeholder.name}'s concerns publicly; create visible co-design opportunity"
        ResponseMode.COALITION_BUILDING -> "Coalition counter-strategy: engage ${response.stakeholder.name}'s potential allies before they are recruited to resistance"
        else -> ""
    }
}
