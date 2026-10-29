package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ChangeSetStatus
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MemoryKind
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.fixtures.FakeGateway
import com.drkkr.strategos.fixtures.Fx
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolDispatcherTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var changeSets: ChangeSetRepository
    private lateinit var memory: MemoryRepository
    private lateinit var gateway: FakeGateway
    private lateinit var conversationId: String
    private val dispatcher = ToolDispatcher.default()

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        db.scenarioDao().upsert(ScenarioEntity("pd", "PD", "DRAFT", 1, 1L, 1L, "{}"))
        db.scenarioDao().upsert(ScenarioEntity("other", "Other", "DRAFT", 1, 1L, 1L, "{}"))
        conversations = ConversationRepository(db)
        changeSets = ChangeSetRepository(db)
        memory = MemoryRepository(db)
        gateway = FakeGateway(Fx.pd())
        conversationId = conversations.getOrCreateActive("pd", "m", "v").id
    }

    @After fun tearDown() { db.close() }

    private fun ctx(userTexts: List<String> = listOf("She will never accept a pay cut, I am sure of it.")) = ToolContext(
        conversationId = conversationId, turnId = "t1", scenarioId = "pd", gateway = gateway, pipeline = AnalysisPipeline(),
        conversations = conversations, changeSets = changeSets, memory = memory, whatIfs = db.whatIfDao(), userTexts = userTexts
    )

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private val proposeArgs = """{"summary":"Add the regulator","ops":[
        {"type":"add_stakeholder","ref":"reg","name":"Regulator","stakeholder_type":"REGULATOR","rationale":"They license the site"},
        {"type":"update_stakeholder","target_id":"p2","can_veto":true,"rationale":"Owns the decision"}]}"""

    @Test
    fun TC_DISP_01_unknownTool() = runBlocking {
        val o = dispatcher.dispatch("launch_rockets", "{}", ctx())
        assertEquals("unknown_tool", o.output.str("error"))
        assertEquals(ToolDispatcher.ERROR_LABEL, o.activityLabel)
    }

    @Test
    fun TC_DISP_02_badJson() = runBlocking {
        val o = dispatcher.dispatch("get_scenario", "{not json", ctx())
        assertEquals("invalid_arguments", o.output.str("error"))
        assertTrue(o.output.str("detail").length <= 200)
    }

    @Test
    fun TC_DISP_03_throwingHandler() = runBlocking {
        val boom = ToolDispatcher(mapOf("boom" to ToolHandler { _, _ -> error("secret scenario text") }))
        val o = boom.dispatch("boom", "{}", ctx())
        assertEquals(setOf("error"), o.output.keys)
        assertEquals("internal_error", o.output.str("error"))
    }

    @Test
    fun TC_DISP_04_proposeChangesNeverTouchesScenario() = runBlocking {
        val before = gateway.scenario
        val o = dispatcher.dispatch("propose_changes", proposeArgs, ctx())
        assertSame(before, gateway.scenario)
        assertEquals("pending_user_review", o.output.str("status"))
        assertEquals(2, o.output.getValue("accepted_count").jsonPrimitive.int)
        assertTrue(o.endsTurn)
        assertEquals(UiKind.CHANGE_SET, o.uiKind)
        val pending = changeSets.pending("pd")
        assertEquals(1, pending.size)
        assertEquals(ChangeSetStatus.PENDING.name, pending.single().status)
        assertEquals(pending.single().id, o.refId)
        assertTrue("reg" in o.output.getValue("ids").jsonObject)
    }

    @Test
    fun TC_DISP_05_tooManyPending() = runBlocking {
        repeat(2) { dispatcher.dispatch("propose_changes", proposeArgs, ctx()) }
        val o = dispatcher.dispatch("propose_changes", proposeArgs, ctx())
        assertEquals("too_many_pending", o.output.str("error"))
        assertEquals(2, o.output.getValue("pending_ids").jsonArray.size)
        assertEquals(2, changeSets.pending("pd").size)
    }

    @Test
    fun TC_DISP_05b_allRejectedMakesNoCard() = runBlocking {
        val o = dispatcher.dispatch("propose_changes", """{"summary":"x","ops":[{"type":"add_action","ref":"a1","stakeholder":"nobody","label":"X","rationale":"r"}]}""", ctx())
        assertEquals("rejected", o.output.str("status"))
        assertEquals(UiKind.NONE, o.uiKind)
        assertTrue(changeSets.pending("pd").isEmpty())
    }

    @Test
    fun TC_DISP_06_askUserMaxThree() = runBlocking {
        val q = """{"id":"q","text":"Who decides?"}"""
        val four = dispatcher.dispatch("ask_user", """{"questions":[$q,$q,$q,$q]}""", ctx())
        assertEquals("invalid_arguments", four.output.str("error"))
        val ok = dispatcher.dispatch("ask_user", """{"questions":[{"id":"q","text":"Who decides?","options":["Me","Them"]}]}""", ctx())
        assertEquals("shown", ok.output.str("status"))
        assertEquals(UiKind.QUESTIONS, ok.uiKind)
        assertTrue(ok.endsTurn)
    }

    @Test
    fun TC_DISP_07_runAnalysis() = runBlocking {
        val o = dispatcher.dispatch("run_analysis", "{}", ctx())
        assertEquals(1, gateway.analyzeCalls)
        assertEquals(UiKind.RECOMMENDATION, o.uiKind)
        assertTrue("executive_status" in o.output)
        val conv = db.conversationDao().get(conversationId)!!
        assertNotNull(conv.lastReportDigestJson)
        assertEquals(gateway.scenario!!.version, conv.lastReportScenarioVersion)
        assertTrue(o.activityLabel.startsWith("Ran "))
    }

    @Test
    fun TC_DISP_08_explainUnknownFramework() = runBlocking {
        dispatcher.dispatch("run_analysis", "{}", ctx())
        val o = dispatcher.dispatch("explain_framework", """{"module_id":"nope"}""", ctx())
        assertEquals("not_in_last_run", o.output.str("error"))
        val available = o.output.getValue("available").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(available.isNotEmpty())
        val ok = dispatcher.dispatch("explain_framework", """{"module_id":"${available.first()}"}""", ctx())
        assertEquals(available.first(), ok.output.str("module_id"))
    }

    @Test
    fun TC_DISP_09_memoryFactStatus() = runBlocking {
        val o = dispatcher.dispatch("update_memory", """{"add":[
            {"kind":"FACT","text":"She won't accept a pay cut","quote":"never accept a pay cut"},
            {"kind":"FACT","text":"Budget is fixed"}]}""", ctx())
        val ids = o.output.getValue("added").jsonArray.map { it.jsonPrimitive.content }
        val items = memory.open("pd").associateBy { it.id }
        assertEquals(FactStatus.USER_ESTIMATE.name, items.getValue(ids[0]).factStatus)
        assertEquals(FactStatus.AI_INFERRED.name, items.getValue(ids[1]).factStatus)
    }

    @Test
    fun TC_DISP_10_resolveOtherScenarioIgnored() = runBlocking {
        val foreign = memory.add("other", MemoryKind.OPEN_QUESTION, "Elsewhere?", FactStatus.AI_INFERRED)
        val o = dispatcher.dispatch("update_memory", """{"resolve":["$foreign"]}""", ctx())
        assertEquals(0, o.output.getValue("resolved").jsonPrimitive.int)
        assertEquals(1, memory.open("other").size)
    }

    @Test
    fun whatIfPersistsAndLeavesScenario() = runBlocking {
        val before = gateway.scenario
        val o = dispatcher.dispatch("what_if", """{"label":"P2 exits","ops":[{"type":"remove_stakeholder","target_id":"p2","rationale":"r"}]}""", ctx())
        assertSame(before, gateway.scenario)
        assertEquals(UiKind.WHAT_IF, o.uiKind)
        assertNotNull(db.whatIfDao().get(o.refId!!))
    }

    @Test
    fun stressTestAndGetScenario() = runBlocking {
        assertEquals("NOT_APPLICABLE", dispatcher.dispatch("stress_test_inferred", "{}", ctx()).output.str("verdict"))
        assertEquals("invalid_arguments", dispatcher.dispatch("stress_test_inferred", """{"perturbation":0.9}""", ctx()).output.str("error"))
        assertEquals("pd", dispatcher.dispatch("get_scenario", "", ctx()).output.str("scenario_id"))
    }

    @Test
    fun understandingAndSupportEndTurn() = runBlocking {
        val u = dispatcher.dispatch("show_understanding", """{"bullets":["You lead the team","Your manager decides"]}""", ctx())
        assertEquals(UiKind.UNDERSTANDING, u.uiKind)
        assertTrue(u.endsTurn)
        assertEquals("invalid_arguments", dispatcher.dispatch("show_understanding", """{"bullets":["only one"]}""", ctx()).output.str("error"))
        val s = dispatcher.dispatch("show_support", """{"reason":"distress"}""", ctx())
        assertEquals(UiKind.SUPPORT, s.uiKind)
        assertTrue(s.endsTurn)
        val event = conversations.messages(conversationId).last()
        assertEquals("EVENT", event.role)
        assertFalse(event.text.contains("pay cut"))
    }
}
