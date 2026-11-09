package com.drkkr.strategos.domain.game

/**
 * A type in a Bayesian game: a private type the opponent might be, with their adjusted payoff
 * vector and the prior probability they are this type.
 *
 * @param typeId       identifier, e.g. "cooperative" or "competitive"
 * @param payoffVector payoffs for each action profile from this type's perspective (same length as IR utility row)
 * @param prior        prior probability this type describes the opponent; priors across all types must sum to 1.0
 */
data class PlayerType(
    val typeId: String,
    val payoffVector: DoubleArray,
    val prior: Double
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerType) return false
        return typeId == other.typeId && prior == other.prior && payoffVector.contentEquals(other.payoffVector)
    }

    override fun hashCode(): Int {
        var result = typeId.hashCode()
        result = 31 * result + payoffVector.contentHashCode()
        result = 31 * result + prior.hashCode()
        return result
    }
}
