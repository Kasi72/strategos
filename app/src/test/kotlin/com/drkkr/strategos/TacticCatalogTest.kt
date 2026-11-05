package com.drkkr.strategos

import com.drkkr.strategos.domain.human.BannedWords
import com.drkkr.strategos.domain.human.Ethics
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.TacticCatalog
import com.drkkr.strategos.domain.human.TacticId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL3.1: the tactic catalog (a tactic is a way of DELIVERING the engine's pick, never a new move). */
class TacticCatalogTest {
    private val offered = TacticCatalog.offered

    @Test
    fun TC_HL_CAT_01_nineteenTactics_sixteenBenign_threeContext_noneDeceptive() {
        assertEquals(19, offered.size)
        assertEquals(19, offered.map { it.id }.toSet().size)
        assertEquals(TacticId.values().toSet(), offered.map { it.id }.toSet())
        assertEquals(16, offered.count { it.ethics == Ethics.BENIGN })
        assertEquals(3, offered.count { it.ethics == Ethics.CONTEXT })
        assertEquals(0, offered.count { it.ethics == Ethics.DECEPTIVE })
        assertTrue(offered.all { it.publicityPass })
        assertEquals(
            setOf(TacticId.STEP_BACK, TacticId.CONCEDE_SMALL_FIRST, TacticId.NAME_YOUR_LEVERAGE),
            offered.filter { it.ethics == Ethics.CONTEXT }.map { it.id }.toSet()
        )
    }

    @Test
    fun TC_HL_CAT_02_everyHintIsShortPlainAndNamesNothing() {
        for (t in offered) {
            val words = t.coachHint.trim().split(Regex("\\s+")).size
            assertTrue("${t.id} hint has $words words", words in 1..20)
            assertFalse("${t.id} hint: ${t.coachHint}", BannedWords.containedIn(t.coachHint))
        }
    }

    @Test
    fun TC_HL_CAT_03_contextTacticsAreOnlyForWorkNegotiationAndAdversarial() {
        val allowed = setOf(RelationshipDomain.WORK_HIERARCHY, RelationshipDomain.WORK_PEER, RelationshipDomain.NEGOTIATION, RelationshipDomain.ADVERSARIAL)
        for (t in offered.filter { it.ethics == Ethics.CONTEXT }) assertTrue("${t.id}: ${t.domains}", t.domains.isNotEmpty() && allowed.containsAll(t.domains))
        // and every entry names at least one domain
        assertTrue(offered.all { it.domains.isNotEmpty() })
    }

    @Test
    fun TC_HL_SRC_01_everySourcePointsAtAChapterOrALaw() {
        for (t in offered) assertTrue("${t.id}: '${t.source}'", t.source.isNotBlank() && (t.source.contains("Ch.") || Regex("\\bL\\d").containsMatchIn(t.source)))
    }

    @Test
    fun TC_HL_CAT_04_theDefenceOnlyListIsTheTwelveLawsOfAppendixA() {
        assertEquals(setOf("L3", "L7", "L12", "L14", "L21", "L26", "L27", "L31", "L32", "L33", "L39", "L44"), TacticCatalog.defenceOnly)
        assertFalse("L15 is excluded entirely", "L15" in TacticCatalog.defenceOnly)
    }

    @Test
    fun TC_HL_CAT_05_everyTacticHasConsistentTagChanges() {
        val vocab = com.drkkr.strategos.domain.human.HumanVocab.OPTION_TAGS + com.drkkr.strategos.domain.human.HumanVocab.PLANNER_TAGS
        for (t in offered) {
            assertTrue("${t.id} adds ${t.adds}", vocab.containsAll(t.adds))
            assertTrue("${t.id} removes ${t.removes}", vocab.containsAll(t.removes))
            assertTrue("${t.id} adds and removes the same tag", t.adds.intersect(t.removes).isEmpty())
        }
        assertEquals(setOf("sets_default"), offered.single { it.id == TacticId.DEFAULT_WITH_DEADLINE }.adds)
        assertEquals(setOf("open_ended"), offered.single { it.id == TacticId.DEFAULT_WITH_DEADLINE }.removes)
    }

    @Test
    fun TC_HL_BAN_01_theBannedWordsCatchLawsBooksAuthorsAndTacticNames() {
        for (bad in listOf("48 Laws of Power", "Law 31 says", "law 4", "As Greene writes", "Thaler and Sunstein", "use DEFAULT_WITH_DEADLINE", "try say_less", "try STEP_BACK now"))
            assertTrue(bad, BannedWords.containedIn(bad))                                 // tactic names match in any case
        for (fine in listOf("a gentle nudge", "Nudge her toward Friday", "Sunita said no", "the law firm", "break the law", "say less about it", "step back for a day"))
            assertFalse(fine, BannedWords.containedIn(fine))                              // plain words are fine, including "nudge"
        assertTrue("a surname is banned wherever it appears", BannedWords.containedIn("Greene Street"))
    }
}
