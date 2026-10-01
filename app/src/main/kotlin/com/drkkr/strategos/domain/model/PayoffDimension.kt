package com.drkkr.strategos.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class PayoffDimension(val label: String, val higherIsBetter: Boolean = true) {
    SAFETY("Safety"),
    ETHICS("Ethics"),
    LEGALITY("Legality"),
    COST("Cost", higherIsBetter = false),
    REPUTATION("Reputation"),
    RELATIONSHIPS("Relationships"),
    WELLBEING("Wellbeing"),
    EFFICIENCY("Efficiency"),
    PUBLIC_TRUST("Public Trust"),
    LONG_TERM_VALUE("Long-term Value")
}

@Serializable
data class MultiPayoff(val values: Map<PayoffDimension, Double> = emptyMap()) {
    fun weighted(weights: Map<PayoffDimension, Double>): Double =
        values.entries.sumOf { (dim, v) ->
            val w = weights[dim] ?: 0.0
            val signed = if (dim.higherIsBetter) v else -v
            signed * w
        }

    fun scalar(): Double = values.values.sum()

    operator fun plus(other: MultiPayoff): MultiPayoff {
        val keys = values.keys + other.values.keys
        return MultiPayoff(keys.associateWith { (values[it] ?: 0.0) + (other.values[it] ?: 0.0) })
    }
}
