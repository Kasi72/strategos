package com.drkkr.strategos.engine

import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.game.GameClass
import com.drkkr.strategos.domain.game.GameIR

object ScenarioRegistry {

    // ── S01: Medical Board Scoring (HARMONY) ────────────────────────────────
    // Both examiners have a dominant strategy: Strict Formula > Round Up.
    // Unique NE at (SF,SF); IEDS removes Round Up in one round.
    private val S01_MedicalBoardScoring = GameScenarioSpec(
        id   = "medical-board-scoring",
        name = "Medical Board Scoring",
        ir   = GameIR(
            scenarioId     = "medical-board-scoring",
            playerIds      = listOf("Examiner 1", "Examiner 2"),
            actionIds      = listOf(
                listOf("Strict Formula", "Round Up"),
                listOf("Strict Formula", "Round Up"),
            ),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(10.0, 10.0, 5.0, 0.0),
                doubleArrayOf(10.0,  5.0, 10.0, 0.0),
            ),
            gameClass   = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100,
        ),
        expected = GameScenarioSpec.Expected(
            archetype            = GameArchetype.HARMONY,
            pureNE               = listOf(listOf(0, 0)),
            iedsStrictRounds     = 1,
            iedsStrictSurviving  = listOf(1, 1),
            mixedNE              = null,
        ),
        meta = GameScenarioSpec.Meta(
            lesson = "Dominant strategies exist → IEDS resolves game in one round. " +
                     "NE is Pareto-optimal; no tension between individual and collective rationality.",
        ),
    )

    // ── S02: Medical Board Inspection / UDID (GENERIC — Inspection game) ────
    // No pure NE; unique interior mixed NE at (2/3, 1/3) × (2/3, 1/3).
    private val S02_MedicalBoardInspection = GameScenarioSpec(
        id   = "udid-inspection",
        name = "UDID Medical Board Inspection",
        ir   = GameIR(
            scenarioId     = "udid-inspection",
            playerIds      = listOf("Medical Board", "Claimant"),
            actionIds      = listOf(
                listOf("Routine Check", "Thorough Audit"),
                listOf("Genuine Presentation", "Exaggerate Symptoms"),
            ),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf( 5.0, -5.0, 0.0,  5.0),
                doubleArrayOf( 5.0, 10.0, 5.0, -5.0),
            ),
            gameClass   = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100,
        ),
        expected = GameScenarioSpec.Expected(
            archetype            = GameArchetype.GENERIC,
            pureNE               = emptyList(),
            iedsStrictRounds     = 0,
            iedsStrictSurviving  = listOf(2, 2),
            mixedNE              = GameScenarioSpec.MixedNESpec(
                probs = listOf(
                    doubleArrayOf(2.0 / 3.0, 1.0 / 3.0),
                    doubleArrayOf(2.0 / 3.0, 1.0 / 3.0),
                ),
            ),
        ),
        meta = GameScenarioSpec.Meta(
            lesson = "Classic inspection game. No pure NE: Board wants to audit when Claimant exaggerates; " +
                     "Claimant wants to exaggerate when Board does routine check — cycling. " +
                     "Only mixed NE exists; randomisation is the equilibrium strategy.",
        ),
    )

    // ── S03: Medical Board Assessment / Screening (PURE_COORDINATION, 3×2) ──
    // Asymmetric 3-action psychiatrist vs 2-action parent. Two pure NE on diagonal.
    // Parent payoffs identical across all Psychiatrist choices → parent structurally indifferent.
    // Standard interior mixed NE detection not applicable; screening NEs require EquilibriumVerifier.
    private val S03_MedicalBoardAssessment = GameScenarioSpec(
        id   = "medical-board-assessment",
        name = "Medical Board Assessment (Screening)",
        ir   = GameIR(
            scenarioId     = "medical-board-assessment",
            playerIds      = listOf("Psychiatrist", "Parent"),
            actionIds      = listOf(
                listOf("Grant Certificate", "Reject Application", "Mandate Observation"),
                listOf("Genuine Presentation", "Exaggerate Deficits"),
            ),
            strategyCounts = listOf(3, 2),
            utility = listOf(
                doubleArrayOf(10.0, -10.0, -10.0, 5.0, 0.0,  0.0),
                doubleArrayOf(10.0,  10.0,  -5.0, -5.0, -2.0, -2.0),
            ),
            gameClass   = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100,
        ),
        expected = GameScenarioSpec.Expected(
            archetype            = GameArchetype.PURE_COORDINATION,
            pureNE               = listOf(listOf(0, 0), listOf(1, 1)),
            iedsStrictRounds     = 0,
            iedsStrictSurviving  = listOf(3, 2),
            mixedNE              = null,
        ),
        meta = GameScenarioSpec.Meta(
            lesson = "3×2 screening game. Parent payoffs identical across all Psychiatrist actions → " +
                     "parent structurally indifferent; two diagonal pure NE. Mandate creates screening " +
                     "mixed NE families but standard NxM interior detection does not apply directly.",
        ),
    )

    // ── S04: Regional Campaign Allocation (ZERO_SUM) ────────────────────────
    // Matching-pennies structure. No pure NE; minimax gives 50/50 mixed NE, game value = 0.
    private val S04_RegionalCampaignAllocation = GameScenarioSpec(
        id   = "regional-campaign-allocation",
        name = "Regional Campaign Allocation",
        ir   = GameIR(
            scenarioId     = "regional-campaign-allocation",
            playerIds      = listOf("Incumbent Party", "Challenger Party"),
            actionIds      = listOf(
                listOf("Focus Healthcare", "Focus Infrastructure"),
                listOf("Focus Healthcare", "Focus Infrastructure"),
            ),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf( 5.0, -5.0, -5.0,  5.0),
                doubleArrayOf(-5.0,  5.0,  5.0, -5.0),
            ),
            gameClass   = GameClass.ZERO_SUM_2P,
            coveragePct = 100,
        ),
        expected = GameScenarioSpec.Expected(
            archetype            = GameArchetype.ZERO_SUM,
            pureNE               = emptyList(),
            iedsStrictRounds     = 0,
            iedsStrictSurviving  = listOf(2, 2),
            mixedNE              = GameScenarioSpec.MixedNESpec(
                probs = listOf(
                    doubleArrayOf(0.5, 0.5),
                    doubleArrayOf(0.5, 0.5),
                ),
            ),
        ),
        meta = GameScenarioSpec.Meta(
            lesson = "Zero-sum matching-pennies. Minimax theorem: both players randomise 50/50. " +
                     "Game value = 0. No pure NE exists.",
        ),
    )

    // ── S05: Resident Ward Cover (CHICKEN) ───────────────────────────────────
    // Action 0 = Prepare SDL (aggressive/defect), Action 1 = Cover Ward (yield/cooperate).
    // Two anti-diagonal pure NE. Mixed NE: P(Prepare SDL) = 5/19.
    // Indifference: 8-18p = 3+p → 5=19p → p=5/19; P(Cover Ward)=14/19.
    private val S05_ResidentWardCover = GameScenarioSpec(
        id   = "resident-ward-cover",
        name = "Resident Ward Cover",
        ir   = GameIR(
            scenarioId     = "resident-ward-cover",
            playerIds      = listOf("Resident 1", "Resident 2"),
            actionIds      = listOf(
                listOf("Prepare SDL", "Cover Ward"),
                listOf("Prepare SDL", "Cover Ward"),
            ),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(-10.0, 8.0, 4.0, 3.0),
                doubleArrayOf(-10.0, 4.0, 8.0, 3.0),
            ),
            gameClass   = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100,
        ),
        expected = GameScenarioSpec.Expected(
            archetype            = GameArchetype.CHICKEN,
            pureNE               = listOf(listOf(0, 1), listOf(1, 0)),
            iedsStrictRounds     = 0,
            iedsStrictSurviving  = listOf(2, 2),
            mixedNE              = GameScenarioSpec.MixedNESpec(
                probs = listOf(
                    doubleArrayOf(5.0 / 19.0, 14.0 / 19.0),
                    doubleArrayOf(5.0 / 19.0, 14.0 / 19.0),
                ),
            ),
        ),
        meta = GameScenarioSpec.Meta(
            lesson = "Chicken with asymmetric off-diagonal payoffs. Action 0=Prepare SDL (aggressive), " +
                     "action 1=Cover Ward (yield). Mixed NE P(SDL)=5/19≈0.263; P(Cover)=14/19≈0.737. " +
                     "Catastrophic mutual-aggression outcome at (SDL, SDL).",
        ),
    )

    // ── S06: Clinical Case Chicken (CHICKEN) ────────────────────────────────
    // Classic textbook Chicken. Mixed NE: P(Insist) = 1/3.
    private val S06_ClinicalCaseChicken = GameScenarioSpec(
        id   = "clinical-case-chicken",
        name = "Clinical Case Chicken",
        ir   = GameIR(
            scenarioId     = "clinical-case-chicken",
            playerIds      = listOf("Student 1", "Student 2"),
            actionIds      = listOf(
                listOf("Insist", "Yield"),
                listOf("Insist", "Yield"),
            ),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(-10.0, 10.0,  0.0, 5.0),
                doubleArrayOf(-10.0,  0.0, 10.0, 5.0),
            ),
            gameClass   = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100,
        ),
        expected = GameScenarioSpec.Expected(
            archetype            = GameArchetype.CHICKEN,
            pureNE               = listOf(listOf(0, 1), listOf(1, 0)),
            iedsStrictRounds     = 0,
            iedsStrictSurviving  = listOf(2, 2),
            mixedNE              = GameScenarioSpec.MixedNESpec(
                probs = listOf(
                    doubleArrayOf(1.0 / 3.0, 2.0 / 3.0),
                    doubleArrayOf(1.0 / 3.0, 2.0 / 3.0),
                ),
            ),
        ),
        meta = GameScenarioSpec.Meta(
            lesson = "Textbook Chicken. Severe mutual-aggression penalty (-10) forces 2/3 yielding in " +
                     "equilibrium. Expected payoff at mixed NE ≈ 3.33 for both.",
        ),
    )

    // ── S07: CDRC Friday Night Admission (CHICKEN — Brinkmanship) ───────────
    // Psychiatric bed dispute. Mixed NE: P(Refuse) = P(Push) = 2/5.
    private val S07_CdrcFridayNightAdmission = GameScenarioSpec(
        id   = "cdrc-friday-night-admission",
        name = "CDRC Friday Night Admission",
        ir   = GameIR(
            scenarioId     = "cdrc-friday-night-admission",
            playerIds      = listOf("Psychiatry PG Resident", "ER Duty Officer"),
            actionIds      = listOf(
                listOf("Refuse Bed", "Admit to Ward"),
                listOf("Push to Ward", "Keep in ER"),
            ),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(-20.0, 10.0, -5.0, 0.0),
                doubleArrayOf(-20.0, -5.0, 10.0, 0.0),
            ),
            gameClass   = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100,
        ),
        expected = GameScenarioSpec.Expected(
            archetype            = GameArchetype.CHICKEN,
            pureNE               = listOf(listOf(0, 1), listOf(1, 0)),
            iedsStrictRounds     = 0,
            iedsStrictSurviving  = listOf(2, 2),
            mixedNE              = GameScenarioSpec.MixedNESpec(
                probs = listOf(
                    doubleArrayOf(2.0 / 5.0, 3.0 / 5.0),
                    doubleArrayOf(2.0 / 5.0, 3.0 / 5.0),
                ),
            ),
        ),
        meta = GameScenarioSpec.Meta(
            lesson = "Brinkmanship Chicken. High mutual-aggression cost (-20) vs moderate asymmetric " +
                     "outcome (10/-5). Mixed NE: each party escalates 40% of the time.",
        ),
    )

    // ── S08: SDL Free Rider (PRISONERS_DILEMMA) ──────────────────────────────
    // CORRECTED: original matrix had P(0)<S(2) violating T>R>P>S.
    // Fix: (WH,SO)=0 and (SO,SO)=2 so T=10>R=8>P=2>S=0.
    private val S08_SdlFreeRider = GameScenarioSpec(
        id   = "sdl-module-free-rider",
        name = "SDL Module Free Rider",
        ir   = GameIR(
            scenarioId     = "sdl-module-free-rider",
            playerIds      = listOf("MBBS Student 1", "MBBS Student 2"),
            actionIds      = listOf(
                listOf("Work Hard", "Slack Off"),
                listOf("Work Hard", "Slack Off"),
            ),
            strategyCounts = listOf(2, 2),
            utility = listOf(
                doubleArrayOf(8.0,  0.0, 10.0, 2.0),
                doubleArrayOf(8.0, 10.0,  0.0, 2.0),
            ),
            gameClass   = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100,
        ),
        expected = GameScenarioSpec.Expected(
            archetype            = GameArchetype.PRISONERS_DILEMMA,
            pureNE               = listOf(listOf(1, 1)),
            iedsStrictRounds     = 1,
            iedsStrictSurviving  = listOf(1, 1),
            mixedNE              = null,
        ),
        meta = GameScenarioSpec.Meta(
            lesson = "Classic PD: dominant strategy (Slack Off) leads to mutually suboptimal NE (2,2) " +
                     "vs cooperative outcome (8,8). T=10>R=8>P=2>S=0.",
            corrections = listOf(
                GameScenarioSpec.Correction(
                    what = "Wrong PD payoff matrix: (WH,SO)=(2,10) and (SO,SO)=(0,0)",
                    why  = "P(0) < S(2) violated T>R>P>S ordering. Game was actually Chicken " +
                           "with NE at (WH,SO) and (SO,WH), not PD.",
                    fix  = "Changed to (WH,SO)=(0,10) and (SO,SO)=(2,2) so T=10>R=8>P=2>S=0.",
                ),
            ),
        ),
    )

    val ALL: List<GameScenarioSpec> = listOf(
        S01_MedicalBoardScoring,
        S02_MedicalBoardInspection,
        S03_MedicalBoardAssessment,
        S04_RegionalCampaignAllocation,
        S05_ResidentWardCover,
        S06_ClinicalCaseChicken,
        S07_CdrcFridayNightAdmission,
        S08_SdlFreeRider,
    )
}
