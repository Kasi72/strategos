package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.PatternCatalog
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.*
import org.junit.Test

class PatternCatalogTest {

    private val archetypes = listOf(
        "prisoners-dilemma", "stag-hunt", "chicken-game", "relational-repair",
        "stakeholder-trust-dynamics", "ultimatum", "pure-nash"
    )

    @Test fun TC_PATTERN_01_everyArchetypeResolvesToNameSummaryAndWiki() {
        archetypes.forEach { id ->
            val p = PatternCatalog.of(id)!!
            assertTrue("name for $id", p.name.isNotBlank())
            assertTrue("summary for $id has 3 sentences", p.summary.count { it == '.' } >= 3)
            assertTrue("wiki for $id", p.wikiUrl.startsWith("https://en.wikipedia.org/wiki/"))
        }
    }

    @Test fun TC_PATTERN_02_unknownOrNullArchetypeIsNull() {
        assertNull(PatternCatalog.of(null))
        assertNull(PatternCatalog.of("not-a-pattern"))
    }

    @Test fun TC_PATTERN_03_acceptsTrajectoryUpperSnakeForm() {
        val p = PatternCatalog.of("CHICKEN_GAME")!!
        assertEquals("chicken-game", p.archetype)
        assertEquals("Standoff over who gives way", p.name)
    }

    @Test fun TC_PATTERN_04_prisonersDilemmaIsTrustTrapLinkingItsOwnPage() {
        val p = PatternCatalog.of("prisoners-dilemma")!!
        assertEquals("Trust trap", p.name)
        assertTrue(p.wikiUrl.endsWith("Prisoner%27s_dilemma"))
    }

    @Test fun TC_PATTERN_05_variablesFillFromScenario() {
        val vars = PatternCatalog.of("stag-hunt", Fx.loanFamily())!!.variables
        assertTrue("limit present", vars.any { it.label == "Your limit" && it.value == "Don't risk peace at home" })
        assertTrue("brother's need present", vars.any { it.label == "Brother's need" && it.value == "Get the loan" })
        // Wife / Parents are influencers; at least one third party shows up.
        assertTrue("a third party present", vars.any { it.label == "Wife" || it.label == "Parents" })
    }

    @Test fun TC_PATTERN_06_noScenarioMeansNoVariables() {
        assertTrue(PatternCatalog.of("stag-hunt").let { it!!.variables.isEmpty() })
    }
}
