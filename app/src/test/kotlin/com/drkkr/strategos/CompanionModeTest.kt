package com.drkkr.strategos

import com.drkkr.strategos.agent.OneShotTurn
import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.EngineDirective
import com.drkkr.strategos.domain.agent.ModelSketch
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.agent.PlaceholderNames
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchOption
import com.drkkr.strategos.domain.agent.SketchPerson
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.power.PowerCardGate
import com.drkkr.strategos.domain.human.power.PowerRead
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Companion mode (the Bumble project, 7 Nov): long dictated messages, context lost around turn 7-8, the other person still "Her",
 * the same Power Read card asking "tap to set" on every reply. Offline only.
 */
class CompanionModeTest {
    private fun scenario(vararg names: String, hc: HumanContext? = null) =
        Scenario(id = "s", title = "t", stakeholders = names.map { Stakeholder(id = it.lowercase().replace(' ', '_'), name = it) }, humanContext = hc)

    // ── memory ──
    @Test fun aLongSummaryKeepsItsNewestPart() {
        val summary = (1..60).joinToString(" ") { "Fact number $it happened." } + " She said yes to coffee in Chennai."
        val kept = OneShotTurn.newestPart(summary, 400)
        assertTrue(kept, kept.endsWith("She said yes to coffee in Chennai."))
        assertFalse(kept, kept.contains("Fact number 1 "))
        assertTrue(kept.length <= 401)
        assertTrue(kept, kept.startsWith("…F"))      // starts at a sentence
    }

    /** Kasi's summarizer (7 Nov) leads with a FACTS section that is never rewritten: it survives the cut. */
    @Test fun theFactsSectionIsAlwaysKept() {
        val summary = "FACTS:\n- You and Meera, choir friends\n- Goal: something serious\nSITUATION: " +
            (1..60).joinToString(" ") { "Detail $it." } + "\nOPEN: She said yes to coffee in Chennai."
        val kept = OneShotTurn.newestPart(summary, 500)
        assertTrue(kept, kept.startsWith("FACTS:\n- You and Meera"))
        assertTrue(kept, kept.endsWith("She said yes to coffee in Chennai."))
        assertFalse(kept, kept.contains("Detail 1."))
        assertTrue(kept.length <= 501)
    }

    @Test fun aShortSummaryIsKeptWhole() = assertEquals("Short.", OneShotTurn.newestPart("Short.", 400))

    // ── names ──
    @Test fun aStandInTakesTheRealNameOnce() {
        val s = scenario("You", "Her")
        assertEquals("her" to "Harini", PlaceholderNames.rename(s, listOf("You" to "PLAYER", "Harini" to "PLAYER")))
        val ops = SketchConverter.toOps(ModelSketch(people = listOf(SketchPerson("You", "PLAYER"), SketchPerson("Harini", "PLAYER"))), s).ops
        assertTrue(ops.any { it.type == OpType.UPDATE_STAKEHOLDER && it.targetId == "her" && it.name == "Harini" })
        assertFalse("no second person", ops.any { it.type == OpType.ADD_STAKEHOLDER && it.name == "Harini" })
    }

    @Test fun noRenameWhenItIsAmbiguousOrTheStandInIsStillUsed() {
        assertNull(PlaceholderNames.rename(scenario("You", "Her"), listOf("Her" to "PLAYER", "Harini" to null)))
        assertNull(PlaceholderNames.rename(scenario("You", "Her"), listOf("Harini" to null, "Priya" to null)))
        assertNull(PlaceholderNames.rename(scenario("You", "Her", "Him"), listOf("Harini" to null)))
        assertNull(PlaceholderNames.rename(scenario("You", "Asha"), listOf("Harini" to null)))
    }

