package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.*
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.*
import com.drkkr.strategos.data.secure.XorBase64TestCipher
import com.drkkr.strategos.domain.agent.*
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.analysis.TrajectoryCalculator
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.textReply
import com.drkkr.strategos.fixtures.Fx
import com.drkkr.strategos.fixtures.Sketches
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fixes from the six-message replay: engine appendix, AI guesses, veto wording, support card, damping, brevity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArgonFixesTest {
    private fun person(name: String, status: FactStatus, veto: Boolean = false) = Stakeholder(
        id = name.lowercase(), name = name, canVeto = veto, goals = listOf("Wants peace"), fears = listOf("Being blamed"),
        gameRole = GameRole.INFLUENCER, factStatus = status
    )

    // 1b. an updated option list replaces AI guesses and leaves the user's own options alone
    @Test fun fix1_freshOptionListReplacesAiGuessesOnly() {
        val s = Scenario(
            title = "t",
            stakeholders = listOf(Stakeholder(id = "you", name = "You"), Stakeholder(id = "him", name = "Him")),
            actions = listOf(
                Action(id = "a1", stakeholderId = "you", label = "Name his effort out loud first", factStatus = FactStatus.AI_INFERRED),
                Action(id = "a2", stakeholderId = "you", label = "Pause the topic", factStatus = FactStatus.AI_INFERRED),
                Action(id = "a3", stakeholderId = "you", label = "Write him a letter", factStatus = FactStatus.USER_ESTIMATE)
            )
        )
        val sketch = SketchParser.parse("""{"people":[{"name":"You","role":"PLAYER"},{"name":"Him","role":"PLAYER"}],
            "options":[{"for":"You","label":"Name his effort and the waiting out loud first"},{"for":"You","label":"Pause the topic"}]}""")!!
        val ops = SketchConverter.toOps(sketch, s).ops
        assertEquals(listOf("a1"), ops.filter { it.type == OpType.REMOVE_ACTION }.map { it.targetId })      // a2 kept (same wording), a3 untouched
        assertEquals(listOf("Name his effort and the waiting out loud first"), ops.filter { it.type == OpType.ADD_ACTION }.map { it.label })
    }

    @Test fun fix1_aSingleNewOptionDoesNotWipeTheOthers() {
        val s = Scenario(title = "t", stakeholders = listOf(Stakeholder(id = "you", name = "You")),
            actions = listOf(Action(id = "a1", stakeholderId = "you", label = "Wait", factStatus = FactStatus.AI_INFERRED)))
        val ops = SketchConverter.toOps(SketchParser.parse("""{"options":[{"for":"You","label":"Call him"}]}""")!!, s).ops
        assertTrue(ops.none { it.type == OpType.REMOVE_ACTION })
    }

    // 1a + 6. a coach answer has no engine appendix and fits a phone screen
    @Test fun fix1and6_coachAnswerIsShortAndHasNoEngineAppendix() {
        val long = "This is a fairly long sentence about what is going on underneath the surface here. ".repeat(6)
        val g = CoachGuideSketch(long, long, long, List(5) { "A reasonably long do bullet that goes on and on and on $it" }, List(5) { "A reasonably long dont bullet that goes on and on and on $it" })
        val s = Fx.loanFamily()
        val text = PlainResult.build(s, AnalysisPipeline().run(s), listOf("Brother: involved"), g)
        assertTrue("${text.length} chars", text.length <= PlainResult.COACH_MAX_CHARS)
        assertTrue(text.contains("Clear-win move:"))
        listOf("Options to consider", "What to watch for", "What I understood", "Other options you have").forEach { assertFalse(it, text.contains(it)) }
        assertTrue(Regex("• A reasonably").findAll(text).count() <= 4)
        assertEquals(2, Regex("(?<=[.!?])\\s+").split(text.substringAfter("underneath: ").substringBefore("\n").trim()).size)
    }

    // 2. AI-guessed fears, wants and limits are never shown as the person's own
    @Test fun fix2_aiGuessesAreFilteredFromSummaryAndWatchList() {
        val s = Scenario(
            title = "T", stakeholders = listOf(Stakeholder(id = "you", name = "You", factStatus = FactStatus.USER_ESTIMATE), person("Auntie", FactStatus.AI_INFERRED), person("Brother", FactStatus.USER_ESTIMATE)),
            constraints = listOf(
                Constraint(label = "Do not dismiss his experience", kind = Constraint.Kind.ETHICAL, factStatus = FactStatus.AI_INFERRED),
                Constraint(label = "No contact after 10pm", kind = Constraint.Kind.USER_HARD, factStatus = FactStatus.USER_ESTIMATE)
            )
        )
        val summary = PlainSummary.build(s, null)
        assertFalse(summary, summary.contains("Do not dismiss"))
        assertTrue(summary.contains("No contact after 10pm"))
        assertFalse(summary.lowercase().contains("auntie: involved. wants"))
        assertTrue(summary.contains("Brother: involved. Wants: Wants peace"))
        val noGuide = PlainResult.build(s, AnalysisPipeline().run(s), emptyList())
        assertFalse(noGuide.contains("Auntie worries"))
        assertTrue(noGuide.contains("Brother worries about: Being blamed"))
    }

    @Test fun fix2_understoodNeverListsLimitsOrVetoes() {
        val ops = listOf(
            ChangeOp(OpType.ADD_STAKEHOLDER, ref = "p", name = "Wife", gameRole = GameRole.INFLUENCER, canVeto = true),
            ChangeOp(OpType.ADD_CONSTRAINT, ref = "c", label = "Do not dismiss", constraintKind = OpConstraintKind.ETHICAL)
        )
        val lines = PlainResult.understood(ops, Scenario(title = "t"))
        assertEquals(listOf("Wife: involved"), lines)
    }

    // 3. veto wording
    @Test fun fix3_vetoWordingIsHuman() {
        val s = Scenario(title = "T", stakeholders = listOf(Stakeholder(id = "you", name = "You"), person("Wife", FactStatus.USER_ESTIMATE, veto = true)))
        val summary = PlainSummary.build(s, null)
        assertTrue(summary, summary.contains("• Needs Wife's willingness to work"))
        assertFalse(summary.contains("can stop this"))
    }

    // 4. the support card shows once per conversation unless a new message matches the keyword screen
    @Test fun fix4_modelSupportFlagDoesNotRetrigger() = runBlocking {
        val db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scenarios = ScenarioRepository(db, null).also { it.load() }
            val saved = (scenarios.save(Scenario(id = "s1", title = "T")) as SaveResult.Success).saved
            val conversations = ConversationRepository(db)
            val settings = SettingsRepository(db).apply { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) }
            db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
            val fake = FakeLlmClient()
            val controller = CounselController(scope, CounselDeps(
                llm = fake, systemPrompt = { "x" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" }, conversations = conversations,
                changeSets = ChangeSetRepository(db), memory = MemoryRepository(db), settings = settings, whatIfs = db.whatIfDao(),
                hasKey = { true }, oneShot = true, trajectory = TrajectoryRepository(db, scenarios),
                journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), conversations)
            ), CommittingGateway(saved))
            controller.bindScenario("s1").join()
            val flagged = """{"support":true,"ask":"How are you holding up?"}"""
            fake.enqueue(textReply(flagged)).enqueue(textReply(flagged)).enqueue(textReply(flagged))

            controller.send("I used to have panic attacks last year")!!.join(); controller.awaitIdle()
            assertTrue(controller.state.value.supportVisible)
            controller.dismissSupport()
            controller.send("We talked about it with the psychiatrist")!!.join(); controller.awaitIdle()
            assertFalse("model flag must not bring the card back", controller.state.value.supportVisible)
            controller.send("Sometimes I want to end my life")!!.join(); controller.awaitIdle()
            assertTrue("a new keyword match still shows it", controller.state.value.supportVisible)
        } finally { scope.cancel(); db.close() }
    }

    // 5. damping and bands
    @Test fun fix5_trajectoryIsDampedAndBackstoryMovesLess() = runBlocking {
        val db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        try {
            val scenarios = ScenarioRepository(db, null).also { it.load() }
            val saved = (scenarios.save(Scenario(id = "s1", title = "T")) as SaveResult.Success).saved
            val repo = TrajectoryRepository(db, scenarios)
            val a = repo.recordTurn(saved.id, null, 20.0, -20.0, null)                    // 50 + 0.25 * 20 = 55
            assertEquals(55.0, a.trustScore, 0.0); assertEquals(45.0, a.tensionScore, 0.0)
            val b = repo.recordTurn(saved.id, null, 20.0, 20.0, null, backstoryOnly = true)   // +5 asked, capped at 2
            assertEquals(57.0, b.trustScore, 0.0); assertEquals(47.0, b.tensionScore, 0.0)
            val c = repo.recordTurn(saved.id, null, 2.0, 2.0, null)                       // 57 + 0.5 -> rounds to 58, within the cap
            assertTrue(c.trustScore in 57.0..58.0)
        } finally { db.close() }
    }

    @Test fun fix5_bandsAreQualitative() {
        assertEquals("High Tension · Guarded", TrajectoryCalculator.momentumBand(40.0, 70.0, 0.0))
        assertEquals("Strain Rising · Careful", TrajectoryCalculator.momentumBand(50.0, 55.0, -2.0))
        assertEquals("Tension Easing · Cautious", TrajectoryCalculator.momentumBand(50.0, 55.0, 2.0))
        assertEquals("Rebuilding Trust", TrajectoryCalculator.momentumBand(55.0, 40.0, 2.0))
        assertEquals("Holding Steady · Guarded", TrajectoryCalculator.momentumBand(50.0, 50.0, 0.0))
    }

    // 4/6. the prompt carries the new rules
    @Test fun promptStatesTheNewRules() {
        val p = ArgonPrompts.INTERACTION_COACH_PROMPT
        listOf("at most 2 short sentences", "12 words or fewer", "NEVER INVENT", "ONLY if the CURRENT message", "backstory_only").forEach { assertTrue(it, p.contains(it)) }
        assertNotNull(SketchParser.parse(p.substringAfter("JSON SCHEMA:\n")))
    }
}
