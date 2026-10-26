package com.drkkr.strategos

import com.drkkr.strategos.domain.analysis.CheckRecord
import com.drkkr.strategos.domain.analysis.MomentumEvents
import com.drkkr.strategos.domain.analysis.MomentumPoint
import org.junit.Assert.*
import org.junit.Test

/** Pure tests for the momentum pins and the pending Quick-check. */
class MomentumEventsTest {

    @Test fun TC_EVENTS_01_pinsStartQuickCheckAndJournalButNotEveryTurn() {
        val points = listOf(
            MomentumPoint(MomentumEvents.INITIAL, 1),
            MomentumPoint(MomentumEvents.THREAD_TURN, 2),
            MomentumPoint(MomentumEvents.QUICK_CHECK, 3),
            MomentumPoint(MomentumEvents.JOURNAL_SHIFT, 4)
        )
        val checks = listOf(CheckRecord("DID_AS_SUGGESTED", "DE_ESCALATED", 3))
        val events = MomentumEvents.of(points, checks)
        assertEquals(3, events.size)   // turn is not pinned
        assertEquals("You described the situation", events[0].label)
        assertEquals("You did as suggested; it eased", events[1].label)
        assertEquals("A journal note shifted things", events[2].label)
    }

    @Test fun TC_EVENTS_02_checkLabelReflectsChoiceAndLanding() {
        assertEquals("You reacted instead; it got tenser", MomentumEvents.checkLabel(CheckRecord("SKIPPED_REACTIVE", "ESCALATED_ATTACKED", 0)))
        assertEquals("You tried a variation; it held about the same", MomentumEvents.checkLabel(CheckRecord("TRIED_VARIATION", "NO_CHANGE", 0)))
    }

    @Test fun TC_EVENTS_03_pendingAfterATurnWithNoLaterCheck() {
        val points = listOf(MomentumPoint(MomentumEvents.INITIAL, 1), MomentumPoint(MomentumEvents.THREAD_TURN, 2))
        val pending = MomentumEvents.pendingCheck(points, "Dana")
        assertNotNull(pending)
        assertEquals("How did the last talk with Dana go?", pending!!.question)
        assertEquals("Dana", pending.counterpartName)
    }

    @Test fun TC_EVENTS_04_notPendingWhenCheckIsNewerThanTheTurn() {
        val points = listOf(MomentumPoint(MomentumEvents.THREAD_TURN, 2), MomentumPoint(MomentumEvents.QUICK_CHECK, 3))
        assertNull(MomentumEvents.pendingCheck(points, "Dana"))
    }

    @Test fun TC_EVENTS_05_notPendingWithoutAnyTurn() {
        assertNull(MomentumEvents.pendingCheck(listOf(MomentumPoint(MomentumEvents.INITIAL, 1)), "Dana"))
    }

    @Test fun TC_EVENTS_06_questionFallsBackWithoutACounterpartName() {
        val points = listOf(MomentumPoint(MomentumEvents.THREAD_TURN, 2))
        assertEquals("How did the last talk go?", MomentumEvents.pendingCheck(points, null)!!.question)
        assertEquals("How did the last talk go?", MomentumEvents.pendingCheck(points, "You")!!.question)
    }
}
