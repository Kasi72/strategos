package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Relational Repair Game.
 *
 * Models the maintenance / repair of a two-party bond (friendship, partnership,
 * co-parenting, alliance, long-running collaboration) as a repeated coordination
 * game with a *decaying prize* and *compounding history*. It unifies three results
 * that single-shot equilibrium analysis cannot see:
 *
 *  1. The repair deadlock — "nobody messages first". Each period both players choose
 *     INVEST (reach / show up / repair) or WITHDRAW (fade). (Invest, Withdraw) is the
 *     sucker cell: you pay the renewal cost R and the exposure sting d and get nothing
 *     back, while the other keeps R and takes the warmth. That asymmetric sting makes
 *     mutual WITHDRAW risk-dominant even when mutual INVEST is payoff-dominant
 *     (classic stag hunt — see StagHuntModule, whose threshold math this reuses).
 *
 *  2. The melting prize — the value of reconnection decays each period of silence while
 *     the cost of reaching stays roughly fixed. There is a period t* at which WITHDRAW
 *     becomes *strictly* dominant: after t* no one is being irrational by staying silent,
 *     and the bond is dead although neither party ever decided to end it ("we drifted
 *     apart" = a war of attrition over a prize that melted before either moved).
 *
 *  3. The renewal burden — when persistence requires a successful mutual renewal every
 *     period, survival probability is q^T. Even a reliable q (0.9) decays geometrically
 *     (0.9^12 ≈ 0.28). Structure (rhythm, a standing slot) lowers the per-period cost R
 *     and raises q toward 1; it does not need to raise affection.
 *
 * Alongside the game it evaluates the maintenance inequality
 *
 *      S · q · [ H + E(H) − R ]   >   A − Fe
 *
 * where H = accumulated shared history (compounds), E(H) = the part of that history already
 * absorbed into the self (survives loss), R = per-period renewal cost, A = pull of the
 * freshest alternative (inflated by counterfactual density — the Fresh Alternative Bias),
 * Fe = extrinsic exit friction (low under modernity, and lowering it is the *good* part),
 * q = bilateral renewal probability, S = self-authority coefficient 0..1 (how much a past
 * commitment binds the present self; present-tense sovereignty drives S → 0 and zeroes the
 * left side regardless of how much history exists).
 *
 * Term inputs, in priority order:
 *   • Beliefs whose label matches a known term (probability read as the 0..1 value):
 *       shared_history / history → H ;  absorbed_history / identity → E(H) ;
 *       renewal_cost / maintenance_cost → R ;  alternative / counterfactual → A ;
 *       exit_friction → Fe ;  renewal_prob / q → q ;  self_authority / authorship → S ;
 *       history_growth → per-period compounding of H ;  prize_decay → per-period melt of the prize.
 *   • Otherwise proxied from stakeholder/action fields (credibility, canExit, trust/coalition
 *     links, reversibility, timeCost) so the module degrades gracefully. Proxy use is reported.
 */
class RelationalRepairModule : FrameworkModule {
    override val id = "relational-repair"
    override val name = "Relational Repair Game (Compounding / Melting Prize)"
    override val version = "1.0"
    override val category = "Behavioral"

    // ---- term container --------------------------------------------------

    private data class Terms(
        val h: Double,          // accumulated shared history (0..1 normalized)
        val eh: Double,         // absorbed-into-self history (0..1)
        val r: Double,          // per-period renewal cost (0..1)
        val a: Double,          // alternative pull as presented (0..1)
        val fe: Double,         // extrinsic exit friction (0..1)
        val q: Double,          // bilateral renewal probability (0..1)
        val s: Double,          // self-authority coefficient (0..1)
        val growth: Double,     // per-period compounding of H (e.g. 0.06 = +6%/period)
        val decay: Double,      // per-period melt of the reconnection prize (e.g. 0.10)
        val proxied: Boolean    // true if any term fell back to a proxy
    )

    // ---- applicability ---------------------------------------------------

    private val relationalKeywords = listOf(
        "relationship", "friend", "friendship", "partner", "marriage", "spouse",
        "co-parent", "coparent", "reconcile", "reconciliation", "repair", "drift",
        "estrange", "reconnect", "fade", "fell out", "fell-out", "silence",
        "alliance", "collaborator", "collaboration", "bond", "trust", "loyalty",
        "belong", "belonging", "maintain", "keep in touch", "reach out", "patch things up"
    )

    override fun canAnalyze(model: Scenario): Applicability {
        if (model.stakeholders.size != 2)
            return Applicability(0.0, listOf("Relational repair is a two-party model (exactly 2 stakeholders)."))

        val text = "${model.title} ${model.description} ${model.stakes}".lowercase()
        val kw = relationalKeywords.count { text.contains(it) }
        val kwScore = (kw / 4.0).coerceAtMost(1.0)

        // Structural signal: a 2×2 game that is a stag hunt, played repeatedly, is exactly
        // the repair game's skeleton.
        val sh = ScenarioClassifier.detectSH(model)
        val repeatedSignal = if (model.repeated || model.timeHorizon > 1) 0.25 else 0.0

        val reasons = mutableListOf<String>()
        if (kw > 0) reasons += "Relational / repair language detected ($kw cue${if (kw == 1) "" else "s"})."
        if (sh > 0.5) reasons += "2×2 stag-hunt structure present — the repair deadlock's skeleton."
        if (model.repeated || model.timeHorizon > 1) reasons += "Repeated interaction (horizon = ${model.timeHorizon}) — enables melting-prize dynamics."
        if (termBeliefsPresent(model)) reasons += "Maintenance-inequality term inputs supplied via beliefs."

        val score = (0.55 * kwScore + 0.55 * sh + repeatedSignal).coerceIn(0.0, 0.95)
        if (reasons.isEmpty()) reasons += "No clear relational-repair structure detected."
        return Applicability(score, reasons)
    }

    private val termLabels = setOf(
        "shared_history", "history", "absorbed_history", "identity",
        "renewal_cost", "maintenance_cost", "alternative", "counterfactual",
        "exit_friction", "renewal_prob", "q", "self_authority", "authorship",
        "history_growth", "prize_decay"
    )

    private fun termBeliefsPresent(model: Scenario): Boolean =
        model.beliefs.any { it.label.trim().lowercase().replace(' ', '_') in termLabels }

    // ---- analysis --------------------------------------------------------

    override fun analyze(model: Scenario): AnalysisResult {
        val t = resolveTerms(model)
        val n0 = model.stakeholders[0].name
        val n1 = model.stakeholders[1].name

        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()
        val calcs = mutableMapOf<String, Any?>()

        // --- 1. The maintenance inequality (snapshot, this period) --------
        val leftNow = t.s * t.q * (t.h + t.eh - t.r)
        val rightNow = t.a - t.fe
        val survivesNow = leftNow > rightNow
        calcs["left_now"] = leftNow
        calcs["right_now"] = rightNow
        calcs["survives_now"] = survivesNow

        findings += Finding(
            if (survivesNow)
                "Bond clears the maintenance bar this period: pull-to-stay %.3f > pull-to-leave %.3f".format(leftNow, rightNow)
            else
                "Bond fails the maintenance bar this period: pull-to-stay %.3f ≤ pull-to-leave %.3f".format(leftNow, rightNow),
            "S·q·[H+E(H)−R] = %.2f·%.2f·[%.2f+%.2f−%.2f] = %.3f, versus A−Fe = %.2f−%.2f = %.3f. ".format(
                t.s, t.q, t.h, t.eh, t.r, leftNow, t.a, t.fe, rightNow) +
                "The left side compounds over time (through H); the right side is renewed fresh each period and does not. So a bond that fails early can still become robust once H accrues — and one that clears today is not safe if S or q fall.",
            if (survivesNow) Finding.Severity.INFO else Finding.Severity.WARNING
        )

        // Self-authority is the master switch: S multiplies everything on the left.
        if (t.s < 0.34) {
            findings += Finding(
                "Self-authority S = %.2f is low — present-tense sovereignty is collapsing the bond".format(t.s),
                "S multiplies the entire pull-to-stay. When the present self grants its current preference near-total authority over past commitments (S → 0), no amount of accumulated history H can hold the bond: 'I don't want this anymore' is being treated as sufficient rather than as one vote in a larger ledger. This is the one term that can zero everything else, and the one modernity attacks morally rather than materially.",
                Finding.Severity.CRITICAL
            )
        }

        // --- 2. Stag-hunt repair deadlock + trust threshold --------------
        val sf = MatrixSupport.build(model)
        val stagHunt = sf != null && sf.players.size == 2 &&
            sf.strategies.values.all { it.size == 2 } && ScenarioClassifier.detectSH(model) > 0.5

        var pThreshold = Double.NaN
        if (stagHunt && sf != null) {
            // Reuse the exact indifference math from StagHuntModule.
            val a = sf.utility[0][sf.profileIndex(listOf(0, 0))]
            val b = sf.utility[0][sf.profileIndex(listOf(0, 1))]
            val c = sf.utility[0][sf.profileIndex(listOf(1, 0))]
            val d = sf.utility[0][sf.profileIndex(listOf(1, 1))]
            val denom = a - b - c + d
            pThreshold = if (abs(denom) > 1e-9) ((d - b) / denom).coerceIn(0.0, 1.0) else 0.5
            val euInvest = (a + b) / 2.0
            val euWithdraw = (c + d) / 2.0
            calcs["trust_threshold_p_star"] = pThreshold
            calcs["eu_invest_vs_random"] = euInvest
            calcs["eu_withdraw_vs_random"] = euWithdraw

            findings += Finding(
                "Repair deadlock — minimum trust to reach first: p* = %.0f%%".format(pThreshold * 100),
                "Mutual INVEST (reconnect) is the payoff-dominant equilibrium, but mutual WITHDRAW is risk-dominant because the sucker cell — you reach, they don't — is the worst outcome. $n0 should reach only if they believe $n1 will reciprocate with probability above %.0f%%. Below it, silence is each person's best reply, and two people who both want reconnection sit in mutual self-protection. This is a coordination failure, not a failure of caring.".format(pThreshold * 100),
                if (euInvest > euWithdraw) Finding.Severity.NOTE else Finding.Severity.CRITICAL
            )
        } else {
            findings += Finding(
                "Repair deadlock (proxy): first-mover exposure blocks reconnection",
                "No explicit 2×2 Invest/Withdraw payoff matrix was supplied, so the trust threshold is not computed. Qualitatively: whoever reaches first risks revealing higher investment than is returned. Both may prefer reconnection and both stay silent. Add a 2×2 payoff matrix (Invest/Withdraw for each party) to compute the exact trust threshold p*.",
                Finding.Severity.NOTE
            )
        }

        // --- 3. The melting prize: find t* over the horizon --------------
        val horizon = (model.timeHorizon.takeIf { it > 1 } ?: 24).coerceAtMost(240)
        val melt = simulateMeltingPrize(t, horizon)
        calcs["horizon"] = horizon
        calcs["t_star"] = melt.tStar
        calcs["prize_at_t_star"] = melt.prizeAtTStar
        calcs["history_trajectory"] = melt.historyPath

        if (melt.tStar in 1..horizon) {
            findings += Finding(
                "Melting-prize threshold: repair stops being rational after period t* = ${melt.tStar}",
                "The value of reconnection decays about %.0f%% per period of silence while the cost of reaching stays ~fixed. By period ${melt.tStar} the decayed prize (%.2f) has fallen below the cost+exposure of trying, so WITHDRAW becomes the strictly dominant action for both — the bond is then dead although no one decided to end it. Before t* this is only a bad equilibrium that a single reach can break; after t* it is a correct individual choice. This is why early repair matters: delay does not merely postpone, it changes the game.".format(t.decay * 100, melt.prizeAtTStar),
                Finding.Severity.WARNING
            )
        } else {
            findings += Finding(
                "No melting-prize collapse within $horizon periods",
                "At the current decay rate (%.0f%%/period) and history level, the reconnection prize stays above the cost of reaching for the whole horizon — the window for repair remains open. Compounding history is outrunning the melt.".format(t.decay * 100),
                Finding.Severity.INFO
            )
        }

        // --- 4. Renewal burden: q^T survival ------------------------------
        val survivalT = t.q.pow(horizon.toDouble())
        val half = if (t.q in 0.0..0.999 && t.q > 0.0) (ln(0.5) / ln(t.q)) else Double.POSITIVE_INFINITY
        calcs["renewal_survival_qT"] = survivalT
        calcs["renewal_halflife_periods"] = half
        findings += Finding(
            "Renewal burden: q=%.2f per period ⇒ %.0f%% survival over %d periods".format(t.q, survivalT * 100, horizon),
            "When the bond must be actively re-renewed each period, survival is q^T. Even a reliable q compounds downward (half the bond's survival probability is gone after ~%.0f periods). The fix is not more affection — it is to raise q toward 1 and lower R by making maintenance a *default* (a standing rhythm) instead of a repeated decision.".format(if (half.isFinite()) half else 0.0),
            if (survivalT < 0.5) Finding.Severity.WARNING else Finding.Severity.NOTE
        )

        // --- 5. Interventions, as equilibrium edits -----------------------
        // Each maps a thread finding to one term, and reports the flip if it occurs.
        val id0 = model.stakeholders[0].id
        buildInterventions(t, leftNow, rightNow, pThreshold, id0).forEach { recs += it }

        // --- confidence & status -----------------------------------------
        val profile = ConfidenceProfile(
            evidenceQuality = (model.evidenceQuality() / 100.0),
            modelCompleteness = if (stagHunt && !t.proxied) 0.85 else if (stagHunt || !t.proxied) 0.6 else 0.4,
            analyticalRobustness = if (stagHunt) 0.8 else 0.55,
            implementationReadiness = 0.6,
            explanations = mapOf(
                "modelCompleteness" to (if (t.proxied) "Some maintenance-inequality terms were proxied from stakeholder fields; supply them as beliefs for a full calculation." else "All terms supplied explicitly."),
                "analyticalRobustness" to (if (stagHunt) "2×2 stag-hunt matrix present — trust threshold computed in closed form." else "No explicit payoff matrix — trust threshold qualitative only.")
            )
        )
        val status = when {
            stagHunt && !t.proxied -> CalculationStatus.CALCULATED
            stagHunt || termBeliefsPresent(model) -> CalculationStatus.PARTIALLY_CALCULATED
            else -> CalculationStatus.PROXY_ESTIMATE
        }

        calcs["terms"] = mapOf(
            "H" to t.h, "E(H)" to t.eh, "R" to t.r, "A" to t.a, "Fe" to t.fe,
            "q" to t.q, "S" to t.s, "history_growth" to t.growth, "prize_decay" to t.decay
        )

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings,
            recommendedActions = recs,
            assumptions = listOf(
                Assumption("Maintenance inequality: S·q·[H+E(H)−R] > A−Fe. Left side compounds via H; right side is renewed fresh each period (Fresh Alternative Bias)."),
                Assumption("Trust threshold p* from the stag-hunt indifference condition p*a+(1−p*)b = p*c+(1−p*)d → p* = (d−b)/(a−b−c+d); action index 0 = INVEST, 1 = WITHDRAW."),
                Assumption("Melting prize: reconnection value decays geometrically at 'prize_decay' per period; shared history H compounds at 'history_growth' per mutual-invest period. t* is the first period the decayed prize falls below the cost of reaching."),
                Assumption("Renewal survival q^T assumes per-period renewals are independent — a simplification; real renewals are correlated, but the geometric-decay intuition holds."),
                Assumption("Self-authority S multiplies the pull-to-stay: S models how far a past commitment binds the present self (biographical authorship vs present-tense sovereignty), not sameness of self over time."),
                Assumption(if (t.proxied) "Some terms were proxied from stakeholder/action fields (credibility, canExit, trust links, reversibility). Supply beliefs labelled e.g. 'shared_history', 'self_authority', 'exit_friction' for exact values." else "All inequality terms supplied explicitly via beliefs.")
            ),
            calculations = calcs,
            confidence = profile.overall(),
            confidenceProfile = profile,
            calculationStatus = status,
            frameworkCategory = if (canAnalyze(model).score >= 0.6) FrameworkCategory.CORE else FrameworkCategory.SUPPORTING,
            visualizations = listOf(
                VizSpec(VizSpec.Kind.TEXT, "Maintenance inequality", mapOf("left" to leftNow, "right" to rightNow, "survives" to survivesNow)),
                VizSpec(VizSpec.Kind.BAR, "Shared-history trajectory", mapOf("history" to melt.historyPath, "tStar" to melt.tStar))
            ),
            explanation = buildString {
                append("Relational repair as a repeated coordination game with a melting prize. ")
                if (stagHunt) append("Trust threshold to reach first: p* = %.0f%%. ".format(pThreshold * 100))
                if (melt.tStar in 1..horizon) append("Repair stops being rational after period t* = ${melt.tStar}. ")
                append("Per-period renewal survival over %d periods: %.0f%%. ".format(horizon, survivalT * 100))
                append("The cure is the cause reversed: raise q and lower R by building a standing rhythm (make maintenance a default, not a decision), protect S against present-tense sovereignty, and let H compound by staying through the dry periods — all without re-raising extrinsic friction Fe.")
            }
        )
    }

    // ---- melting-prize simulation ---------------------------------------

    private data class MeltResult(
        val tStar: Int,                 // first period Withdraw strictly dominates; -1 if none in horizon
        val prizeAtTStar: Double,
        val historyPath: List<Double>
    )

    /**
     * Prize of reconnection decays each period of silence; shared history compounds while
     * the bond is maintained. We report the period at which the decayed prize + the
     * compounding value of accumulated history can no longer cover the cost+exposure of
     * reaching — i.e. when Withdraw becomes strictly dominant.
     *
     * reachCost  = R + sting        (cost of reaching + exposure of the sucker cell)
     * prize(t)   = prize0 · (1−decay)^t   (reconnection value decays during silence;
     *              compounding of H only accrues while the bond is maintained, not during silence)
     * prize0 is anchored so a *fresh* bond is always worth repairing (prize0 > reachCost):
     *   prize0 = reachCost · (1 + restorable),  restorable = H + A in [0,2].
     * t* = first t with prize(t) < reachCost. Higher decay ⇒ smaller t*.
     * The historyPath (what H would have become had the bond been maintained) is reported
     * separately so the UI can contrast "value if kept" against "value if left to melt".
     */
    private fun simulateMeltingPrize(t: Terms, horizon: Int): MeltResult {
        val sting = t.r * 0.5                             // exposure sting of reaching unreciprocated
        val reachCost = (t.r + sting).coerceAtLeast(0.05) // cost of a single reach
        val restorable = (t.h + t.a).coerceIn(0.0, 2.0)   // how much reconnection would restore
        val prize0 = reachCost * (1.0 + restorable)       // a fresh reach is always worth it (> reachCost)
        val historyPath = ArrayList<Double>(horizon + 1)
        var tStar = -1
        var prizeAtTStar = 0.0
        for (step in 0..horizon) {
            val decayed = prize0 * (1.0 - t.decay).pow(step.toDouble())
            val compounded = (t.h + t.eh) * (1.0 + t.growth).pow(step.toDouble())
            historyPath.add(compounded)
            if (tStar < 0 && step > 0 && decayed < reachCost) {
                tStar = step
                prizeAtTStar = decayed
            }
        }
        return MeltResult(tStar, prizeAtTStar, historyPath)
    }

    // ---- interventions ---------------------------------------------------

    private fun buildInterventions(
        t: Terms,
        leftNow: Double,
        rightNow: Double,
        pThreshold: Double,
        ownerId: String
    ): List<RecommendedAction> {
        val recs = mutableListOf<RecommendedAction>()

        fun flips(newLeft: Double): String =
            if (leftNow <= rightNow && newLeft > rightNow)
                " This flips the bond from failing to clearing the maintenance bar (%.3f → %.3f vs %.3f).".format(leftNow, newLeft, rightNow)
            else if (newLeft > leftNow)
                " Pull-to-stay rises %.3f → %.3f (bar: %.3f).".format(leftNow, newLeft, rightNow)
            else ""

        // Rhythm → lowers R (and raises q). Cheapest, highest-leverage.
        val rNew = (t.r * 0.4).coerceAtLeast(0.0)
        val qNew = (t.q + (1 - t.q) * 0.4).coerceAtMost(1.0)
        val leftRhythm = t.s * qNew * (t.h + t.eh - rNew)
        recs += RecommendedAction(
            null,
            "Build a standing rhythm (fixed time, place, people)",
            "Convert maintenance from a repeated decision into a default — 'Tuesday 7pm, same table'. This lowers the per-period renewal cost R (%.2f → %.2f) and raises the bilateral renewal probability q (%.2f → %.2f), because a scheduled contact is normative rather than revealing, so nobody risks the sucker payoff by reaching." .format(t.r, rNew, t.q, qNew) + flips(leftRhythm),
            contingency = "If a session is missed, treat it as an operational miss, not a referendum on the bond.",
            lens = RecommendedAction.Lens.LONG_TERM,
            actionId = "intervention-rhythm"
        )

        // Focal point → zeroes the exposure sting d (lowers the effective trust threshold).
        if (pThreshold.isFinite()) {
            recs += RecommendedAction(
                null,
                "Create a focal point so reaching signals nothing about who cares more",
                "A birthday, an anniversary, a recurring occasion makes the first move normative rather than an admission of higher investment — it drives the exposure sting toward zero and lowers the trust threshold below the current p* = %.0f%%, so reconnection no longer requires winning a game of nerve.".format(pThreshold * 100),
                lens = RecommendedAction.Lens.RELATIONSHIP,
                actionId = "intervention-focal-point"
            )
        }

        // Repair early → act before t* (melting prize).
        recs += RecommendedAction(
            ownerId,
            "Reach now, before the prize melts",
            "Delay is not neutral: each period of silence lowers the value of reconnection while the cost of reaching stays fixed, moving the bond toward the t* threshold after which withdrawal is the rational choice for both. The reach is cheapest today and never cheaper again.",
            lens = RecommendedAction.Lens.LOW_REGRET,
            actionId = "intervention-repair-early"
        )

        // Re-instrumentalize → raises the cost of silence (makes the bond load-bearing).
        recs += RecommendedAction(
            null,
            "Make the bond load-bearing again (a shared project, duty, or dependency)",
            "A shared undertaking — co-owning something, a joint responsibility, a standing obligation — restores instrumental benefit and raises the cost of silence, which revives contact regardless of current affection. This is why co-parents and business partners keep talking: structure, not sentiment.",
            lens = RecommendedAction.Lens.LONG_TERM,
            actionId = "intervention-reinstrumentalize"
        )

        // Protect S → authorship over present-tense sovereignty.
        if (t.s < 0.6) {
            val sNew = (t.s + (1 - t.s) * 0.5).coerceAtMost(1.0)
            val leftS = sNew * t.q * (t.h + t.eh - t.r)
            recs += RecommendedAction(
                ownerId,
                "Grant the past commitment standing — treat 'I don't want this now' as one vote, not the verdict",
                "Raising self-authority S (%.2f → %.2f) means current preference enters a larger ledger (what was promised, what was built, who you became through it) rather than overruling it outright. This does not forbid leaving genuinely bad bonds — it forbids confusing boredom, difficulty, or a fresher-looking alternative with a reason to.".format(t.s, sNew) + flips(leftS),
                lens = RecommendedAction.Lens.ETHICAL_LEGAL,
                actionId = "intervention-authorship"
            )
        }

        // Let H compound → stay through the dry periods.
        recs += RecommendedAction(
            null,
            "Stay through the dull stretch so shared history can compound",
            "The value that makes a bond irreplaceable (H, and the part absorbed into you, E(H)) accrues precisely in the unremarkable periods that comparison says to leave. The alternative looks cleaner only because it has not yet accumulated either its costs or its history. Suspend comparison long enough for compounding to occur.",
            lens = RecommendedAction.Lens.LONG_TERM,
            actionId = "intervention-compound"
        )

        return recs
    }

    // ---- term resolution -------------------------------------------------

    private fun beliefVal(model: Scenario, vararg labels: String): Double? {
        val wanted = labels.map { it.lowercase() }.toSet()
        return model.beliefs.firstOrNull {
            it.label.trim().lowercase().replace(' ', '_') in wanted
        }?.probability?.coerceIn(0.0, 1.0)
    }

    private fun resolveTerms(model: Scenario): Terms {
        var proxied = false
        fun proxy(v: Double): Double { proxied = true; return v }

        val s0 = model.stakeholders[0]
        val s1 = model.stakeholders[1]

        // H — shared history. Proxy: mutual trust/coalition links + repeated flag.
        val h = beliefVal(model, "shared_history", "history") ?: proxy(run {
            val mutualTrust = (s0.trustRelationships.contains(s1.id) || s1.trustRelationships.contains(s0.id))
            val coalition = (s0.coalitionsWith.contains(s1.id) || s1.coalitionsWith.contains(s0.id))
            var base = 0.25
            if (mutualTrust) base += 0.25
            if (coalition) base += 0.2
            if (model.repeated) base += 0.1
            base.coerceIn(0.0, 1.0)
        })

        // E(H) — absorbed into self. Proxy: a fraction of H.
        val eh = beliefVal(model, "absorbed_history", "identity") ?: proxy((h * 0.4).coerceIn(0.0, 1.0))

        // R — per-period renewal cost. Proxy: normalized max action timeCost, or default.
        val r = beliefVal(model, "renewal_cost", "maintenance_cost") ?: proxy(run {
            val maxTime = model.actions.maxOfOrNull { it.timeCost } ?: 0
            if (maxTime > 0) (maxTime / 30.0).coerceIn(0.1, 0.9) else 0.35
        })

        // A — alternative pull. Proxy: whichever party can exit raises it.
        val a = beliefVal(model, "alternative", "counterfactual") ?: proxy(run {
            var base = 0.3
            if (s0.canExit || s1.canExit) base += 0.2
            base.coerceIn(0.0, 1.0)
        })

        // Fe — extrinsic exit friction. Proxy: low if either canExit; action reversibility.
        val fe = beliefVal(model, "exit_friction") ?: proxy(run {
            val easyExit = s0.canExit || s1.canExit
            val reversible = model.actions.all { it.reversible }
            when {
                easyExit && reversible -> 0.1
                easyExit || reversible -> 0.25
                else -> 0.5
            }
        })

        // q — bilateral renewal probability. Proxy: average credibility.
        val q = beliefVal(model, "renewal_prob", "q") ?: proxy(
            ((s0.credibility + s1.credibility) / 20.0).coerceIn(0.1, 0.98)
        )

        // S — self-authority. Proxy: average credibility as a stand-in for commitment-keeping.
        val s = beliefVal(model, "self_authority", "authorship") ?: proxy(
            ((s0.credibility + s1.credibility) / 20.0).coerceIn(0.1, 0.95)
        )

        // growth / decay — compounding and melt rates.
        val growth = beliefVal(model, "history_growth") ?: proxy(0.05)
        val decay = beliefVal(model, "prize_decay") ?: proxy(0.08)

        return Terms(h, eh, r, a, fe, q, s, growth, decay, proxied)
    }
}
