package com.drkkr.strategos.domain.game

/**
 * Classifies an opponent's behavioral profile from [OpponentModel] into a primary/secondary
 * behavior type, so the coach can tailor its framing accordingly.
 *
 * Uses all five learned priors: epsilon, discountFactor (original) plus riskAversion,
 * cooperationIndex, informationBelief (Phase 2).
 */
object OpponentActionClassifier {

    enum class BehaviorClass {
        COOPERATIVE,
        COMPETITIVE,
        RISK_AVERSE,
        RISK_SEEKING,
        INFORMATION_SHARING,
        INFORMATION_WITHHOLDING,
        REACTIVE,
        STRATEGIC
    }

    data class Classification(
        val primary: BehaviorClass,
        val secondary: BehaviorClass?,
        /** Confidence [0, 1]: strength of the top-scoring signal. */
        val confidence: Double,
        /** One-line coach note derived from the classification. */
        val note: String
    )

    private const val HI = 0.65
    private const val LO = 0.35

    fun classify(model: OpponentModel): Classification {
        val scores = mutableMapOf<BehaviorClass, Double>()

        val ci = model.cooperationIndex ?: 0.5
        if (ci > HI) scores[BehaviorClass.COOPERATIVE] = ci
        else if (ci < LO) scores[BehaviorClass.COMPETITIVE] = 1.0 - ci

        val ra = model.riskAversion ?: 0.5
        if (ra > HI) scores[BehaviorClass.RISK_AVERSE] = ra
        else if (ra < LO) scores[BehaviorClass.RISK_SEEKING] = 1.0 - ra

        val ib = model.informationBelief ?: 0.5
        if (ib > HI) scores[BehaviorClass.INFORMATION_SHARING] = ib
        else if (ib < LO) scores[BehaviorClass.INFORMATION_WITHHOLDING] = 1.0 - ib

        if (model.epsilon > HI && model.discountFactor < LO)
            scores[BehaviorClass.REACTIVE] = model.epsilon
        else if (model.epsilon < LO && model.discountFactor > HI)
            scores[BehaviorClass.STRATEGIC] = model.discountFactor

        val sorted = scores.entries.sortedByDescending { it.value }
        val primary = sorted.firstOrNull()?.key ?: BehaviorClass.STRATEGIC
        val secondary = sorted.getOrNull(1)?.key
        val confidence = sorted.firstOrNull()?.value ?: 0.5
        return Classification(primary, secondary, confidence, buildNote(primary, secondary))
    }

    private fun buildNote(primary: BehaviorClass, secondary: BehaviorClass?): String {
        val base = when (primary) {
            BehaviorClass.COOPERATIVE -> "likely to reciprocate concessions"
            BehaviorClass.COMPETITIVE -> "likely to press for unilateral gains"
            BehaviorClass.RISK_AVERSE -> "prefers certain outcomes, avoids gambles"
            BehaviorClass.RISK_SEEKING -> "willing to accept uncertain outcomes for higher upside"
            BehaviorClass.INFORMATION_SHARING -> "open to exchanging information"
            BehaviorClass.INFORMATION_WITHHOLDING -> "guards private information closely"
            BehaviorClass.REACTIVE -> "responds impulsively to immediate moves"
            BehaviorClass.STRATEGIC -> "weighs long-term consequences"
        }
        return if (secondary != null) "$base; also ${secondary.name.lowercase().replace('_', ' ')}"
        else base
    }
}
