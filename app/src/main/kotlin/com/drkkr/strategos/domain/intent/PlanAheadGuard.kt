package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.ModelSketch

/**
 * plan_ahead branches on a move the user is already making ("and if she says no?"), and Call 1 always names that move.
 * Without one, the message is a new step to prepare ("assume I'm on the date Saturday: what should I talk about?"), which
 * is a standard turn. Read as a hypothetical it saved nothing, so the engine kept re-picking the previous move and every
 * new question got the last answer again (live, 13 Nov).
 */
object PlanAheadGuard {
    fun realIntent(sketch: ModelSketch): ModelSketch =
        // A description of the step ("rehearse Saturday's date do's and don'ts", seen live) is not a move to branch from.
        if (sketch.intentKind == Intent.PLAN_AHEAD && sketch.intentDetail?.move.isNullOrBlank())
            sketch.copy(intent = Intent.STANDARD.word, intentDetail = null)
        else sketch
}
