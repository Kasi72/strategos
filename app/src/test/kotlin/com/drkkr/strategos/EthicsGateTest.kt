package com.drkkr.strategos

import com.drkkr.strategos.domain.human.EthicsGate
import com.drkkr.strategos.domain.human.Ethics
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.Tactic
import com.drkkr.strategos.domain.human.TacticCatalog
import com.drkkr.strategos.domain.human.TacticId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL3.2: the ethics gate (I-H2 to I-H5). Its test is Nudge's publicity principle plus the relationship domain. */
class EthicsGateTest {
    private val offered = TacticCatalog.offered
    private val closeDomains = RelationshipDomain.values().filter { it.isClose }
    private val deceptive = Tactic(TacticId.DEFAULT_WITH_DEADLINE, "L14", Ethics.DECEPTIVE, true, RelationshipDomain.values().toSet(), { _, _, _ -> true }, emptySet(), emptySet(), "x")
    private val hidden = Tactic(TacticId.DEFAULT_WITH_DEADLINE, "Ch.5", Ethics.BENIGN, false, RelationshipDomain.values().toSet(), { _, _, _ -> true }, emptySet(), emptySet(), "x")

    @Test
    fun TC_HL_ETH_01_neitherADeceptiveNorAHiddenTacticIsEverAllowed() {
        val ctxs = listOf<HumanContext?>(null, HumanContext(), HumanContext(theirVulnerable = "none"), HumanContext(theirTactics = listOf("hot_cold")))
        for (t in offered + deceptive + hidden) for (d in RelationshipDomain.values()) for (support in listOf(true, false)) for (c in ctxs) {
            val ok = EthicsGate.allowed(t, c, d, support)
            if (t.ethics == Ethics.DECEPTIVE || !t.publicityPass) assertFalse("${t.id} $d support=$support", ok)
        }
    }

    @Test
    fun TC_HL_ETH_02_closeRelationshipsGetBenignTacticsOnly() {
        for (t in offered.filter { it.ethics == Ethics.CONTEXT }) for (d in closeDomains)
            assertFalse("${t.id} in $d", EthicsGate.allowed(t, HumanContext(), d, false))
        // BENIGN ones are fine in a close domain, as long as the tactic lists it
        assertTrue(EthicsGate.allowed(offered.single { it.id == TacticId.PUT_IT_IN_WRITING }, HumanContext(), RelationshipDomain.FAMILY, false))
        assertTrue(EthicsGate.allowed(offered.single { it.id == TacticId.DEFAULT_WITH_DEADLINE }, null, RelationshipDomain.INTIMATE, false))
    }

    @Test
    fun TC_HL_ETH_02b_contextTacticsAreAllowedAtWorkWhereTheyListThem_andOnlyThere() {
        val stepBack = offered.single { it.id == TacticId.STEP_BACK }
        assertTrue(EthicsGate.allowed(stepBack, HumanContext(), RelationshipDomain.WORK_HIERARCHY, false))
        assertTrue(EthicsGate.allowed(stepBack, HumanContext(), RelationshipDomain.ADVERSARIAL, false))
        val concede = offered.single { it.id == TacticId.CONCEDE_SMALL_FIRST }
        assertTrue(EthicsGate.allowed(concede, HumanContext(), RelationshipDomain.NEGOTIATION, false))
        assertFalse("not listed for adversarial", EthicsGate.allowed(concede, HumanContext(), RelationshipDomain.ADVERSARIAL, false))
        assertFalse("apology is not offered where there may be legal exposure",
            EthicsGate.allowed(offered.single { it.id == TacticId.APOLOGISE_FOR_YOUR_PART }, HumanContext(), RelationshipDomain.ADVERSARIAL, false))
    }

    @Test
    fun TC_HL_ETH_03_aSafetyNeedAllowsNothing() {
        for (t in offered) for (d in RelationshipDomain.values()) assertFalse("${t.id} $d", EthicsGate.allowed(t, HumanContext(), d, support = true))
    }

    @Test
    fun TC_HL_ETH_04_aVulnerableCounterpartAllowsNothing() {
        for (v in listOf("elder_in_care", "child", "unwell", "in_crisis"))
            for (t in offered) for (d in RelationshipDomain.values()) assertFalse("$v ${t.id} $d", EthicsGate.allowed(t, HumanContext(theirVulnerable = v), d, false))
        assertTrue("'none' is not a vulnerability", EthicsGate.allowed(offered.first(), HumanContext(theirVulnerable = "none"), RelationshipDomain.WORK_PEER, false))
    }

    @Test
    fun TC_HL_ETH_05_coerciveBehaviourInACloseRelationshipAllowsNothing_atWorkItIsOrdinary() {
        for (tag in listOf("isolates_you", "keeps_you_dependent", "hot_cold")) {
            val c = HumanContext(theirTactics = listOf(tag))
            for (t in offered) for (d in closeDomains) assertFalse("$tag ${t.id} $d", EthicsGate.allowed(t, c, d, false))
            // the same behaviour at work is ordinary power dynamics: normal gating
            assertTrue(EthicsGate.allowed(offered.single { it.id == TacticId.DEFAULT_WITH_DEADLINE }, c, RelationshipDomain.WORK_PEER, false))
        }
        // a non-coercive behaviour does not block anything
        assertTrue(EthicsGate.allowed(offered.single { it.id == TacticId.DEFAULT_WITH_DEADLINE }, HumanContext(theirTactics = listOf("moves_goalposts")), RelationshipDomain.FAMILY, false))
    }
}
