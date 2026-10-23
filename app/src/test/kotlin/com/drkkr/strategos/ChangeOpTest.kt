package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeOpDescriber
import com.drkkr.strategos.domain.agent.OpConstraintKind
import com.drkkr.strategos.domain.agent.OpSource
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.fixtures.Fx
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeOpTest {

    private fun parse(json: String): List<ChangeOp> =
        ChangeOp.json.decodeFromString(ListSerializer(ChangeOp.serializer()), json)

    @Test
    fun parsesSnakeCaseSchemaFields() {
        val ops = parse(
            """[
              {"type":"add_stakeholder","ref":"reg","name":"Regulator","stakeholder_type":"REGULATOR","game_role":"INFLUENCER",
               "can_veto":true,"rationale":"They license the site","source":"ai_inferred"},
              {"type":"set_payoff","stakeholder":"p1","profile":[{"stakeholder":"p1","action":"c1"},{"stakeholder":"p2","action":"c2"}],
               "values":[{"dimension":"COST","value":7}],"rationale":"r","unknown_field":1},
              {"type":"add_constraint","label":"Law","constraint_kind":"LEGAL","rationale":"r"},
              {"type":"set_scenario_fields","scenario_fields":{"decision_owner":"You","zero_sum":false},"rationale":"r"}
            ]"""
        )
        assertEquals(OpType.ADD_STAKEHOLDER, ops[0].type)
        assertEquals(GameRole.INFLUENCER, ops[0].gameRole)
        assertEquals(true, ops[0].canVeto)
        assertEquals(OpSource.AI_INFERRED, ops[0].source)
        assertEquals(PayoffDimension.COST, ops[1].values!!.single().dimension)
        assertEquals(7.0, ops[1].values!!.single().value, 0.0)
        assertEquals(Constraint.Kind.LEGAL, ops[2].constraintKind!!.kind)
        assertEquals("You", ops[3].scenarioFields!!.decisionOwner)
    }

    @Test
    fun userHardConstraintCannotBeExpressed() {
        assertFalse(OpConstraintKind.entries.any { it.kind == Constraint.Kind.USER_HARD })
        val failed = runCatching { parse("""[{"type":"add_constraint","label":"x","constraint_kind":"USER_HARD","rationale":"r"}]""") }
        assertTrue(failed.isFailure)
    }

    @Test
    fun describerResolvesNamesFromScenarioAndBatch() {
        val s = Fx.pd()
        val add = ChangeOp(OpType.ADD_STAKEHOLDER, ref = "reg", name = "Regulator", rationale = "r")
        val action = ChangeOp(OpType.ADD_ACTION, ref = "inspect", stakeholder = "reg", label = "Inspect", rationale = "r")
        assertEquals("Add Regulator (influencer)", ChangeOpDescriber.describe(add, s))
        assertEquals("Add option for Regulator: Inspect", ChangeOpDescriber.describe(action, s, listOf(add, action)))
        assertEquals("Remove P2", ChangeOpDescriber.describe(ChangeOp(OpType.REMOVE_STAKEHOLDER, targetId = "p2", rationale = "r"), s))
    }
}
