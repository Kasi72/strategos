package com.drkkr.strategos

import com.drkkr.strategos.data.repo.JournalEntryView
import com.drkkr.strategos.ui.calm.journal.JournalUi
import com.drkkr.strategos.ui.calm.journal.Mood
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class JournalUiTest {
    private val utc = ZoneOffset.UTC
    private val now = LocalDate.of(2026, 10, 24).atTime(20, 0).toInstant(utc).toEpochMilli()
    private fun at(d: Int, h: Int = 9) = LocalDate.of(2026, 10, d).atTime(h, 5).toInstant(utc).toEpochMilli()

    @Test fun TC_JRN_01_groupLabels() {
        val t = LocalDate.of(2026, 10, 24)
        assertEquals("Today", JournalUi.groupLabel(t, t)); assertEquals("Yesterday", JournalUi.groupLabel(t.minusDays(1), t))
        assertEquals("Oct 21", JournalUi.groupLabel(LocalDate.of(2026, 10, 21), t))
    }

    @Test fun TC_JRN_02_weekIsMondayToSunday() {
        val w = JournalUi.week(LocalDate.of(2026, 10, 24))   // a Saturday
        assertEquals(LocalDate.of(2026, 10, 19), w.first()); assertEquals(LocalDate.of(2026, 10, 25), w.last()); assertEquals(7, w.size)
    }

    @Test fun TC_JRN_03_monthCellsPadToFirstWeekday() {
        val cells = JournalUi.monthCells(YearMonth.of(2026, 10))   // Oct 1 2026 is a Thursday
        assertEquals(3, cells.takeWhile { it == null }.size); assertEquals(31, cells.count { it != null })
    }

    @Test fun TC_JRN_04_moodTagsRoundTrip() {
        Mood.values().forEach { assertEquals(it, Mood.fromTag(it.tag)) }
        assertNull(Mood.fromTag("NEUTRAL"))
    }

    @Test fun TC_JRN_05_buildOrdersNewestFirstAndOnlySuggestsUnlinkedKnownProjects() {
        val titles = mapOf("p1" to "Loan")
        val entries = listOf(
            JournalEntryView("a", null, "old", "CALM", "RECORDED", at(21)),
            JournalEntryView("b", null, "new", "ANXIOUS", "RECORDED", at(24)),
            JournalEntryView("c", "p1", "linked", "NEUTRAL", "RECORDED", at(23))
        )
        val ui = JournalUi.build(entries, titles, mapOf("a" to "p1", "c" to "p1", "b" to "gone"), now, utc)
        assertEquals(listOf("b", "c", "a"), ui.map { it.id })
        assertEquals("Loan", ui.first { it.id == "a" }.suggestedProjectName)
        assertNull(ui.first { it.id == "c" }.suggestedProjectId); assertEquals("Loan", ui.first { it.id == "c" }.linkedProjectName)
        assertNull(ui.first { it.id == "b" }.suggestedProjectId)
        assertEquals(Mood.UNEASY, ui.first { it.id == "b" }.mood)
    }
}
