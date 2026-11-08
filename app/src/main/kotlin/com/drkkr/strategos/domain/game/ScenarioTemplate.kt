package com.drkkr.strategos.domain.game

/**
 * Pre-built [GameIR] shells for the 8 archetypal strategic situations.
 *
 * These are canonical examples used for:
 *   - Onboarding / tutorial flows
 *   - Extraction validation (compare extracted model against expected archetype)
 *   - Testing the engine pipeline end-to-end
 *
 * Payoffs are on the standard 0–10 scale.
 */
object ScenarioTemplate {

    /** Classic Prisoner's Dilemma: T=10 > R=8 > P=2 > S=0. */
    val PRISONERS_DILEMMA = GameIR(
        scenarioId     = "template-prisoners-dilemma",
        playerIds      = listOf("Player 1", "Player 2"),
        actionIds      = listOf(listOf("Cooperate", "Defect"), listOf("Cooperate", "Defect")),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf(8.0, 0.0, 10.0, 2.0),
            doubleArrayOf(8.0, 10.0, 0.0, 2.0),
        ),
        gameClass   = GameClass.FINITE_NORMAL_FORM,
        coveragePct = 100,
    )

    /** Chicken / Hawk-Dove: mutual aggression is worst, mutual yield is second-best. */
    val CHICKEN = GameIR(
        scenarioId     = "template-chicken",
        playerIds      = listOf("Player 1", "Player 2"),
        actionIds      = listOf(listOf("Aggressive", "Yield"), listOf("Aggressive", "Yield")),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf(-10.0, 10.0, 0.0, 5.0),
            doubleArrayOf(-10.0, 0.0, 10.0, 5.0),
        ),
        gameClass   = GameClass.FINITE_NORMAL_FORM,
        coveragePct = 100,
    )

    /** Stag Hunt: mutual cooperation is best, unilateral safe play is second-best. */
    val STAG_HUNT = GameIR(
        scenarioId     = "template-stag-hunt",
        playerIds      = listOf("Player 1", "Player 2"),
        actionIds      = listOf(listOf("Hunt Stag", "Hunt Hare"), listOf("Hunt Stag", "Hunt Hare")),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf(10.0, 0.0, 3.0, 3.0),
            doubleArrayOf(10.0, 3.0, 0.0, 3.0),
        ),
        gameClass   = GameClass.FINITE_NORMAL_FORM,
        coveragePct = 100,
    )

    /** Battle of the Sexes: two pure NE with conflicting preferences. */
    val BATTLE_OF_SEXES = GameIR(
        scenarioId     = "template-battle-of-sexes",
        playerIds      = listOf("Player 1", "Player 2"),
        actionIds      = listOf(listOf("Option A", "Option B"), listOf("Option A", "Option B")),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf(10.0, 0.0, 0.0, 7.0),
            doubleArrayOf(7.0,  0.0, 0.0, 10.0),
        ),
        gameClass   = GameClass.FINITE_NORMAL_FORM,
        coveragePct = 100,
    )

    /** Zero-sum matching-pennies: no pure NE, 50/50 mixed NE. */
    val ZERO_SUM = GameIR(
        scenarioId     = "template-zero-sum",
        playerIds      = listOf("Player 1", "Player 2"),
        actionIds      = listOf(listOf("Heads", "Tails"), listOf("Heads", "Tails")),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf( 5.0, -5.0, -5.0,  5.0),
            doubleArrayOf(-5.0,  5.0,  5.0, -5.0),
        ),
        gameClass   = GameClass.ZERO_SUM_2P,
        coveragePct = 100,
    )

    /** Ultimatum game: proposer makes offer, responder accepts or rejects. Uses sequential structure note in meta. */
    val ULTIMATUM = GameIR(
        scenarioId     = "template-ultimatum",
        playerIds      = listOf("Proposer", "Responder"),
        actionIds      = listOf(
            listOf("Aggressive Offer (80/20)", "Fair Offer (50/50)"),
            listOf("Accept", "Reject"),
        ),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf(8.0, 0.0, 5.0, 0.0),
            doubleArrayOf(2.0, 0.0, 5.0, 0.0),
        ),
        gameClass   = GameClass.FINITE_NORMAL_FORM,
        coveragePct = 100,
    )

    /** Public goods / Stag Hunt variant with coordination failure risk. */
    val PUBLIC_GOODS = GameIR(
        scenarioId     = "template-public-goods",
        playerIds      = listOf("Contributor 1", "Contributor 2"),
        actionIds      = listOf(listOf("Contribute", "Free-ride"), listOf("Contribute", "Free-ride")),
        strategyCounts = listOf(2, 2),
        utility = listOf(
            doubleArrayOf(8.0, 0.0, 10.0, 2.0),
            doubleArrayOf(8.0, 10.0, 0.0, 2.0),
        ),
        gameClass   = GameClass.FINITE_NORMAL_FORM,
        coveragePct = 100,
    )

    /** Screening / signalling: informed party signals type, uninformed party responds. 3×2. */
    val SCREENING = GameIR(
        scenarioId     = "template-screening",
        playerIds      = listOf("Informed Party", "Screener"),
        actionIds      = listOf(
            listOf("Signal High", "Signal Low", "No Signal"),
            listOf("Accept", "Reject"),
        ),
        strategyCounts = listOf(3, 2),
        utility = listOf(
            doubleArrayOf(10.0, -5.0, 7.0, -5.0, 3.0, 0.0),
            doubleArrayOf(10.0,  5.0, 3.0,  5.0, 0.0, 0.0),
        ),
        gameClass   = GameClass.FINITE_NORMAL_FORM,
        coveragePct = 100,
    )

    val ALL: List<GameIR> = listOf(
        PRISONERS_DILEMMA, CHICKEN, STAG_HUNT, BATTLE_OF_SEXES,
        ZERO_SUM, ULTIMATUM, PUBLIC_GOODS, SCREENING,
    )
}
