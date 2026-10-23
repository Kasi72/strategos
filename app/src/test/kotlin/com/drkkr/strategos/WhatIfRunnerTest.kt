package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.agent.WhatIfRunner
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.fixtures.Fx
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatIfRunnerTest {

    private val pipeline = AnalysisPipeline()

    @Test
    fun TC_WHATIF_01_removingOpponentChangesOutcomeAndLeavesBaseAlone() = runBlocking {
        val base = Fx.pd()
        val before = base.copy()
        val ref = base
        val out = WhatIfRunner.run(base, listOf(ChangeOp(OpType.REMOVE_STAKEHOLDER, targetId = "p2", rationale = "Opponent exits")), pipeline, "Opponent exits")
        assertSame(ref, base)
        assertEquals(before, base)
        val c = out.comparison
        assertEquals("Opponent exits", c.getValue("label").jsonPrimitive.content)
        val statusChanged = c["status_before"] != c["status_after"]
        val recChanged = c["recommended_before"] != c["recommended_after"]
        assertEquals(statusChanged || recChanged, c.getValue("changed").jsonPrimitive.boolean)
        assertTrue(c.getValue("changed").jsonPrimitive.boolean)
        assertNotEquals(base, out.variant)
        assertTrue(out.variant.stakeholders.none { it.id == "p2" })
    }

    @Test
    fun TC_WHATIF_02_invalidOpListedValidOnesRun() = runBlocking {
        val base = Fx.pd()
        val ops = listOf(
            ChangeOp(OpType.UPDATE_ACTION, targetId = "nope", label = "X", rationale = "r"),
            ChangeOp(OpType.UPDATE_STAKEHOLDER, targetId = "p1", canVeto = true, rationale = "r")
        )
        val out = WhatIfRunner.run(base, ops, pipeline, "Mixed")
        val rejected = out.comparison.getValue("rejected_ops").jsonArray
        assertEquals(1, rejected.size)
        assertEquals(0, rejected[0].jsonObject.getValue("index").jsonPrimitive.int)
        assertEquals("unknown_target", rejected[0].jsonObject.getValue("reason").jsonPrimitive.content)
        assertTrue(out.variant.stakeholders.single { it.id == "p1" }.canVeto)
    }

    @Test
    fun comparisonShape() = runBlocking {
        val out = WhatIfRunner.run(Fx.loanFamily(), listOf(ChangeOp(OpType.REMOVE_ACTION, targetId = "cosign", rationale = "r")), pipeline, "No co-signing")
        listOf("label", "recommended_before", "recommended_after", "changed", "status_before", "status_after",
            "pathway_deltas", "newly_excluded", "newly_feasible", "rejected_ops").forEach { assertTrue(it, it in out.comparison) }
        assertTrue(out.comparison.getValue("pathway_deltas").jsonArray.size <= 5)
    }
}
