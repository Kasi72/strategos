package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ReportDigest
import com.drkkr.strategos.domain.agent.ScenarioDigest
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.PayoffEntry
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.fixtures.Fx
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestTest {

    private fun JsonObject.obj(k: String) = getValue(k).jsonObject
    private fun JsonObject.int(k: String) = getValue(k).jsonPrimitive.int

    /** [players] players with [actions] actions each; the first [payoffs] profiles get one entry for player 1. */
    private fun game(players: Int, actions: Int, payoffs: Int = 0): Scenario {
        val ps = (1..players).map { Stakeholder(id = "p$it", name = "P$it") }
        val acts = ps.flatMap { p -> (1..actions).map { Action(id = "${p.id}a$it", stakeholderId = p.id, label = "A$it") } }
        val entries = (1..payoffs).map { i ->
            val profile = ps.associate { p -> p.id to "${p.id}a${(i - 1) % actions + 1}" }
            PayoffEntry("p1", profile, MultiPayoff(mapOf(PayoffDimension.SAFETY to 1.0)))
        }.distinctBy { it.actionProfile }
        return Scenario(title = "g", stakeholders = ps, actions = acts, payoffs = entries)
    }

    @Test
    fun TC_DIG_01_pdCoverage() {
        val d = ScenarioDigest.build(Fx.pd()).obj("payoffs")
        assertEquals(8, d.int("expected"))
        assertEquals(8, d.int("filled"))
        assertEquals(0, d.getValue("missing_profiles").jsonArray.size)
    }

    @Test
    fun TC_DIG_02_threeByThreeExpected() {
        val s = game(players = 2, actions = 3).let { g ->
            val profiles = listOf("p1a1" to "p2a1", "p1a1" to "p2a2", "p1a2" to "p2a1", "p1a3" to "p2a3")
            g.copy(payoffs = profiles.map { (a, b) -> PayoffEntry("p1", mapOf("p1" to a, "p2" to b), MultiPayoff(mapOf(PayoffDimension.SAFETY to 1.0))) })
        }
        val d = ScenarioDigest.build(s).obj("payoffs")
        assertEquals(18, d.int("expected"))
        assertEquals(4, d.int("filled"))
        val missing = d.getValue("missing_profiles").jsonArray
        assertEquals(9, missing.size) // every profile still lacks p2's entry
        assertTrue(missing.size <= 10)
        assertEquals(2, missing[0].jsonArray.size)
    }

    @Test
    fun TC_DIG_02b_expectedFor2x3And3x2() {
        val twoByThree = Scenario(
            title = "g",
            stakeholders = listOf(Stakeholder(id = "a", name = "A"), Stakeholder(id = "b", name = "B")),
            actions = listOf(Action(id = "a1", stakeholderId = "a", label = "1"), Action(id = "a2", stakeholderId = "a", label = "2")) +
                (1..3).map { Action(id = "b$it", stakeholderId = "b", label = "$it") }
        )
        val threeByTwo = twoByThree.copy(actions = twoByThree.actions + Action(id = "a3", stakeholderId = "a", label = "3")
            .let { listOf(it) } - twoByThree.actions.single { it.id == "b3" })
        assertEquals(12, ScenarioDigest.build(twoByThree).obj("payoffs").int("expected"))  // 2x3 profiles x 2 players
        assertEquals(12, ScenarioDigest.build(threeByTwo).obj("payoffs").int("expected"))  // 3x2 profiles x 2 players
        assertEquals(24, ScenarioDigest.build(game(players = 3, actions = 2)).obj("payoffs").int("expected"))  // 2^3 x 3
    }

    @Test
    fun TC_DIG_02c_missingProfilesCappedAtTen() {
        val missing = ScenarioDigest.build(game(players = 3, actions = 3)).obj("payoffs").getValue("missing_profiles").jsonArray
        assertEquals(10, missing.size)
    }

    @Test
    fun TC_DIG_03_stakeholderCap() {
        val s = Scenario(title = "big", stakeholders = (1..15).map { Stakeholder(id = "s$it", name = "S$it") })
        val d = ScenarioDigest.build(s)
        assertEquals(12, d.getValue("players").jsonArray.size + d.getValue("influencers").jsonArray.size)
        assertEquals(JsonPrimitive(true), d["truncated"])
        assertEquals(JsonPrimitive(false), ScenarioDigest.build(Fx.pd())["truncated"])
    }

    @Test
    fun TC_DIG_03b_fieldsPresentAndInfluencersSplit() {
        val d = ScenarioDigest.build(Fx.pdWithInfluencer())
        listOf("scenario_id", "version", "title", "description", "decision_owner", "stakes", "time_horizon", "decision_rule",
            "lifecycle_stage", "workflow_stage", "completeness", "players", "influencers", "actions", "payoffs",
            "constraints", "beliefs", "evidence_count", "validity_issues", "truncated").forEach { assertTrue(it, it in d) }
        assertEquals(listOf("m1"), d.getValue("influencers").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(2, d.getValue("players").jsonArray.size)
    }

    @Test
    fun TC_DIG_04_reportDigest() {
        val pipeline = AnalysisPipeline()
        for (s in Fx.templates() + Fx.pdAiPayoffs(4)) {
            val pr = pipeline.run(s)
            if (pr.report == null) continue
            val d = ReportDigest.build(pr, s)
            assertTrue(d.getValue("pathways").jsonArray.size <= 6)
            assertTrue(d.getValue("frameworks").jsonArray.size <= 20)
            assertTrue(d.getValue("voi").jsonArray.size <= 3)
            assertTrue(d.getValue("risks").jsonArray.size <= 3)
            assertTrue("gate" in d.obj("provenance"))
            assertTrue(maxDecimals(d) <= 2)
        }
        val ai = Fx.pdAiPayoffs(4)
        val prov = ReportDigest.build(pipeline.run(ai), ai).obj("provenance")
        assertEquals(0.5, prov.getValue("ai_share").jsonPrimitive.doubleOrNull!!, 0.0)
        assertEquals(4, prov.int("payoffs_ai_inferred"))
    }

    @Test
    fun TC_DIG_04b_numbersRounded() {
        assertEquals(JsonPrimitive(0.33), ScenarioDigest.build(Fx.pd().copy(beliefs = listOf(
            com.drkkr.strategos.domain.model.Belief("x", 1.0 / 3)))).getValue("beliefs").jsonArray[0].jsonObject["probability"])
        assertFalse(maxDecimals(ScenarioDigest.build(Fx.loanFamily())) > 2)
    }

    private fun maxDecimals(e: JsonElement): Int = when (e) {
        is JsonObject -> e.values.maxOfOrNull(::maxDecimals) ?: 0
        is JsonArray -> e.maxOfOrNull(::maxDecimals) ?: 0
        is JsonPrimitive -> if (e.isString || e.doubleOrNull == null || !e.content.contains('.')) 0
            else e.content.substringAfter('.').trimEnd('0').length
    }
}
