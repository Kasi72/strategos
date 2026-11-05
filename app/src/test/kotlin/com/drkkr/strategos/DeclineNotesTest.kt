package com.drkkr.strategos

import com.drkkr.strategos.domain.human.BannedWords
import com.drkkr.strategos.domain.human.HumanVocab
import com.drkkr.strategos.domain.human.power.DeclineNotes
import com.drkkr.strategos.domain.human.power.StrategicId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Power Mode, PM3.2: one fixed, short, non-preachy line per manipulative request. */
class DeclineNotesTest {
    private val kinds = HumanVocab.USER_ASKS_FOR - "none"

    @Test fun TC_PM_DECL_01_oneShortSentencePerRequestPointingToTheNextMove() {
        for (k in kinds) {
            val n = DeclineNotes.of(k)
            assertNotNull(k, n)
            assertEquals("$k: one sentence", 1, Regex("""[.!?](\s|$)""").findAll(n!!).count())
            assertTrue("$k: ${n.split(Regex("\\s+")).size} words", n.split(Regex("\\s+")).size <= 25)
            assertTrue(k, n.contains("here's"))
        }
        assertNull(DeclineNotes.of("none"))
        assertNull(DeclineNotes.of(null))
        assertNull(DeclineNotes.of("blackmail"))
    }

    @Test fun TC_PM_DECL_02_noLawBookAuthorOrTacticId() {
        for (k in kinds) {
            val n = DeclineNotes.of(k)!!
            assertFalse(n, BannedWords.containedIn(n))
            assertFalse(n, Regex("""\bL\d|Ch\.|48 Laws|Nudge|Greene|Thaler|Sunstein""").containsMatchIn(n))
            for (id in StrategicId.values()) assertFalse(n, n.contains(id.name, ignoreCase = true))
        }
    }

    @Test fun theNotesAreNotPreachy() {
        for (k in kinds) assertFalse(k, Regex("""(?i)\b(unethical|wrong|immoral|shouldn't|you must|illegal|shame)\b""").containsMatchIn(DeclineNotes.of(k)!!))
    }
}
