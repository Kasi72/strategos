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
        tacticSuccess = mapOf("DEFAULT_WITH_DEADLINE" to listOf(2.0, 1.0))
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
}
