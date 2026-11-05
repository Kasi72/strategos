package com.drkkr.strategos

import com.drkkr.strategos.domain.human.Ethics
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.TacticCatalog
import com.drkkr.strategos.domain.human.power.StrategicCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Power Mode, PM2.1: the 15 STRATEGIC (hardball) tactics, kept apart from the Human Layer's catalog. */
class StrategicCatalogTest {
    private val all = StrategicCatalog.tactics

    @Test fun TC_PM_CAT_01_fifteenStrategicTacticsAndTheHumanLayerCountsAreUnchanged() {
        assertEquals(15, all.size)
        assertEquals(15, all.map { it.id }.toSet().size)
        assertTrue(all.all { it.tactic.ethics == Ethics.STRATEGIC })
        assertEquals(19, TacticCatalog.offered.size)
    }

    @Test fun TC_PM_CAT_02_everyEntryNamesItsSource() {
        for (t in all) {
            assertTrue(t.id.name, t.tactic.source.isNotBlank())
            assertTrue("${t.id}: ${t.tactic.source}", Regex("""L\d|Ch\.""").containsMatchIn(t.tactic.source))
        }
    }

    @Test fun TC_PM_CAT_03_hintsAreShortAndNeverAskForAFalseFact() {
        val banned = listOf("pretend", "fake", "make them think", "lie", "invent", "bluff about facts")
        for (t in all) {
            val h = t.tactic.coachHint
            assertTrue("${t.id}: ${h.split(Regex("\\s+")).size} words", h.split(Regex("\\s+")).size <= 20)
            for (b in banned) assertFalse("${t.id} says \"$b\"", Regex("""(?i)\b${Regex.escape(b)}\b""").containsMatchIn(h))
            if (Regex("""(?i)\b(?:other|another) (?:offers?|bidders?)\b|\bbidders?\b|\balternatives?\b|\bother options\b""").containsMatchIn(h))
                assertTrue("${t.id} mentions other offers without \"real\"", Regex("""(?i)\breal\b""").containsMatchIn(h))
        }
    }

    @Test fun TC_PM_CAT_04_neverInACloseRelationship() {
        for (t in all) assertTrue(t.id.name, t.tactic.domains.none { it.isClose })
        assertTrue(all.flatMap { it.tactic.domains }.toSet().all { it in setOf(RelationshipDomain.NEGOTIATION, RelationshipDomain.WORK_HIERARCHY, RelationshipDomain.WORK_PEER, RelationshipDomain.ADVERSARIAL) })
    }

    @Test fun TC_PM_CAT_05_gainsAndLossesAreBounded() {
        for (t in all) {
            assertTrue("${t.id} gain ${t.gain}", t.gain > 0.0 && t.gain <= 1.5)
            assertTrue("${t.id} exposureLoss ${t.exposureLoss}", t.exposureLoss >= 0.0 && t.exposureLoss <= 1.0)
        }
    }

    @Test fun theBlueprintTableValues() {
        val byId = all.associateBy { it.id.name }
        val expected = mapOf(
            "HOLD_YOUR_NUMBER" to (0.8 to 0.2), "FIRM_POSTURE" to (0.7 to 0.5), "AGGRESSIVE_ANCHOR" to (1.0 to 0.4),
            "LET_THEM_MOVE_FIRST" to (0.6 to 0.1), "STRATEGIC_SILENCE" to (0.5 to 0.2), "COLOR_THE_CHOICES" to (0.6 to 0.4),
            "REAL_COMPETITION" to (1.2 to 0.3), "CREDIBLE_WALKAWAY" to (1.0 to 0.3), "CONTROL_THE_CLOCK" to (0.7 to 0.3),
            "DONT_SHOW_YOUR_HAND" to (0.5 to 0.2), "GO_TO_THE_DECIDER" to (0.8 to 0.5), "BUILD_INDISPENSABILITY" to (0.9 to 0.2),
            "ALLIES_FIRST" to (0.8 to 0.2), "STAY_UNREADABLE" to (0.5 to 0.4), "DISDAIN_THE_JAB" to (0.5 to 0.1)
        )
        assertEquals(expected.keys, byId.keys)
        for ((id, v) in expected) { assertEquals(id, v.first, byId.getValue(id).gain, 0.0); assertEquals(id, v.second, byId.getValue(id).exposureLoss, 0.0) }
        assertTrue(byId.getValue("REAL_COMPETITION").tactic.coachHint.contains("real"))
    }
}
