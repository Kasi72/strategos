package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport
import kotlin.math.abs

class TrustGameModule : FrameworkModule {
    override val id = "trust-game"
    override val name = "Trust Game (Reciprocity)"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        val n = model.stakeholders.size
        val text = "${model.title} ${model.description}".lowercase()
        val kwHits = listOf("trust", "invest", "delegate", "reciproc", "send", "return", "oversight", "behalf", "deposit", "authority", "monitor").count { text.contains(it) }
        val infoBonus = if (model.informationAsymmetry) 0.15 else 0.0
        val score = (0.35 + kwHits * 0.06 + infoBonus).coerceAtMost(0.88)
        if (n == 2 && (kwHits >= 2 || model.informationAsymmetry)) {
            return Applicability(score, listOf("Trust/investment language ($kwHits keyword hits)${if (model.informationAsymmetry) " + information asymmetry flag" else ""}."))
        }
        if (n >= 2 && kwHits >= 3) {
            return Applicability(score, listOf("Strong trust language across $n players."))
        }
        return Applicability(0.0, listOf("Trust game requires 2 players with trust/investment or delegation dynamics."))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.build(model)
        val p0 = model.stakeholders.getOrNull(0) ?: return emptyResult()
        val p1 = model.stakeholders.getOrNull(1) ?: return emptyResult()

        val (investor, trustee) = if (p0.authority >= p1.authority) p0 to p1 else p1 to p0

        val findings = mutableListOf<Finding>()

        if (sf != null && sf.players.size >= 2) {
            val invIdx = sf.players.indexOf(investor.id)
            val truIdx = sf.players.indexOf(trustee.id)
            if (invIdx >= 0 && truIdx >= 0) {
                val nActions0 = sf.strategies[sf.players[invIdx]]?.size ?: 2
                val nActions1 = sf.strategies[sf.players[truIdx]]?.size ?: 2

                // Trust = investor plays action 0; withhold = action 1 (if exists)
                val trustProfile = MutableList(sf.players.size) { 0 }
                val withholdProfile = MutableList(sf.players.size) { 0 }.also { it[invIdx] = 1.coerceAtMost(nActions0 - 1) }
                val reciprocateProfile = MutableList(sf.players.size) { 0 }
                val defectProfile = MutableList(sf.players.size) { 0 }.also { it[truIdx] = 1.coerceAtMost(nActions1 - 1) }

                val uInvTrust = sf.utility[invIdx][sf.profileIndex(trustProfile)]
                val uInvWithhold = sf.utility[invIdx][sf.profileIndex(withholdProfile)]
                val uTruRecip = sf.utility[truIdx][sf.profileIndex(reciprocateProfile)]
                val uTruDefect = sf.utility[truIdx][sf.profileIndex(defectProfile)]

                val trustPremium = uInvTrust - uInvWithhold
                val betrayalTemptation = uTruDefect - uTruRecip

                findings += Finding(
                    "Trust premium: %.2f for ${investor.name}".format(trustPremium),
                    "If ${trustee.name} reciprocates, ${investor.name} gains %.2f more vs withholding. Betrayal temptation for ${trustee.name}: %.2f (gain from defecting instead of returning).".format(trustPremium, betrayalTemptation),
                    if (trustPremium > 0) Finding.Severity.INFO else Finding.Severity.WARNING
                )

                if (trustPremium < 0) {
                    findings += Finding(
                        "Dominant strategy: withhold trust",
                        "Backward induction: rational trustee defects → rational investor withholds. Cooperation requires external enforcement, reputation, or social norms stronger than self-interest.",
                        Finding.Severity.CRITICAL
                    )
                }

                if (betrayalTemptation > abs(trustPremium)) {
                    findings += Finding(
                        "High betrayal temptation (%.2f > trust premium %.2f)".format(betrayalTemptation, abs(trustPremium)),
                        "Trustee's gain from exploiting trust exceeds investor's loss-to-gain ratio. Monitoring, staged delegation, or contractual safeguards are especially important here.",
                        Finding.Severity.CRITICAL
                    )
                }
            }
        }

        findings += Finding(
            "Behavioral baseline: Berg, Dickhaut & McCabe (1995)",
            "Original trust game: investors sent ~50% of endowment on average; trustees returned ~50% of the tripled amount. Social norms of reciprocity reliably override backward induction in real interactions — but fall off when anonymity is high or stakes are very large.",
            Finding.Severity.NOTE
        )

        if (model.informationAsymmetry) {
            findings += Finding(
                "Information asymmetry amplifies trust gap",
                "${trustee.name} holds private information ${investor.name} cannot verify. This increases both the trust required from ${investor.name} and the exploitation opportunity for ${trustee.name}. Transparency mechanisms (open books, audit rights) materially shift outcomes.",
                Finding.Severity.WARNING
            )
        }

        val authorityGap = abs(p0.authority - p1.authority)
        if (authorityGap > 2) {
            findings += Finding(
                "Authority imbalance: gap = $authorityGap",
                "Power asymmetry between ${investor.name} and ${trustee.name} can suppress candid reciprocity signals. Ensure ${trustee.name} has a formal channel to raise concerns without retaliation risk.",
                Finding.Severity.WARNING
            )
        }

        if (model.repeated) {
            findings += Finding(
                "Repeated game: reputational incentives are operative",
                "Multi-period interaction creates long-term reputational incentives for ${trustee.name} to reciprocate consistently. Early reciprocation builds the relational capital that sustains future cooperation.",
                Finding.Severity.INFO
            )
        }

        val recs = listOf(
            RecommendedAction(investor.id, "Trust conditionally with staged checkpoints",
                "Extend trust incrementally: small initial delegation with explicit performance review before the next stage. Behavioral research shows staged trust-building is more robust than all-or-nothing delegation.",
                contingency = "If ${trustee.name} fails the first checkpoint, reduce scope — do not escalate delegation.",
                lens = RecommendedAction.Lens.RISK_AVERSE),
            RecommendedAction(trustee.id, "Signal reciprocity commitment proactively",
                "Sharing information voluntarily before being asked significantly raises investor trust and cooperation rates in experimental studies. First-mover reciprocity signals shift the game from distrust to cooperation.",
                lens = RecommendedAction.Lens.RELATIONSHIP),
            RecommendedAction(null, "Establish formal reputation mechanism",
                "Audit trail, performance log, or third-party review creates future-game incentives for ${trustee.name} to reciprocate. Effective even in relationships with finite horizons when the record has external value.",
                lens = RecommendedAction.Lens.LONG_TERM)
        )

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Higher-authority stakeholder assumed to be investor (trusting party)."),
                Assumption("Action 0 = trust/invest/delegate; action 1 = withhold/defect per player."),
                Assumption("Behavioral baselines from Berg, Dickhaut & McCabe (1995); reciprocity norms from Fehr & Gächter (2000).")
            ),
            calculations = mapOf("investorId" to investor.id, "trusteeId" to trustee.id, "informationAsymmetry" to model.informationAsymmetry, "authorityGap" to authorityGap),
            confidence = 0.62,
            visualizations = emptyList(),
            explanation = "Trust game: backward induction predicts zero trust, but social reciprocity norms drive substantial cooperation in practice. Staged delegation, proactive transparency, and reputational accountability are the three most effective levers."
        )
    }

    private fun emptyResult() = AnalysisResult(id, name, Applicability(0.0, listOf("Insufficient model data.")), emptyList(), emptyList(), emptyList(), emptyMap(), 0.0, emptyList(), "")
}
