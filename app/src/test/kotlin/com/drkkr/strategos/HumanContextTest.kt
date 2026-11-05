package com.drkkr.strategos

import com.drkkr.strategos.data.repo.ScenarioMapper
import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpSource
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.agent.ScenarioFieldsPatch
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Human Layer, HL1.1: HumanContext on the scenario. */
class HumanContextTest {

    private val full = HumanContext(
        relationship = "manager", theirPower = "they_higher", theyDependOnYou = "some", youDependOnThem = "high",
        yourAlternative = "weak", theirAlternative = "strong", setting = "private",
        statusQuo = "nothing changes", statusQuoForThem = "keeps_something", deadline = "soft",
        theirVulnerable = "none", yourStateLevel = 1.5, theirStateLevel = 0.0, yourStateRaw = "tense", theirStateRaw = "calm",
        theirTactics = listOf("moves_goalposts"), stepsForThemToSayYes = 2, goalIs = "lasting_commitment",
        tacticSuccess = mapOf("DEFAULT_WITH_DEADLINE" to listOf(2.0, 1.0)),
        pending = mapOf("setting" to "public")
    )

    @Test
    fun TC_HL_CTX_01_roundTripsThroughScenarioJson_andOldJsonDecodesToNull() {
        val s = Scenario(title = "t", humanContext = full)
        val back = ScenarioMapper.json.decodeFromString<Scenario>(ScenarioMapper.json.encodeToString(s))
        assertEquals(full, back.humanContext)

        val old = ScenarioMapper.json.encodeToString(Scenario(title = "old"))
        assert("humanContext" in old)           // encodeDefaults writes the key as null
        val stripped = old.replace(Regex(",?\"humanContext\":null"), "")
        assert("humanContext" !in stripped)
        assertNull(ScenarioMapper.json.decodeFromString<Scenario>(stripped).humanContext)
    }

    @Test
    fun TC_HL_CTX_02_nullNeverWipesASavedValue() {
        val saved = HumanContext(statusQuo = "A")
        val merged = saved.merge(HumanContext(statusQuo = null, deadline = "hard"))
        assertEquals("A", merged.statusQuo)
        assertEquals("hard", merged.deadline)
    }

    @Test
    fun TC_HL_CTX_03_statesAreSmoothed_tacticsUnionNewestFirstCapped() {
        val old = HumanContext(yourStateLevel = 0.0, theirTactics = listOf("a1", "a2", "a3", "a4"))
        val m = old.merge(HumanContext(yourStateLevel = 3.0, theirTactics = listOf("n1", "a2", "n2", "n3")))
        assertEquals(1.5, m.yourStateLevel!!, 1e-9)
        assertEquals(listOf("n1", "a2", "n2", "n3", "a1", "a3"), m.theirTactics)   // newest first, no repeats, at most 6
        // no earlier reading: the first one is taken as it is
        assertEquals(0.0, HumanContext().merge(HumanContext(yourStateLevel = 0.0)).yourStateLevel!!, 1e-9)
        // an empty newer list keeps the old tactics; an absent level keeps the old level
        val kept = old.merge(HumanContext())
        assertEquals(old.theirTactics, kept.theirTactics)
        assertEquals(0.0, kept.yourStateLevel!!, 1e-9)
    }

    @Test
    fun TC_HL_CTX_03b_statusQuoIsCappedAt200_andTacticSuccessIsKept() {
        val m = HumanContext(tacticSuccess = mapOf("X" to listOf(3.0, 1.0))).merge(HumanContext(statusQuo = "x".repeat(300)))
        assertEquals(200, m.statusQuo!!.length)
        assertEquals(mapOf("X" to listOf(3.0, 1.0)), m.tacticSuccess)
    }

    @Test
    fun TC_HL_CTX_04_applierMergesThePatch_andANullPatchKeepsTheSavedContext() {
        val base = Scenario(title = "t", humanContext = HumanContext(statusQuo = "A", yourStateLevel = 0.0))
        fun run(patch: HumanContext?): Scenario {
            val op = ChangeOp(type = OpType.SET_SCENARIO_FIELDS, source = OpSource.AI_INFERRED, rationale = "test",
                scenarioFields = ScenarioFieldsPatch(title = "t2", humanContext = patch))
            return ChangeSetApplier.apply(base, listOf(op), listOf(OpDecision(0, Decision.ACCEPT)), emptyMap(), emptyList(), "t").scenario
        }
        val merged = run(HumanContext(deadline = "hard", yourStateLevel = 3.0)).humanContext
        assertNotNull(merged)
        assertEquals("A", merged!!.statusQuo)
        assertEquals("hard", merged.deadline)
        assertEquals(1.5, merged.yourStateLevel!!, 1e-9)
        assertEquals(base.humanContext, run(null).humanContext)
        // first ever patch on a scenario with no context
        val fresh = ChangeSetApplier.apply(
            Scenario(title = "t"),
            listOf(ChangeOp(type = OpType.SET_SCENARIO_FIELDS, source = OpSource.AI_INFERRED, rationale = "test",
                scenarioFields = ScenarioFieldsPatch(humanContext = HumanContext(relationship = "friend")))),
            listOf(OpDecision(0, Decision.ACCEPT)), emptyMap(), emptyList(), "t").scenario
        assertEquals("friend", fresh.humanContext?.relationship)
    }

