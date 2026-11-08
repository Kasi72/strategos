package com.drkkr.strategos.engine

import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.game.GameIR

data class GameScenarioSpec(
    val id: String,
    val name: String,
    val ir: GameIR,
    val expected: Expected,
    val meta: Meta,
) {
    data class Expected(
        val archetype: GameArchetype,
        val pureNE: List<List<Int>>,
        val iedsStrictRounds: Int,
        val iedsStrictSurviving: List<Int>,
        val mixedNE: MixedNESpec?,
    )

    data class MixedNESpec(
        val probs: List<DoubleArray>,
        val tol: Double = 1e-4,
    )

    data class Meta(
        val lesson: String,
        val corrections: List<Correction> = emptyList(),
    )

    data class Correction(
        val what: String,
        val why: String,
        val fix: String,
    )

    override fun toString() = "[$id] $name"
}
