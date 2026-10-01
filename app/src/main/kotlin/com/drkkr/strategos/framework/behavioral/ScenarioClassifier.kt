package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport

object ScenarioClassifier {

    enum class GameType {
        PRISONERS_DILEMMA, STAG_HUNT, ULTIMATUM, TRUST_GAME,
        PUBLIC_GOODS, BARGAINING, AUCTION, ZERO_SUM,
        SIGNALLING, FIRST_MOVER, GENERAL
    }

    data class Classification(
        val scores: Map<GameType, Double>,
        val primary: GameType,
        val evidence: List<String>
    )

    fun classify(scenario: Scenario): Classification {
        val scores = mutableMapOf<GameType, Double>()
        val evidence = mutableListOf<String>()
        val text = "${scenario.title} ${scenario.description}".lowercase()
        val n = scenario.stakeholders.size

        val pdScore = detectPD(scenario)
        if (pdScore > 0.3) {
            scores[GameType.PRISONERS_DILEMMA] = pdScore
            evidence += "2×2 payoff structure satisfies T>R>P>S (Prisoner's Dilemma)"
        }

        val shScore = detectSH(scenario)
        if (shScore > 0.3) {
            scores[GameType.STAG_HUNT] = shScore
            evidence += "Two pure Nash equilibria — payoff vs risk dominant coordination"
        }

        val ultScore = kwScore(text, listOf("split", "offer", "reject", "accept", "propose", "fair", "ultimatum", "allocation", "divide", "share"))
        if (ultScore > 0.18 && n == 2) {
            scores[GameType.ULTIMATUM] = (0.3 + ultScore * 0.65).coerceAtMost(0.9)
            evidence += "Ultimatum/fair-division language detected"
        }

        val trustScore = kwScore(text, listOf("trust", "invest", "delegate", "reciproc", "send", "return", "oversight", "behalf", "deposit"))
        val trustBoost = if (scenario.informationAsymmetry) 1.25 else 1.0
        if (trustScore > 0.18) {
            scores[GameType.TRUST_GAME] = ((0.3 + trustScore * 0.65) * trustBoost).coerceAtMost(0.9)
            evidence += "Trust/delegation language" + if (scenario.informationAsymmetry) " + information asymmetry flag" else ""
        }

        val pgScore = kwScore(text, listOf("contribut", "public", "collective", "pool", "common", "cooperat", "commons", "shared", "fund"))
        if (pgScore > 0.15 && n >= 3) {
            scores[GameType.PUBLIC_GOODS] = (0.3 + pgScore * 0.65).coerceAtMost(0.9)
            evidence += "Public goods / collective action language (N=$n players)"
        }

        val bargScore = kwScore(text, listOf("negotiat", "bargain", "agreement", "settle", "deal", "compromise", "resolve", "mediat", "arbitrat"))
        if (bargScore > 0.15 && n == 2) {
            scores[GameType.BARGAINING] = (0.3 + bargScore * 0.65).coerceAtMost(0.9)
            evidence += "Bargaining / negotiation language"
        }

        val auctionScore = kwScore(text, listOf("auction", "bid", "bidder", "vickrey", "sealed", "winner", "procurement", "tender", "reserve price", "second price", "first price", "vcg", "clarke"))
        if (auctionScore > 0.15) {
            scores[GameType.AUCTION] = (0.3 + auctionScore * 0.65).coerceAtMost(0.92)
            evidence += "Auction / mechanism design language (${"%.0f%%".format(auctionScore * 100)} keyword density)"
        }

        if (scenario.zeroSum) {
            scores[GameType.ZERO_SUM] = 0.95
            evidence += "Zero-sum flag set"
        }

        // Signalling: information asymmetry flag or signalling-related keywords
        val sigScore = kwScore(text, listOf("signal", "credib", "reputation", "certif", "credential", "verif", "screen", "adverse selection", "cheap talk", "commitment", "reveal", "private information"))
        if (sigScore > 0.15 || scenario.informationAsymmetry) {
            scores[GameType.SIGNALLING] = ((0.3 + sigScore * 0.7) * (if (scenario.informationAsymmetry) 1.3 else 1.0)).coerceAtMost(0.92)
            evidence += "Signalling/credibility language" + if (scenario.informationAsymmetry) " + information asymmetry flag" else ""
        }

        // First-mover: sequential game or explicit leadership/announcement language
        val fmScore = kwScore(text, listOf("first mover", "leader", "commit", "announce", "sequence", "move first", "pioneer", "preempt", "stackelberg", "anticipate response"))
        if ((fmScore > 0.12 || scenario.gameTree != null) && scenario.decisionOwner.isNotBlank()) {
            scores[GameType.FIRST_MOVER] = ((0.3 + fmScore * 0.65) * (if (scenario.gameTree != null) 1.25 else 1.0)).coerceAtMost(0.88)
            evidence += "First-mover / sequential game structure" + if (scenario.gameTree != null) " (game tree present)" else ""
        }

        val primary = if (scores.isEmpty()) GameType.GENERAL else scores.maxByOrNull { it.value }?.key ?: GameType.GENERAL
        return Classification(scores, primary, evidence)
    }

    private fun kwScore(text: String, keywords: List<String>): Double =
        keywords.count { kw -> text.contains(kw) } / keywords.size.toDouble()

    fun detectPD(scenario: Scenario): Double {
        val sf = MatrixSupport.build(scenario) ?: return 0.0
        if (sf.players.size != 2) return 0.0
        val s0 = sf.strategies[sf.players[0]] ?: return 0.0
        val s1 = sf.strategies[sf.players[1]] ?: return 0.0
        if (s0.size != 2 || s1.size != 2) return 0.0
        val r = sf.utility[0][sf.profileIndex(listOf(0, 0))]
        val s = sf.utility[0][sf.profileIndex(listOf(0, 1))]
        val t = sf.utility[0][sf.profileIndex(listOf(1, 0))]
        val p = sf.utility[0][sf.profileIndex(listOf(1, 1))]
        return if (t > r && r > p && p > s && 2 * r > t + s) 0.9 else 0.0
    }

    fun detectSH(scenario: Scenario): Double {
        val sf = MatrixSupport.build(scenario) ?: return 0.0
        if (sf.players.size != 2) return 0.0
        val s0 = sf.strategies[sf.players[0]] ?: return 0.0
        val s1 = sf.strategies[sf.players[1]] ?: return 0.0
        if (s0.size != 2 || s1.size != 2) return 0.0
        val nash00 = isNash2x2(sf, listOf(0, 0))
        val nash11 = isNash2x2(sf, listOf(1, 1))
        if (!nash00 || !nash11) return 0.0
        val a = sf.utility[0][sf.profileIndex(listOf(0, 0))]
        val d = sf.utility[0][sf.profileIndex(listOf(1, 1))]
        return if (a > d) 0.85 else 0.6
    }

    private fun isNash2x2(sf: MatrixSupport.StrategicForm, profile: List<Int>): Boolean {
        val base = sf.profileIndex(profile)
        for ((pi, _) in sf.players.withIndex()) {
            val size = sf.strategies[sf.players[pi]]!!.size
            val current = sf.utility[pi][base]
            for (alt in 0 until size) if (alt != profile[pi]) {
                val newProfile = profile.toMutableList().also { it[pi] = alt }
                if (sf.utility[pi][sf.profileIndex(newProfile)] > current + 1e-9) return false
            }
        }
        return true
    }
}
