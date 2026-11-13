package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.intent.PlanAheadGuard
import org.junit.Assert.assertEquals
import org.junit.Test

/** Live, 13 Nov: "assume I'm on the date Saturday, what should I talk about?" came back plan_ahead with no move, and every
 *  later question got the previous café answer, because a plan_ahead turn saved nothing and re-ran the last move. */
class PlanAheadGuardTest {
    private fun intent(json: String) = PlanAheadGuard.realIntent(SketchParser.parse(json)!!).intentKind

    @Test fun planAheadWithoutAMoveIsANewStepToPrepare_aStandardTurn() {
        assertEquals(Intent.STANDARD, intent("""{"intent":"plan_ahead"}"""))
        assertEquals(Intent.STANDARD, intent("""{"intent":"plan_ahead","intent_detail":{}}"""))
        // Seen live: the step described in `change`, no move.
        assertEquals(Intent.STANDARD, intent("""{"intent":"plan_ahead","intent_detail":{"change":"rehearse Saturday's date do's and don'ts"}}"""))
    }

    @Test fun planAheadThatNamesTheMoveStaysPlanAhead() {
        assertEquals(Intent.PLAN_AHEAD, intent("""{"intent":"plan_ahead","intent_detail":{"move":"Pick Anko Cafe at 4.30"}}"""))
    }

    @Test fun otherIntentsAreUntouched() {
        assertEquals(Intent.WHAT_IF, intent("""{"intent":"what_if"}"""))
        assertEquals(Intent.COMPARE, intent("""{"intent":"compare","intent_detail":{"move_a":"a","move_b":"b"}}"""))
    }
}
