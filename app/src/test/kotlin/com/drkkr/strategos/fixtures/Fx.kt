package com.drkkr.strategos.fixtures

import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.templates.CrisisManagementTemplate
import com.drkkr.strategos.templates.InstitutionalHealthcareTemplate
import com.drkkr.strategos.templates.ProcurementTemplate
import com.drkkr.strategos.templates.ResourceAllocationTemplate

/** Shared test fixtures (TEST_CASES §0). */
object Fx {

    private fun payoff(v: Double) = MultiPayoff(mapOf(PayoffDimension.LONG_TERM_VALUE to v))

    /** Prisoner's dilemma: players p1 and p2, actions c1/d1 and c2/d2, full payoffs, all USER_ESTIMATE. */
    fun pd(): Scenario {
        val payoffs = listOf(
            PayoffEntry("p1", mapOf("p1" to "c1", "p2" to "c2"), payoff(3.0)),
            PayoffEntry("p1", mapOf("p1" to "c1", "p2" to "d2"), payoff(0.0)),
            PayoffEntry("p1", mapOf("p1" to "d1", "p2" to "c2"), payoff(5.0)),
            PayoffEntry("p1", mapOf("p1" to "d1", "p2" to "d2"), payoff(1.0)),
            PayoffEntry("p2", mapOf("p1" to "c1", "p2" to "c2"), payoff(3.0)),
            PayoffEntry("p2", mapOf("p1" to "c1", "p2" to "d2"), payoff(5.0)),
            PayoffEntry("p2", mapOf("p1" to "d1", "p2" to "c2"), payoff(0.0)),
            PayoffEntry("p2", mapOf("p1" to "d1", "p2" to "d2"), payoff(1.0))
        )
        return Scenario(
            id = "pd", title = "Prisoner's Dilemma",
            stakeholders = listOf(Stakeholder(id = "p1", name = "P1"), Stakeholder(id = "p2", name = "P2")),
            actions = listOf(
                Action(id = "c1", stakeholderId = "p1", label = "Cooperate"),
                Action(id = "d1", stakeholderId = "p1", label = "Defect"),
                Action(id = "c2", stakeholderId = "p2", label = "Cooperate"),
                Action(id = "d2", stakeholderId = "p2", label = "Defect")
            ),
            payoffs = payoffs
        )
    }

    /** [pd] plus an INFLUENCER "Media" (m1) with no actions. */
    fun pdWithInfluencer(): Scenario = pd().let {
        it.copy(stakeholders = it.stakeholders + Stakeholder(id = "m1", name = "Media", gameRole = GameRole.INFLUENCER))
    }

    /** [pd] with the first [n] payoff entries set to AI_INFERRED. */
    fun pdAiPayoffs(n: Int): Scenario = pd().let { s ->
        s.copy(payoffs = s.payoffs.mapIndexed { i, p -> if (i < n) p.copy(factStatus = FactStatus.AI_INFERRED) else p })
    }