    // ── hl1.5: sticky relationship, two-turn confirmation ─────────────────────────

    private fun rel(vararg turns: String?): HumanContext =
        turns.fold(HumanContext()) { c, r -> c.merge(HumanContext(relationship = r)) }

    @Test
    fun TC_HL_STICKY_01_relationshipIsSticky() {
        assertEquals("sibling", rel("sibling").relationship)                     // nothing saved yet: taken at once
        assertEquals("sibling", rel("sibling", "friend").relationship)           // one different-domain turn does not replace it
        assertEquals("friend", rel("sibling", "friend", "friend").relationship)  // two in a row do
        assertEquals("spouse", rel("spouse", "partner").relationship)            // same domain: never replaces
        assertEquals("spouse", rel("spouse", "partner", "partner").relationship)
    }

    @Test
    fun TC_HL_STICKY_02_aFlipBackOrADifferentDomainRestartsTheCount() {
        assertEquals("sibling", rel("sibling", "friend", "sibling", "friend").relationship)     // back to saved clears the pending value
        assertEquals("sibling", rel("sibling", "friend", "manager").relationship)               // a third domain restarts it
        assertEquals("manager", rel("sibling", "friend", "manager", "manager").relationship)
        assertEquals("sibling", rel("sibling", "other").relationship)                           // "other" is no information
        assertEquals("friend", rel("other", "friend").relationship)                             // ...and is replaced at once
        assertEquals("friend", rel("sibling", "friend", null, "friend").relationship)           // a silent turn neither confirms nor resets
        assertEquals(mapOf("relationship" to "friend"), rel("sibling", "friend").pending)
        assertEquals(emptyMap<String, String>(), rel("sibling", "friend", "friend").pending)
    }

    private fun soft(field: String, v: String?): HumanContext = when (field) {
        "setting" -> HumanContext(setting = v)
        "theyDependOnYou" -> HumanContext(theyDependOnYou = v)
        "youDependOnThem" -> HumanContext(youDependOnThem = v)
        "yourAlternative" -> HumanContext(yourAlternative = v)
        else -> HumanContext(theirAlternative = v)
    }
    private fun value(c: HumanContext, field: String): String? = when (field) {
        "setting" -> c.setting; "theyDependOnYou" -> c.theyDependOnYou; "youDependOnThem" -> c.youDependOnThem
        "yourAlternative" -> c.yourAlternative; else -> c.theirAlternative
    }
    private fun run(field: String, a: String, b: String, vararg turns: String?): HumanContext =
        turns.fold(HumanContext()) { c, v -> c.merge(soft(field, v)) }

    @Test
    fun TC_HL_CONFIRM_01_aSoftValueNeedsTwoTurnsInARow() {
        val cases = mapOf(
            "setting" to ("private" to "public"), "theyDependOnYou" to ("some" to "high"), "youDependOnThem" to ("none" to "high"),
            "yourAlternative" to ("weak" to "strong"), "theirAlternative" to ("strong" to "weak")
        )
        for ((f, ab) in cases) {
            val (a, b) = ab
            assertNull("$f after one turn", value(run(f, a, b, a), f))
            assertEquals("$f after two", a, value(run(f, a, b, a, a), f))
            assertNull("$f changed value resets", value(run(f, a, b, a, b), f))
            assertEquals("$f changed then repeated", b, value(run(f, a, b, a, b, b), f))
            // a saved value is not replaced by one turn, and going back to it clears the pending one
            assertEquals("$f saved kept", a, value(run(f, a, b, a, a, b), f))
            assertEquals("$f flip back", a, value(run(f, a, b, a, a, b, a, b), f))
            assertEquals("$f replaced after two", b, value(run(f, a, b, a, a, b, b), f))
        }
    }

    @Test
    fun TC_HL_CONFIRM_02_canExitOnlyOnceStrongAlternativeIsConfirmed() {
        // see HumanConvertTest for the converter side; here the pending state that drives it
        val once = HumanContext().merge(HumanContext(theirAlternative = "strong"))
        assertNull(once.theirAlternative)
        assertEquals(mapOf("theirAlternative" to "strong"), once.pending)
        assertEquals("strong", once.merge(HumanContext(theirAlternative = "strong")).theirAlternative)
    }
}
