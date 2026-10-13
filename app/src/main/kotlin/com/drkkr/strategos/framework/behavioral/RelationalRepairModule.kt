package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import kotlin.math.ln
import kotlin.math.pow

// ─────────────────────────────────────────────────────────────────
//  Public data types — used by both the engine and the UI what-if panel
// ─────────────────────────────────────────────────────────────────
data class RepairInputs(
    val S: Double,             // self-authority weight (0–1)
    val q: Double,             // renewal probability per period (0–1)
    val H: Double,             // current shared-history value (0–1)
    val eH: Double,            // expected future history gain this period (0–1)
    val R: Double,             // renewal cost (0–1)
    val A: Double,             // alternative pull (0–1)
    val Fe: Double,            // reconnection friction (0–1)
    val historyGrowth: Double, // per-period growth rate if maintained
    val prizeDecay: Double     // per-period decay rate of reconnection prize
)

data class RepairOutputs(
    val left: Double,              // S·q·[H+E(H)−R]
    val right: Double,             // A−Fe
    val survives: Boolean,         // left > right
    val tStar: Int,                // period when repair stops being rational (−1 = never)
    val historyPath: List<Double>, // maintained-history value per period
    val renewalSurvival: Double,   // q^T
    val renewalHalflife: Double,   // periods until survival halves
    val pStar: Double?             // minimum belief for first-move cooperation (null = no 2×2 matrix)
)

// ─────────────────────────────────────────────────────────────────
//  Pure computation — called by analyze() and by the UI what-if sliders
// ─────────────────────────────────────────────────────────────────
fun computeRepair(inputs: RepairInputs, horizon: Int, pStar: Double?): RepairOutputs {
    val left = inputs.S * inputs.q * (inputs.H + inputs.eH - inputs.R)
    val right = inputs.A - inputs.Fe
    val survives = left > right

    val historyPath = (0..horizon).map { t ->
        inputs.H * (1.0 + inputs.historyGrowth).pow(t.toDouble())
    }

    val initialPrize = inputs.H + inputs.eH
    var tStar = -1
    for (t in 1..horizon) {
        val decayedPrize = initialPrize * (1.0 - inputs.prizeDecay).pow(t.toDouble())
        if (decayedPrize < inputs.R + inputs.Fe) {
            tStar = t
            break
        }
    }

    val renewalSurvival = inputs.q.pow(horizon.toDouble())
    val renewalHalflife = if (inputs.q < 1.0 && inputs.q > 0.0)
        ln(0.5) / ln(inputs.q)
    else
        Double.POSITIVE_INFINITY

    return RepairOutputs(left, right, survives, tStar, historyPath, renewalSurvival, renewalHalflife, pStar)
}

// ─────────────────────────────────────────────────────────────────
//  Framework module
// ─────────────────────────────────────────────────────────────────
class RelationalRepairModule : FrameworkModule {
    override val id      = "relational-repair"
    override val name    = "Relational Repair Game"
    override val version = "1.0.0"
    override val category = "behavioral"

    private val repairKeywords = listOf(
        "repair", "reconnect", "drift", "estrang", "relationship", "mend",
        "reconcil", "rebuild", "restore", "renew", "heal", "bridge", "distant",
        "bond", "rupture", "frayed", "alienat", "detach", "rapport"
    )