    /**
     * Guided example: "You" and "Brother" are players; "Wife" (can veto) and "Parents" are influencers.
     * Dimensions WELLBEING, RELATIONSHIPS, COST; full 3x3 payoffs for both players, all USER_ESTIMATE.
     */
    fun loanFamily(): Scenario {
        val mine = listOf("cosign", "give", "wait")
        val his = listOf("accept", "refuse", "askp")
        val payoffs = buildList {
            mine.forEachIndexed { i, a ->
                his.forEachIndexed { j, b ->
                    val profile = mapOf("you" to a, "bro" to b)
                    add(PayoffEntry("you", profile, MultiPayoff(mapOf(
                        PayoffDimension.WELLBEING to (3.0 + i + j), PayoffDimension.RELATIONSHIPS to (6.0 - j), PayoffDimension.COST to (8.0 - 3 * i)))))
                    add(PayoffEntry("bro", profile, MultiPayoff(mapOf(
                        PayoffDimension.WELLBEING to (8.0 - 2 * i - j), PayoffDimension.RELATIONSHIPS to (5.0 + i - j), PayoffDimension.COST to (2.0 + j)))))
                }
            }
        }
        return Scenario(
            id = "loan", title = "Loan for my brother", decisionOwner = "You",
            description = "My brother wants me to co-sign a large loan and my wife is against it.",
            stakes = "Family peace and our savings",
            stakeholders = listOf(
                Stakeholder(id = "you", name = "You", authority = 8, goals = listOf("Help without risking our savings")),
                Stakeholder(id = "bro", name = "Brother", influence = 6, goals = listOf("Get the loan"), fears = listOf("Losing face")),
                Stakeholder(id = "wife", name = "Wife", gameRole = GameRole.INFLUENCER, canVeto = true, fears = listOf("Debt")),
                Stakeholder(id = "parents", name = "Parents", gameRole = GameRole.INFLUENCER, influence = 4, goals = listOf("Keep the family together"))
            ),
            actions = listOf(
                Action(id = "cosign", stakeholderId = "you", label = "Co-sign"),
                Action(id = "give", stakeholderId = "you", label = "Give a fixed amount"),
                Action(id = "wait", stakeholderId = "you", label = "Wait"),
                Action(id = "accept", stakeholderId = "bro", label = "Accept"),
                Action(id = "refuse", stakeholderId = "bro", label = "Refuse"),
                Action(id = "askp", stakeholderId = "bro", label = "Ask parents")
            ),
            payoffs = payoffs,
            constraints = listOf(Constraint(id = "peace", label = "Don't risk peace at home", kind = Constraint.Kind.ETHICAL))
        )
    }


    // ── Human Layer fixtures (TEST_CASES) ────────────────────────────────────────────────────────

    private fun hlPayoffs(you: String, them: String, mine: List<String>, his: List<String>, theirScores: (Int, Int) -> List<Double>): List<PayoffEntry> {
        val dims = listOf(PayoffDimension.LONG_TERM_VALUE, PayoffDimension.RELATIONSHIPS, PayoffDimension.COST)
        return buildList {
            mine.forEachIndexed { i, a ->
                his.forEachIndexed { j, b ->
                    val profile = mapOf(you to a, them to b)
                    add(PayoffEntry(you, profile, MultiPayoff(dims.zip(listOf(6.0 - j, 6.0 - j, 4.0 + i)).toMap())))
                    add(PayoffEntry(them, profile, MultiPayoff(dims.zip(theirScores(i, j)).toMap())))
                }
            }
        }
    }

    /**
     * Raise story: "You" (repair a1, boundary a2, reactive a3) and "Manager" (receptive r1, defensive r2). Dims LONG_TERM_VALUE,
     * RELATIONSHIPS, COST; all 6 rows filled. The manager is the higher-status side. Their defensive reply to the boundary move (a2)
     * is the status-quo row: LTV 5, RELATIONSHIPS 6, COST 3.
     */
    fun hlRaise(): Scenario = Scenario(
        id = "hl-raise", title = "Asking for a raise", decisionOwner = "You", stakes = "My pay and my standing", repeated = true,
        stakeholders = listOf(Stakeholder(id = "you", name = "You", authority = 3), Stakeholder(id = "mgr", name = "Manager", authority = 8, influence = 5)),
        actions = listOf(
            Action(id = "a1", stakeholderId = "you", label = "Ask with a plan", description = "[posture:repair]"),
            Action(id = "a2", stakeholderId = "you", label = "Hold the line", description = "[posture:boundary]"),
            Action(id = "a3", stakeholderId = "you", label = "Threaten to leave", description = "[posture:reactive]"),
            Action(id = "r1", stakeholderId = "mgr", label = "Engage", description = "[posture:receptive]"),
            Action(id = "r2", stakeholderId = "mgr", label = "Put it off", description = "[posture:defensive]")
        ),
        payoffs = hlPayoffs("you", "mgr", listOf("a1", "a2", "a3"), listOf("r1", "r2")) { i, j ->
            if (j == 0) listOf(8.0 - 2 * i, 8.0 - 2 * i, 3.0) else listOf(5.0, 6.0, 3.0)      // [LTV, REL, COST] for the manager
        },
        humanContext = HumanContext(relationship = "manager", theirPower = "they_higher")
    )

