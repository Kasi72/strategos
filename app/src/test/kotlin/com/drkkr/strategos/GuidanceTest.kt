package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.CoachGuideSketch
import com.drkkr.strategos.domain.agent.GuidanceCodec
import com.drkkr.strategos.domain.agent.GuidanceItem
import com.drkkr.strategos.domain.agent.GuidanceKind
import com.drkkr.strategos.domain.agent.GuidanceSource
import com.drkkr.strategos.domain.agent.StandingGuidance
import org.junit.Assert.*
import org.junit.Test

/** Pure dedup/merge tests for standing guidance, plus the guide codec round-trip. */
class GuidanceTest {

    private fun item(text: String, kind: GuidanceKind, at: Long, src: GuidanceSource = GuidanceSource.THREAD) =
        GuidanceItem(text, kind, src, if (src == GuidanceSource.JOURNAL) "your journal" else "Main", "ref-$at", at)

    @Test fun TC_GUIDE_01_separatesDosAndDonts() {
        val g = StandingGuidance.of(listOf(
            item("Hold your limit", GuidanceKind.DO, 2),
            item("Don't double-text", GuidanceKind.DONT, 1)
        ))
        assertEquals(listOf("Hold your limit"), g.dos.map { it.text })
        assertEquals(listOf("Don't double-text"), g.donts.map { it.text })
    }

    @Test fun TC_GUIDE_02_deduplicatesByTextKeepingNewest() {
        val g = StandingGuidance.of(listOf(
            item("Hold your limit.", GuidanceKind.DO, 1, GuidanceSource.THREAD),
            item("hold your limit", GuidanceKind.DO, 5, GuidanceSource.JOURNAL)   // same line, newer, from journal
        ))
        assertEquals(1, g.dos.size)
        assertEquals(5L, g.dos.first().at)
        assertEquals(GuidanceSource.JOURNAL, g.dos.first().source)
    }

    @Test fun TC_GUIDE_03_sameTextDifferentKindIsNotMerged() {
        val g = StandingGuidance.of(listOf(
            item("Reach out", GuidanceKind.DO, 1),
            item("Reach out", GuidanceKind.DONT, 2)
        ))
        assertEquals(1, g.dos.size)
        assertEquals(1, g.donts.size)
    }

    @Test fun TC_GUIDE_04_newestFirstWithinEachKind() {
        val g = StandingGuidance.of(listOf(
            item("older", GuidanceKind.DO, 1),
            item("newer", GuidanceKind.DO, 9),
            item("middle", GuidanceKind.DO, 5)
        ))
        assertEquals(listOf("newer", "middle", "older"), g.dos.map { it.text })
    }

    @Test fun TC_GUIDE_05_emptyInputIsEmpty() {
        assertTrue(StandingGuidance.of(emptyList()).isEmpty)
    }

    @Test fun TC_GUIDE_06_codecRoundTrip() {
        val sketch = CoachGuideSketch(doThis = listOf("Hold your limit"), dontDoThis = listOf("Don't co-sign"))
        val decoded = GuidanceCodec.decode(GuidanceCodec.encode(sketch))!!
        assertEquals(listOf("Hold your limit"), decoded.doThis)
        assertEquals(listOf("Don't co-sign"), decoded.dontDoThis)
        assertNull(GuidanceCodec.decode("not json"))
    }
}
