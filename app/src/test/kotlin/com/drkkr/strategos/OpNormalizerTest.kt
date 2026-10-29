package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.OpSource
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LEAN PROFILE: the slips gpt-oss-20b made live (turn g6), repaired before the strict validator. */
class OpNormalizerTest {

    private fun parse(json: String) = ChangeOp.json.decodeFromString(ListSerializer(ChangeOp.serializer()), json)

    @Test
    fun liveTurnG6IsAcceptedAfterNormalizing() {
        // Shape of the model's real call: labels as names, no refs, user_stated without quotes, owner in target_id, names as references.
        val ops = parse("""[
          {"type":"add_stakeholder","game_role":"PLAYER","label":"You","name":"You","source":"user_stated","rationale":"r"},
          {"type":"add_stakeholder","game_role":"PLAYER","label":"Brother","source":"user_stated","rationale":"r"},
          {"type":"add_stakeholder","game_role":"INFLUENCER","name":"Parents","source":"user_stated","quote":"My parents always take his side.","rationale":"r"},
          {"type":"add_action","label":"Co-sign","source":"user_stated","target_id":"You","rationale":"r"},
          {"type":"add_action","label":"Wait","stakeholder":"you","rationale":"r"},
          {"type":"add_action","label":"Take the loan","stakeholder":"Brother","rationale":"r"},
          {"type":"add_action","label":"Give up","stakeholder":"brother","rationale":"r"}
        ]""")
        val blank = Scenario(title = "Loan")
        val before = ChangeSetValidator.validate(blank, ops)
        assertTrue(before.rejected.isNotEmpty())

        val fixed = OpNormalizer.normalize(ops, blank)
        val after = ChangeSetValidator.validate(blank, fixed)
        assertEquals(emptyList<Any>(), after.rejected)
        assertEquals("Brother", fixed[1].name)
        assertEquals(OpSource.AI_INFERRED, fixed[0].source)          // no quote → not claimed as the user's words
        assertEquals(OpSource.USER_STATED, fixed[2].source)          // quote present → kept (QuoteVerifier decides later)
        assertEquals("you", fixed[3].stakeholder)
        assertEquals("brother", fixed[5].stakeholder)
    }

    @Test
    fun namesResolveToScenarioIdsAndRefsStayUnique() {
        val s = Fx.loanFamily()
        val ops = listOf(
            ChangeOp(OpType.UPDATE_STAKEHOLDER, targetId = "Wife", canDelay = true, rationale = "r"),
            ChangeOp(OpType.REMOVE_ACTION, targetId = "Ask parents", rationale = "r"),
            ChangeOp(OpType.ADD_STAKEHOLDER, name = "Bank!", rationale = "r"),
            ChangeOp(OpType.ADD_STAKEHOLDER, name = "Bank?", rationale = "r")
        )
        val fixed = OpNormalizer.normalize(ops, s)
        assertEquals("wife", fixed[0].targetId)
        assertEquals("askp", fixed[1].targetId)
        assertEquals(listOf("bank", "bank_2"), fixed.drop(2).map { it.ref })
        assertTrue(ChangeSetValidator.validate(s, fixed).rejected.isEmpty())
    }

    @Test
    fun readdedPeopleAndOptionsAreMergedNotDuplicated() {
        val s = Fx.loanFamily()
        val ops = parse("""[
          {"type":"add_stakeholder","ref":"b","name":"brother","game_role":"INFLUENCER","rationale":"r"},
          {"type":"add_stakeholder","ref":"w","name":"Wife","goals":["Keep our savings"],"rationale":"r"},
          {"type":"add_stakeholder","ref":"bank","name":"Bank","rationale":"r"},
          {"type":"add_stakeholder","ref":"bank2","name":"bank","rationale":"r"},
          {"type":"add_action","ref":"cs","stakeholder":"You","label":"co-sign","rationale":"r"},
          {"type":"add_action","ref":"loan","stakeholder":"bank2","label":"Lend","rationale":"r"}
        ]""")
        val fixed = OpNormalizer.normalize(ops, s)
        // Brother (exists, no new details) is dropped; Wife (exists, new goals) becomes an update that keeps her role; Bank is added once.
        assertEquals(listOf(OpType.UPDATE_STAKEHOLDER, OpType.ADD_STAKEHOLDER, OpType.ADD_ACTION), fixed.map { it.type })
        assertEquals("wife", fixed[0].targetId)
        assertEquals(null, fixed[0].gameRole)
        assertEquals("bank", fixed[1].ref)
        assertEquals("bank", fixed[2].stakeholder)             // the duplicate's ref now points at the first Bank
        assertTrue(ChangeSetValidator.validate(s, fixed).rejected.isEmpty())
    }
}
