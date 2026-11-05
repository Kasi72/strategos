package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.OpSource
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.DeliveryTags
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.fixtures.Sketches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL1.3: the sketch becomes saved facts (tags, power, context) through the normal ops. */
class HumanConvertTest {

    private fun base(authority: Int = 0, influence: Int = 0, canExit: Boolean = false) = Scenario(
        title = "t",
        stakeholders = listOf(
            Stakeholder(id = "you", name = "You", gameRole = GameRole.PLAYER),
            Stakeholder(id = "mgr", name = "Manager", gameRole = GameRole.PLAYER, authority = authority, influence = influence, canExit = canExit)
        )
    )

    private fun ops(json: String, s: Scenario = base()) = SketchConverter.toOps(SketchParser.parse(json)!!, s).ops

    private fun apply(json: String, s: Scenario): Pair<Scenario, List<Pair<Int, String>>> {
        val sketch = SketchParser.parse(json)!!
        val o = OpNormalizer.normalize(SketchConverter.toOps(sketch, s).ops, s)
        val v = ChangeSetValidator.validate(s, o)
        val accepted = v.accepted.map { it.op }
        val r = ChangeSetApplier.apply(s, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t")
        return r.scenario to (v.rejected.map { it.index to it.reason } + r.skipped)
    }

    private val optionsJson = """"options":[
        {"for":"You","label":"Ask for a raise now","posture":"boundary"},
        {"for":"You","label":"Wait for the review","posture":"repair"},
        {"for":"You","label":"Quit loudly","posture":"reactive"},
        {"for":"Manager","label":"Agree","posture":"receptive"},{"for":"Manager","label":"Refuse","posture":"defensive"}]"""

    // ── DeliveryTags ─────────────────────────────────────────────────────────────

    @Test
    fun TC_HL_TAGS_01_markerRoundTripsAndKeepsThePostureTag() {
        assertEquals("", DeliveryTags.tag(emptyList()))
        assertEquals("[f:open_ended,public]", DeliveryTags.tag(listOf("public", "open_ended")))
        val a = Action(stakeholderId = "you", label = "x", description = Postures.tag("repair") + DeliveryTags.tag(setOf("public", "argues")))
        assertEquals(setOf("public", "argues"), DeliveryTags.of(a))
        assertEquals(Postures.REPAIR, Postures.of(a))
        // an unknown word in a stored marker is ignored
        assertEquals(setOf("public"), DeliveryTags.of(a.copy(description = "[f:public,sneaky]")))
        assertEquals(emptySet<String>(), DeliveryTags.of(a.copy(description = "no marker")))
    }

    @Test
    fun TC_HL_TAGS_02_withIsAnInMemoryCopyThatLeavesTheRestOfTheDescription() {
        val a = Action(stakeholderId = "you", label = "x", description = "[posture:boundary][f:open_ended,public]")
        val b = DeliveryTags.with(a, add = setOf("sets_default"), remove = setOf("open_ended"))
        assertEquals(setOf("public", "sets_default"), DeliveryTags.of(b))
        assertEquals(Postures.BOUNDARY, Postures.of(b))
        assertEquals(setOf("open_ended", "public"), DeliveryTags.of(a))          // original untouched
        assertEquals("[posture:boundary]", DeliveryTags.with(a, emptySet(), setOf("open_ended", "public")).description)
        assertEquals("[f:sets_default]", DeliveryTags.with(a.copy(description = ""), setOf("sets_default"), emptySet()).description)
    }

    // ── option tags, power, context ──────────────────────────────────────────────

    @Test
    fun TC_HL_SKETCH_03_optionTagsLandInTheActionDescription_nextToThePosture() {
        val json = """{"people":[{"name":"You","role":"PLAYER"},{"name":"Manager","role":"PLAYER"}],$optionsJson,
            "option_tags":{"Ask for a raise now":["public","asks_them_to_give_up"],"wait for the REVIEW":["open_ended"]}}"""
        val adds = ops(json, Scenario(title = "t")).filter { it.type == OpType.ADD_ACTION }
        fun desc(label: String) = adds.single { it.label == label }.description
        assertTrue(desc("Ask for a raise now")!!.contains("[posture:boundary]"))
        assertTrue(desc("Ask for a raise now")!!.contains("[f:asks_them_to_give_up,public]"))
        assertTrue(desc("Wait for the review")!!.contains("[posture:repair]") && desc("Wait for the review")!!.contains("[f:open_ended]"))
        assertEquals("[posture:reactive]", desc("Quit loudly"))                 // untagged option: posture only, as before
        assertNull(adds.single { it.label == "Agree" }.description?.takeIf { it.contains("[f:") }) // the counterpart's options are not tagged here
        // and it survives the real apply path
        val (saved, problems) = apply(json, Scenario(title = "t"))
        assertEquals(emptyList<Any>(), problems)
        val raise = saved.actions.single { it.label == "Ask for a raise now" }
        assertEquals(setOf("asks_them_to_give_up", "public"), DeliveryTags.of(raise))
        assertEquals(Postures.BOUNDARY, Postures.of(raise))
    }

    @Test
    fun TC_HL_SKETCH_04_powerFillsAuthorityAndInfluenceWhenTheyAreStillZero() {
        val o = ops("""{"people":[{"name":"Manager","role":"PLAYER"}],"power":{"relative":"they_higher"}}""", base(0, 0))
        val up = o.filter { it.type == OpType.UPDATE_STAKEHOLDER }
        assertEquals(1, up.size)
        assertEquals("mgr", up.single().targetId)
        assertEquals(8, up.single().authority); assertEquals(5, up.single().influence)
        assertEquals(OpSource.AI_INFERRED, up.single().source)
        assertEquals(3, ops("""{"people":[{"name":"Manager"}],"power":{"relative":"you_higher"}}""", base()).single { it.type == OpType.UPDATE_STAKEHOLDER }.authority)
        assertEquals(5, ops("""{"power":{"relative":"equal"},"people":[{"name":"Manager"}]}""", base()).single { it.type == OpType.UPDATE_STAKEHOLDER }.authority)
    }

    @Test
    fun TC_HL_SKETCH_05_powerNeverOverwritesAValueThatIsAlreadySet() {
        val o = ops("""{"people":[{"name":"Manager","role":"PLAYER"}],"power":{"relative":"they_higher"}}""", base(authority = 6))
        assertTrue(o.none { it.type == OpType.UPDATE_STAKEHOLDER && (it.authority != null || it.influence != null) })
    }

    @Test
    fun TC_HL_SKETCH_06_strongAlternativeSetsCanExit_dependenciesGoToTheContextPatch() {
        val json = """{"people":[{"name":"Manager","role":"PLAYER"}],
            "power":{"relative":"equal","their_alternative":"strong","they_depend_on_you":"high","you_depend_on_them":"some"}}"""
        val o = ops(json, base())
        assertEquals(true, o.single { it.type == OpType.UPDATE_STAKEHOLDER }.canExit)
        val patch = o.single { it.type == OpType.SET_SCENARIO_FIELDS }.scenarioFields!!.humanContext!!
        assertEquals("high", patch.theyDependOnYou); assertEquals("some", patch.youDependOnThem); assertEquals("strong", patch.theirAlternative)
        // a counterpart who can already exit gets no redundant canExit op
        val again = ops("""{"people":[{"name":"Manager"}],"power":{"their_alternative":"strong"}}""", base(canExit = true))
        assertTrue(again.none { it.type == OpType.UPDATE_STAKEHOLDER })
    }

    @Test
    fun TC_HL_SKETCH_07_aNewCounterpartGetsItsPowerThroughTheRealApplyPath() {
        val json = """{"people":[{"name":"You","role":"PLAYER"},{"name":"Manager","role":"PLAYER"}],$optionsJson,
            "power":{"relative":"they_higher","their_alternative":"strong"},"relationship":"manager"}"""
        val (s, problems) = apply(json, Scenario(title = "t"))
        assertEquals(emptyList<Any>(), problems)
        val m = s.stakeholders.single { it.name == "Manager" }
        assertEquals(8, m.authority); assertEquals(5, m.influence); assertTrue(m.canExit)
        assertEquals("manager", s.humanContext?.relationship)
        assertEquals("they_higher", s.humanContext?.theirPower)
    }

    @Test
    fun TC_HL_SKETCH_08_aSketchWithNoHumanFieldsAddsNothingHuman() {
        val o = ops(Sketches.loanStory, Scenario(title = "Loan"))
        assertTrue(o.none { it.type == OpType.UPDATE_STAKEHOLDER })
        assertTrue(o.none { it.scenarioFields?.humanContext != null })
        val (s, problems) = apply(Sketches.loanStory, Scenario(title = "Loan"))
        assertEquals(emptyList<Any>(), problems)
        assertNull(s.humanContext)
        assertTrue(s.actions.none { it.description.contains("[f:") })
    }

    @Test
    fun TC_HL_SKETCH_09_aHumanOnlySketchStillSavesNothing() {
        // keeps the "reply that only asks a question saves nothing" rule intact
        assertTrue(ops("""{"ask":"Who is it?","your_state":"furious"}""", Scenario(title = "t")).isEmpty())
    }

    @Test
    fun TC_HL_STATE_01_stateIsSmoothedAcrossThreeTurns() {
        fun turn(state: String) = """{"people":[{"name":"You","role":"PLAYER"},{"name":"Manager","role":"PLAYER"}],$optionsJson,"your_state":"$state"}"""
        var s = Scenario(title = "t")
        val seen = mutableListOf<Double?>()
        for (st in listOf("calm", "furious", "furious")) {
            s = apply(turn(st), s).first
            seen += s.humanContext?.yourStateLevel
        }
        assertEquals(listOf(0.0, 1.5, 2.25), seen)
        assertEquals("furious", s.humanContext?.yourStateRaw)
    }
}