    override fun canAnalyze(model: Scenario): Applicability {
        val hasTwo       = model.stakeholders.size == 2
        val isRepeated   = model.repeated
        val hasRepairLang = repairKeywords.any { kw ->
            model.description.lowercase().contains(kw) ||
            model.title.lowercase().contains(kw) ||
            model.stakeholders.any { s ->
                s.notes.lowercase().contains(kw) ||
                s.goals.any { g -> g.lowercase().contains(kw) }
            }
        }
        val isStagHunt = model.payoffs.size == 4 && !model.zeroSum

        val score = when {
            hasTwo && isRepeated && hasRepairLang -> 0.92
            hasTwo && isRepeated && isStagHunt    -> 0.78
            hasTwo && (isRepeated || hasRepairLang) -> 0.55
            hasRepairLang                           -> 0.40
            else                                    -> 0.10
        }

        val reasons = buildList {
            if (hasTwo)        add("Exactly 2 stakeholders — dyadic repair game")
            if (isRepeated)    add("Repeated game — shadow of future operative")
            if (hasRepairLang) add("Relational repair language detected")
            if (isStagHunt)    add("2×2 Stag Hunt payoff structure detected")
        }

        return Applicability(score, reasons)
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val inputs  = extractInputs(model)
        val pStar   = computePStar(model)
        val horizon = model.timeHorizon.coerceAtLeast(6)
        val outputs = computeRepair(inputs, horizon, pStar)

        val termsMap: Map<String, Double> = mapOf(
            "S"             to inputs.S,
            "q"             to inputs.q,
            "H"             to inputs.H,
            "E(H)"          to inputs.eH,
            "R"             to inputs.R,
            "A"             to inputs.A,
            "Fe"            to inputs.Fe,
            "history_growth" to inputs.historyGrowth,
            "prize_decay"   to inputs.prizeDecay
        )

        val findings = buildList {
            add(when {
                outputs.survives && (outputs.tStar == -1 || outputs.tStar > horizon / 2) ->
                    Finding("Bond holds — reconnection stays rational",
                        "Pull-to-stay (${fmt(outputs.left)}) exceeds pull-to-leave (${fmt(outputs.right)}). " +
                        "Shared history creates sufficient incentive to maintain the bond.",
                        Finding.Severity.INFO)
                outputs.survives && outputs.tStar in 1..horizon ->
                    Finding("Bond survives now — act before period ${outputs.tStar}",
                        "Window for rational repair closes at t*=${outputs.tStar}. " +
                        "Prize decay makes reconnection irrational beyond this point.",
                        Finding.Severity.WARNING)
                else ->
                    Finding("Bond at risk — present-tense pull-to-leave dominates",
                        "A−Fe (${fmt(outputs.right)}) exceeds S·q·[H+E(H)−R] (${fmt(outputs.left)}). " +
                        "The alternative or exit currently out-competes reconnection.",
                        Finding.Severity.CRITICAL)
            })
            if (outputs.renewalSurvival < 0.5)
                add(Finding("Low renewal probability over $horizon periods",
                    "q^T = ${pct(outputs.renewalSurvival)} — fewer than half the renewal opportunities will succeed.",
                    Finding.Severity.WARNING))
            if (pStar != null && pStar > 0.7)
                add(Finding("High trust threshold for first-move cooperation",
                    "Both parties need ${pct(pStar)}+ belief the other will cooperate. " +
                    "A focal point or commitment device is required.",
                    Finding.Severity.WARNING))
        }

        val actions = buildList {
            if (!outputs.survives || outputs.tStar in 1..horizon) {
                add(RecommendedAction(null, "Protect the commitment architecture",
                    "Introduce a ritual or checkpoint to maintain shared history H before it decays further.",
                    actionId = "intervention-rhythm"))
                add(RecommendedAction(null, "Reduce reconnection friction",
                    "Identify and remove the largest contributor to Fe to lower the cost floor.",
                    actionId = "intervention-repair-early"))
            }
            if (pStar != null && pStar > 0.6)
                add(RecommendedAction(null, "Create a focal point for first cooperation",
                    "At ${pct(pStar)} belief threshold, unilateral signalling may be needed to break the coordination trap.",
                    actionId = "intervention-focal-point"))
            if (outputs.renewalSurvival < 0.5)
                add(RecommendedAction(null, "Reinstrumentalize the relationship",
                    "Low q signals unreliable renewal cadence. Reframe engagement as high-value recurring interaction.",
                    actionId = "intervention-reinstrumentalize"))
        }

        val initialPrize = inputs.H + inputs.eH
        val prizeTraj = (0..horizon).map { t ->
            initialPrize * (1.0 - inputs.prizeDecay).pow(t.toDouble())
        }

        val calcs: Map<String, Any?> = mapOf(
            "left_now"               to outputs.left,
            "right_now"              to outputs.right,
            "survives_now"           to outputs.survives,
            "trust_threshold_p_star" to outputs.pStar,
            "t_star"                 to outputs.tStar,
            "prize_at_t_star"        to if (outputs.tStar in 1..horizon) prizeTraj.getOrNull(outputs.tStar) else null,
            "prize_trajectory"       to prizeTraj,
            "history_trajectory"     to outputs.historyPath,
            "horizon"                to horizon,
            "renewal_survival_qT"    to outputs.renewalSurvival,
            "renewal_halflife_periods" to outputs.renewalHalflife,
            "terms"                  to termsMap
        )

        return AnalysisResult(
            moduleId            = id,
            moduleName          = name,
            applicability       = canAnalyze(model),
            findings            = findings,
            recommendedActions  = actions,
            assumptions         = listOf(
                Assumption("S, H, q, R, A, Fe are proxied from stakeholder profiles; add explicit beliefs to refine"),
                Assumption("History trajectory assumes consistent maintenance — actual growth depends on interaction quality"),
                Assumption("prize_decay = ${inputs.prizeDecay} per period; adjust via scenario beliefs if empirically known")
            ),
            calculations        = calcs,
            confidence          = if (outputs.survives) 0.72 else 0.35,
            visualizations      = listOf(
                VizSpec(VizSpec.Kind.BAR, "Maintenance inequality",
                    mapOf("left" to outputs.left, "right" to outputs.right)),
                VizSpec(VizSpec.Kind.TABLE, "Melting prize trajectory",
                    mapOf("series" to outputs.historyPath, "tStar" to outputs.tStar))
            ),
            explanation         = buildExplanation(inputs, outputs, pStar, horizon),
            calculationStatus   = CalculationStatus.PROXY_ESTIMATE,
            frameworkCategory   = FrameworkCategory.SUPPORTING
        )
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun extractInputs(model: Scenario): RepairInputs {
        val s1 = model.stakeholders.getOrNull(0)
        val s2 = model.stakeholders.getOrNull(1)

        val S   = ((s1?.authority ?: 5) / 10.0).coerceIn(0.1, 1.0)
        val H   = ((s1?.credibility ?: 5) + (s2?.credibility ?: 5)) / 20.0
        val eH  = H * 0.15
        val q   = if (model.beliefs.isNotEmpty())
                      model.beliefs.map { it.probability }.average().coerceIn(0.1, 0.99)
                  else 0.65
        val R   = run {
            val costs = model.actions.map { it.cost }.filter { it > 0 }
            if (costs.isNotEmpty()) (costs.average() / 100.0).coerceIn(0.1, 0.8) else 0.30
        }
        val A   = (model.stakeholders.count { it.canExit } * 0.25 + 0.15).coerceIn(0.10, 0.90)
        val Fe  = (((s1?.fears?.size ?: 0) + (s2?.fears?.size ?: 0)) * 0.08).coerceIn(0.0, 0.50)

        return RepairInputs(S, q, H, eH, R, A, Fe,
            historyGrowth = 0.12,
            prizeDecay    = 0.08)
    }

    private fun computePStar(model: Scenario): Double? {
        if (model.payoffs.size < 4 || model.stakeholders.size != 2) return null
        val s0 = model.stakeholders[0]
        val s1 = model.stakeholders[1]
        val acts0 = model.actions.filter { it.stakeholderId == s0.id }
        val acts1 = model.actions.filter { it.stakeholderId == s1.id }
        if (acts0.size < 2 || acts1.size < 2) return null

        fun payoff(a0idx: Int, a1idx: Int): Double? =
            model.payoffs.firstOrNull {
                it.stakeholderId == s0.id &&
                it.actionProfile[s0.id] == acts0[a0idx].id &&
                it.actionProfile[s1.id] == acts1[a1idx].id
            }?.payoff?.scalar()

        val a = payoff(0, 0) ?: return null  // C,C
        val b = payoff(0, 1) ?: return null  // C,D
        val c = payoff(1, 0) ?: return null  // D,C
        val d = payoff(1, 1) ?: return null  // D,D

        val denom = a - b - c + d
        if (denom == 0.0) return null
        return ((d - b) / denom).coerceIn(0.0, 1.0)
    }

    private fun buildExplanation(inputs: RepairInputs, outputs: RepairOutputs, pStar: Double?, horizon: Int): String {
        val verdict = if (outputs.survives) "holds" else "is at risk"
        return buildString {
            appendLine("Relational Repair Game (melting-prize, stag-hunt basis). Bond $verdict.")
            appendLine("Maintenance inequality: S·q·[H+E(H)−R] = ${fmt(outputs.left)} vs A−Fe = ${fmt(outputs.right)}.")
            if (outputs.tStar != -1)
                appendLine("Repair window closes at t*=${outputs.tStar} (prize decay dominates beyond).")
            else
                appendLine("No t* within horizon — repair remains rational throughout.")
            appendLine("Renewal survival over $horizon periods: ${pct(outputs.renewalSurvival)} " +
                "(half-life ≈ ${if (outputs.renewalHalflife.isInfinite()) "∞" else "${outputs.renewalHalflife.toInt()}"} periods).")
            if (pStar != null)
                append("Trust threshold p* = ${pct(pStar)} — minimum belief to reach-first in the 2×2 game.")
        }.trim()
    }

    private fun fmt(d: Double) = "%.2f".format(d)
    private fun pct(d: Double) = "${(d * 100).toInt()}%"
}
