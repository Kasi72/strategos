package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The solver's dominance list and the pathway ranking can disagree (live: the safe fallback was strictly dominated on the
 * extracted payoffs). A move the directive gives the coach is never also listed as dominated.
 */
class DirectiveDominatedGuardTest {
    @Test fun aMoveTheDirectiveGivesIsNeverAlsoListedAsDominated() {
        val s = Fx.hlLoan()
        val pr = AnalysisPipeline().run(s)
        val plain = EngineDirectiveBuilder.build(s, pr, null)
        val fallback = plain.safeFallbackMove
        assertNotNull("the fixture has a safe fallback", fallback)
        val given = listOfNotNull(plain.recommendedMove, fallback, plain.complementaryMove)
        val others = s.actions.map { it.label }.filter { it !in given }
        assertTrue("the fixture has a move the directive does not give", others.isNotEmpty())

        // the solver says every option is dominated, written as OneShotTurn writes them
        val claimed = s.actions.map { a -> "${s.stakeholders.first { it.id == a.stakeholderId }.name}: ${a.label} (dominated)" }
        val d = EngineDirectiveBuilder.build(s, pr, null, dominatedStrategies = claimed)

        for (m in given) assertTrue("$m listed as dominated: ${d.dominatedStrategies}", d.dominatedStrategies.none { it.endsWith(": $m (dominated)") })
        // the moves the directive does not give stay listed
        assertEquals(others.sorted(), d.dominatedStrategies.map { it.substringAfter(": ").removeSuffix(" (dominated)") }.sorted())
    }
}
