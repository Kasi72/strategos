package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.CoachGuideSketch
import com.drkkr.strategos.domain.agent.GuidanceCodec
import com.drkkr.strategos.domain.human.BannedWords
import com.drkkr.strategos.domain.human.EthicsGate
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.HumanVocab
import com.drkkr.strategos.domain.human.ManipulationRadar
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.TacticCatalog
import com.drkkr.strategos.domain.human.TacticId
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL5.1: the watch-for radar (what is being done TO the user, and what protects them). */
class ManipulationRadarTest {
    private val sixteen = (HumanVocab.THEIR_TACTICS - "none").toList()
    private fun ctx(vararg tags: String, turns: Int = 2) = HumanContext(theirTactics = tags.toList(), tacticTurns = tags.associateWith { turns })

    @Test
    fun TC_HL_RADAR_01_everyTagGivesOneWatchForAtWork_exceptTheCoerciveOnesAtHome() {
        assertEquals(16, sixteen.size)
        for (tag in sixteen) {
            val work = ManipulationRadar.scan(ctx(tag), RelationshipDomain.WORK_PEER)
            assertFalse(tag, work.routeToSupport)
            val w = work.watchFor.single()
            assertEquals(tag, w.pattern)
            assertTrue("$tag sign", w.sign.isNotBlank()); assertTrue("$tag protect", w.protect.isNotBlank()); assertTrue("$tag source", w.source.isNotBlank())
            if (tag in EthicsGate.COERCIVE) {
                for (d in listOf(RelationshipDomain.INTIMATE, RelationshipDomain.FAMILY)) {
                    val home = ManipulationRadar.scan(ctx(tag), d)
                    assertTrue("$tag in $d", home.routeToSupport); assertTrue(home.watchFor.isEmpty())
                }
                // decided at the CP-C review: in a friendship these are ordinary watch-for items
                val friend = ManipulationRadar.scan(ctx(tag), RelationshipDomain.FRIENDSHIP)
                assertFalse("$tag in a friendship", friend.routeToSupport); assertEquals(1, friend.watchFor.size)
            } else {
                val family = ManipulationRadar.scan(ctx(tag), RelationshipDomain.FAMILY)
                assertFalse("$tag in a family is an ordinary watch-for", family.routeToSupport)
                assertEquals(1, family.watchFor.size)
            }
        }
    }

    @Test
    fun TC_HL_RADAR_02_hotAndColdInAPartnershipGoesToSupportOnTheSecondTurn_isolationOnTheFirst() {
        val once = ManipulationRadar.scan(ctx("hot_cold", turns = 1), RelationshipDomain.INTIMATE)
        assertFalse("hot_cold alone, once", once.routeToSupport); assertTrue(once.watchFor.isEmpty())
        val twice = ManipulationRadar.scan(ctx("hot_cold", turns = 2), RelationshipDomain.INTIMATE)
        assertTrue(twice.routeToSupport); assertTrue(twice.watchFor.isEmpty())
        assertTrue(ManipulationRadar.scan(ctx("isolates_you", turns = 1), RelationshipDomain.INTIMATE).routeToSupport)
    }

    @Test
    fun TC_HL_RADAR_03_atMostTwoEntries_newestFirst() {
        val r = ManipulationRadar.scan(ctx("moves_goalposts", "flattery_before_ask", "creates_chaos", "extreme_anchor"), RelationshipDomain.WORK_PEER)
        assertEquals(listOf("moves_goalposts", "flattery_before_ask"), r.watchFor.map { it.pattern })
    }

    @Test
    fun TC_HL_RADAR_04_theWordsAreMadeForTheUser_noBookNoLawNoTacticName() {
        for (d in listOf(RelationshipDomain.WORK_PEER, RelationshipDomain.WORK_HIERARCHY, RelationshipDomain.NEGOTIATION)) for (tag in sixteen) {
            val w = ManipulationRadar.scan(ctx(tag), d).watchFor.single()
            for (text in listOf(w.sign, w.protect)) {
                assertFalse("$tag: $text", BannedWords.containedIn(text))
                assertFalse("$tag leaks its source: $text", text.contains(w.source))
                assertFalse("$tag leaks its own name: $text", text.contains(tag, ignoreCase = true))
            }
            assertFalse(w.sign.contains("manipulat", ignoreCase = true))              // describes behaviour, never accuses
        }
    }