    // ── thinking aloud ──
    @Test fun thinkingAloudHoldsTheCourseOnlyWithAModelAndAStandardTurn() {
        assertTrue(OneShotTurn.holdsCourse("thinking_aloud", Intent.STANDARD, hasModel = true))
        assertFalse(OneShotTurn.holdsCourse("thinking_aloud", Intent.STANDARD, hasModel = false))
        assertFalse(OneShotTurn.holdsCourse("situation", Intent.STANDARD, hasModel = true))
        assertFalse(OneShotTurn.holdsCourse(null, Intent.STANDARD, hasModel = true))
        Intent.values().filter { it != Intent.STANDARD }.forEach { assertFalse(it.name, OneShotTurn.holdsCourse("thinking_aloud", it, true)) }
    }

    @Test fun holdCourseReachesTheCoachAndThePromptsSayWhatToDo() {
        val d = EngineDirective(
            ready = true, recommendedMove = "Meet for coffee first", recommendedWhy = "", supportingFrameworks = emptyList(), safeFallbackMove = null,
            complementaryMove = null, blockedOrDominatedMoves = emptyList(), dominantDynamic = emptyList(), dynamicLabel = null,
            influencersInPlay = emptyList(), stressVerdict = "ROBUST", stressFlip = null, moveToImplement = "Meet for coffee first")
        assertFalse(d.toJson().containsKey("hold_course"))
        assertTrue(d.copy(holdCourse = true).toJson().containsKey("hold_course"))
        assertTrue(ArgonPrompts.ENGINE_COACH_PROMPT.contains("34. HOLD COURSE"))
        assertTrue(ArgonPrompts.EXTRACTION_PROMPT.contains("\"thinking_aloud\""))
    }

    // ── Power Read card ──
    private val move = PowerRead.Move("Real competition", "L3", 0.4, "they may call it", false)

    @Test fun theFallbackIsAskedOnlyWhenItWouldChangeTheAdviceAndOnlyOnce() {
        val s = scenario("You", "Her", hc = HumanContext(relationship = "friend"))
        assertEquals(emptyList<String>(), PowerCardGate.ask(PowerRead(), s, fallbackMatters = false, askedBefore = emptySet()))
        assertEquals(listOf(PowerCardGate.YOUR_FALLBACK), PowerCardGate.ask(PowerRead(), s, fallbackMatters = true, askedBefore = emptySet()))
        assertEquals(emptyList<String>(), PowerCardGate.ask(PowerRead(), s, true, setOf(PowerCardGate.YOUR_FALLBACK)))
        val set = scenario("You", "Her", hc = HumanContext(relationship = "friend", yourAlternative = "weak"))
        assertEquals(emptyList<String>(), PowerCardGate.ask(PowerRead(), set, true, emptySet()))
    }

    @Test fun theyNeedYouIsNeverAskedInACloseRelationshipOrWithoutMoves() {
        val friend = scenario("You", "Her", hc = HumanContext(relationship = "friend"))
        assertFalse(PowerCardGate.THEY_NEED_YOU in PowerCardGate.ask(PowerRead(yourMoves = listOf(move)), friend, false, emptySet()))
        val manager = scenario("You", "Boss", hc = HumanContext(relationship = "manager"))
        assertFalse(PowerCardGate.THEY_NEED_YOU in PowerCardGate.ask(PowerRead(), manager, false, emptySet()))
        assertTrue(PowerCardGate.THEY_NEED_YOU in PowerCardGate.ask(PowerRead(yourMoves = listOf(move)), manager, false, emptySet()))
    }

    @Test fun theSameCardIsNotShownTwice() {
        val a = PowerRead(leverage = PowerRead.Leverage(yourFallback = "weak"))
        assertTrue(PowerCardGate.show(a, null))
        assertFalse(PowerCardGate.show(a, a))
        assertFalse(PowerCardGate.show(a, a.copy(ask = listOf(PowerCardGate.YOUR_FALLBACK))))
        assertTrue(PowerCardGate.show(a.copy(leverage = a.leverage.copy(yourFallback = "strong")), a))
        assertTrue(PowerCardGate.show(a.copy(ask = listOf(PowerCardGate.THEY_NEED_YOU)), a))
    }
}