    /** The loan story, with the human context of a sibling who would keep something. */
    fun hlLoan(): Scenario = loanFamily().copy(
        humanContext = HumanContext(relationship = "sibling", statusQuoForThem = "keeps_something")
    )

    /** A relational-repair 2x3 with a furious user. */
    fun hlSpouse(): Scenario = Scenario(
        id = "hl-spouse", title = "Hurt about the money", decisionOwner = "You", stakes = "Trust at home", repeated = true,
        stakeholders = listOf(Stakeholder(id = "you", name = "You", authority = 5), Stakeholder(id = "meera", name = "Meera", authority = 5, influence = 5)),
        actions = listOf(
            Action(id = "s1", stakeholderId = "you", label = "Tell her calmly it hurt", description = "[posture:repair]"),
            Action(id = "s2", stakeholderId = "you", label = "Hold the topic until morning", description = "[posture:boundary]"),
            Action(id = "s3", stakeholderId = "you", label = "Send the angry message", description = "[posture:reactive]"),
            Action(id = "t1", stakeholderId = "meera", label = "Listen", description = "[posture:receptive]"),
            Action(id = "t2", stakeholderId = "meera", label = "Shut down", description = "[posture:defensive]")
        ),
        payoffs = hlPayoffs("you", "meera", listOf("s1", "s2", "s3"), listOf("t1", "t2")) { i, j ->
            if (j == 0) listOf(8.0 - 2 * i, 8.0 - 2 * i, 3.0) else listOf(4.0, 4.0 + i, 4.0)
        },
        humanContext = HumanContext(relationship = "spouse", yourStateLevel = 3.0)
    )

    // ── Power Mode fixtures (docs/power-mode/TEST_CASES.md); trust 50 is passed to the planner by each test ──────────────

    /** A vendor price negotiation: walk-away 40000, no opening yet, a strong alternative against their weak one. */
    fun pmVendor(): Scenario = hlRaise().copy(
        id = "pm-vendor", title = "Vendor price",
        negotiationContext = NegotiationContext(reservationPoint = "40000"),
        humanContext = HumanContext(relationship = "vendor", yourAlternative = "strong", theirAlternative = "weak")
    )

    /** A co-founder who depends on the user somewhat (WORK_PEER, continuing). */
    fun pmCofounder(): Scenario = hlRaise().copy(
        id = "pm-cofounder", title = "Co-founder split",
        humanContext = HumanContext(relationship = "cofounder", theyDependOnYou = "some")
    )

    /** A manager who depends on the user a lot (WORK_HIERARCHY, continuing); add `probes_you` where a test says so. */
    fun pmManager(probes: Boolean = false): Scenario = hlRaise().copy(
        id = "pm-manager", title = "Raise",
        humanContext = HumanContext(relationship = "manager", theyDependOnYou = "high", theirTactics = if (probes) listOf("probes_you") else emptyList())
    )

    /** A spouse (INTIMATE). */
    fun pmSpouse(): Scenario = hlSpouse().copy(id = "pm-spouse")

    /** The 4 built-in templates. */
    fun templates(): List<Scenario> = listOf(
        CrisisManagementTemplate.create(), InstitutionalHealthcareTemplate.create(),
        ProcurementTemplate.create(), ResourceAllocationTemplate.create()
    )

    /** A 1.x scenario: no factStatus and no gameRole fields anywhere. */
    val v1ScenarioJson: String = """
        {
          "id": "v1",
          "title": "Legacy",
          "stakeholders": [
            {"id": "s1", "name": "Alice"},
            {"id": "s2", "name": "Bob"}
          ],
          "actions": [
            {"id": "a1", "stakeholderId": "s1", "label": "Go"},
            {"id": "a2", "stakeholderId": "s2", "label": "Stay"}
          ],
          "constraints": [
            {"id": "k1", "label": "Law", "kind": "LEGAL"}
          ],
          "payoffs": [
            {"stakeholderId": "s1", "actionProfile": {"s1": "a1", "s2": "a2"},
             "payoff": {"values": {"COST": 2.0}}}
          ]
        }
    """.trimIndent()
}
