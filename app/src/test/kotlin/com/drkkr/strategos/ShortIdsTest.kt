package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.LiveDigest
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.agent.ProfilePick
import com.drkkr.strategos.domain.agent.ShortIds
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** LEAN PROFILE: short ids in, full ids out. */
class ShortIdsTest {

    /** [Fx.loanFamily] with real UUIDs, as in the app. */
    private fun loanWithUuids(): Scenario = Fx.loanFamily().let { s ->
        val map = (s.stakeholders.map { it.id } + s.actions.map { it.id } + s.constraints.map { it.id }).associateWith { UUID.randomUUID().toString() }
        s.copy(
            stakeholders = s.stakeholders.map { it.copy(id = map.getValue(it.id)) },
            actions = s.actions.map { it.copy(id = map.getValue(it.id), stakeholderId = map.getValue(it.stakeholderId)) },
            payoffs = s.payoffs.map { p -> p.copy(stakeholderId = map.getValue(p.stakeholderId), actionProfile = p.actionProfile.entries.associate { map.getValue(it.key) to map.getValue(it.value) }) },
            constraints = s.constraints.map { it.copy(id = map.getValue(it.id)) }
        )
    }

    @Test
    fun shortAndExpand() {
        val id = "3f9a1c2b-0000-4000-8000-000000000001"
        assertEquals("3f9a1c2b", ShortIds.short(id))
        assertEquals("p1", ShortIds.short("p1"))
        assertEquals(id, ShortIds.expand("3f9a1c2b", listOf(id, "other")))
        assertEquals(id, ShortIds.expand(id, listOf(id)))
        assertEquals("3f9a", ShortIds.expand("3f9a", listOf(id)))          // too short to expand
        val twin = "3f9a1c2b-0000-4000-8000-000000000002"
        assertEquals("3f9a1c2b", ShortIds.expand("3f9a1c2b", listOf(id, twin)))   // ambiguous stays as is → unknown_target
    }

    @Test
    fun expandOpsResolvesReferencesButNotBatchRefs() {
        val s = loanWithUuids()
        val bro = s.stakeholders.single { it.name == "Brother" }
        val you = s.stakeholders.single { it.name == "You" }
        val ops = listOf(
            ChangeOp(OpType.UPDATE_STAKEHOLDER, targetId = ShortIds.short(bro.id), canVeto = true, rationale = "r"),
            ChangeOp(OpType.ADD_STAKEHOLDER, ref = bro.id.take(6), name = "Bank", rationale = "r"),
            ChangeOp(OpType.ADD_ACTION, ref = "lend", stakeholder = bro.id.take(6), label = "Lend", rationale = "r"),
            ChangeOp(OpType.SET_PAYOFF, stakeholder = ShortIds.short(you.id), rationale = "r",
                profile = listOf(ProfilePick(ShortIds.short(you.id), ShortIds.short(s.actionsFor(you.id).first().id)),
                    ProfilePick(ShortIds.short(bro.id), ShortIds.short(s.actionsFor(bro.id).first().id))))
        )
        val out = ShortIds.expandOps(ops, s)
        assertEquals(bro.id, out[0].targetId)
        assertEquals(bro.id.take(6), out[2].stakeholder)      // a ref defined in the batch wins over a prefix match
        assertEquals(you.id, out[3].stakeholder)
        assertEquals(s.actionsFor(bro.id).first().id, out[3].profile!![1].action)
    }

    @Test
    fun liveDigestUsesShortIdsAndIsSmall() {
        val s = loanWithUuids()
        val d = LiveDigest.build(s).toString()
        assertFalse(s.stakeholders.any { d.contains(it.id) })
        assertTrue(s.stakeholders.all { d.contains(ShortIds.short(it.id)) })
        assertTrue("LiveDigest ~${d.length / 4} tokens", d.length / 4 <= 450)
    }

    companion object {
        fun loan(): Scenario = ShortIdsTest().loanWithUuids()
    }
}