    @Test
    fun TC_HL_RADAR_07_anOrdinaryTacticNeedsToBeSeenOnTwoTurnsBeforeItIsShown() {
        assertTrue(ManipulationRadar.scan(ctx("moves_goalposts", turns = 1), RelationshipDomain.WORK_PEER).watchFor.isEmpty())
        assertEquals(1, ManipulationRadar.scan(ctx("moves_goalposts", turns = 2), RelationshipDomain.WORK_PEER).watchFor.size)
        // one confirmed and one not: only the confirmed one
        val mixed = HumanContext(theirTactics = listOf("hot_cold", "creates_chaos"), tacticTurns = mapOf("creates_chaos" to 2, "hot_cold" to 1))
        assertEquals(listOf("creates_chaos"), ManipulationRadar.scan(mixed, RelationshipDomain.WORK_PEER).watchFor.map { it.pattern }.filter { it == "creates_chaos" })
        assertTrue(ManipulationRadar.scan(HumanContext(), RelationshipDomain.INTIMATE).watchFor.isEmpty())
        assertFalse(ManipulationRadar.scan(null, RelationshipDomain.INTIMATE).routeToSupport)
    }

    @Test
    fun TC_HL_RADAR_08_theContextCountsTheTurnsATacticWasDescribed() {
        val a = HumanContext().merge(HumanContext(theirTactics = listOf("moves_goalposts")))
        assertEquals(1, a.tacticTurns["moves_goalposts"])
        val b = a.merge(HumanContext(theirTactics = listOf("moves_goalposts", "hot_cold")))
        assertEquals(2, b.tacticTurns["moves_goalposts"]); assertEquals(1, b.tacticTurns["hot_cold"])
        val c = b.merge(HumanContext(yourStateLevel = 1.0))                               // a turn that says nothing about them
        assertEquals(b.tacticTurns, c.tacticTurns)
        assertEquals(listOf("moves_goalposts"), b.confirmedTactics())
    }

    @Test
    fun TC_HL_RADAR_09_tacticsThatReadTheirBehaviourWaitForItToBeConfirmed() {
        val stepBack = TacticCatalog.offered.single { it.id == TacticId.STEP_BACK }
        val yardstick = TacticCatalog.offered.single { it.id == TacticId.SHARED_OUTSIDE_YARDSTICK }
        val a = Action(stakeholderId = "you", label = "x")
        val s = Scenario(title = "t")
        assertFalse(stepBack.preconditions(ctx("silent_treatment", turns = 1), a, s))
        assertTrue(stepBack.preconditions(ctx("silent_treatment", turns = 2), a, s))
        assertFalse(yardstick.preconditions(ctx("moves_goalposts", turns = 1), a, s))
        assertTrue(yardstick.preconditions(ctx("extreme_anchor", turns = 2), a, s))
    }

    @Test
    fun TC_HL_RADAR_06_anOldGuidancePayloadStillDecodes_andCarriesWatchForNow() {
        val old = """{"what_happened":"x","clear_win_move":"y","exact_words":"z","do_this":["a"],"dont_do_this":["b"],"follow_up":"f","pill":"p"}"""
        val g = GuidanceCodec.decode(old)
        assertNotNull(g); assertTrue(g!!.watchFor.isEmpty()); assertEquals(listOf("a"), g.doThis)
        val roundTrip = GuidanceCodec.decode(GuidanceCodec.encode(CoachGuideSketch(doThis = listOf("a"), watchFor = listOf("They keep moving the target. Agree the finish line in writing first."))))!!
        assertEquals(listOf("They keep moving the target. Agree the finish line in writing first."), roundTrip.watchFor)
    }
}
