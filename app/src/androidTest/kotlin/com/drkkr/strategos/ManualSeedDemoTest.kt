package com.drkkr.strategos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.PlainResult
import com.drkkr.strategos.domain.agent.ReportDigest
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual helper, skipped unless run with `-e seedDemo 1`. Puts a realistic loan conversation, built by the same code the app
 * runs (sketch → ops → apply → engine → plain result), into the app's real database, and sets a placeholder key so the chat
 * composer shows. Lets you look at the Talk, Overview, Journal, Summary and Review screens on an emulator without a live key.
 */
@RunWith(AndroidJUnit4::class)
class ManualSeedDemoTest {
    private val mine = listOf("Co-sign", "Give a fixed amount", "Wait")
    private val his = listOf("Take the loan", "Give up the shop", "Ask parents")

    private val sketch: String = buildString {
        append("""{"goal":"Help my brother without risking our savings or peace at home","people":[{"name":"You","role":"PLAYER","fears":"Losing our savings"},""")
        append("""{"name":"Brother","role":"PLAYER","wants":"Open his shop"},{"name":"Wife","role":"INFLUENCER","veto":true,"fears":"Debt"},{"name":"Parents","role":"INFLUENCER"}],""")
        append(""""options":[""" + mine.joinToString(",") { """{"for":"You","label":"$it"}""" } + "," + his.joinToString(",") { """{"for":"Brother","label":"$it"}""" } + "],")
        append(""""dims":["WELLBEING","RELATIONSHIPS","COST"],"outcomes":[""")
        append(mine.indices.flatMap { i -> his.indices.map { j ->
            """{"picks":{"You":"${mine[i]}","Brother":"${his[j]}"},"scores":{"You":[${3 + i + j},${6 - j},${8 - 3 * i}],"Brother":[${8 - 2 * i - j},${5 + i - j},${2 + j}]}}"""
        } }.joinToString(","))
        append("""],"limits":[{"label":"Don't risk our house","kind":"ETHICAL"}]}""")
    }

    @Test
    fun seedLoanConversation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedDemo") == "1")
        val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)
        container.prepareStorage()
        container.scenarioRepository.load()
        val story = "My brother wants me to co-sign a big loan for his new shop. My wife is strongly against it. My parents always take his side."

        val base = Scenario(title = "Loan for my brother", description = story)
        val ops = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(sketch)!!, base).ops, base)
        val v = ChangeSetValidator.validate(base, ops)
        val accepted = v.accepted.map { it.op }
        val applied = ChangeSetApplier.apply(base, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "demo").scenario
        val saved = (container.scenarioRepository.save(applied) as SaveResult.Success).saved

        val pr = AnalysisPipeline().run(saved)
        val deps = container.counselDeps!!
        val conv = deps.conversations.getOrCreateActive(saved.id, AgentConfig.DEFAULT_MODEL, "2.0.0")
        deps.conversations.append(conv.id, "t1", MessageRole.USER, story)
        deps.conversations.append(conv.id, "t1", MessageRole.TOOL_RESULT, "Ran 12 frameworks in 180 ms on device", toolName = "run_analysis",
            payloadJson = ReportDigest.build(pr, saved).toString(), uiKind = UiKind.RECOMMENDATION)
        deps.conversations.append(conv.id, "t1", MessageRole.ASSISTANT, PlainResult.build(saved, pr, listOf("Brother: makes a choice that matters here", "Wife: can stop this", "Your options: Co-sign, Give a fixed amount, Wait", "A limit: Don't risk our house")))
        deps.conversations.append(conv.id, "t2", MessageRole.USER, "I decided to wait and told him. He said he will ask our parents.")
        deps.conversations.append(conv.id, "t2", MessageRole.ERROR, "The API key was not accepted. Check it in Settings.")

        // Argon: a second named thread, a short trajectory, a journal entry and the note it produced.
        deps.conversations.createNamedThread(saved.id, "Wife's concerns", "FAMILY")
        val trajectory = container.trajectoryRepository!!
        trajectory.recordTurn(saved.id, conv.id, 4.0, 6.0, "CHICKEN_GAME")
        trajectory.recordTurn(saved.id, conv.id, 6.0, -8.0, "STAG_HUNT")
        trajectory.submitQuickCheck(saved.id, conv.id, "Wait", "DID_AS_SUGGESTED", "DE_ESCALATED", "GROUNDED", "Went well")
        val journal = container.journalRepository!!
        val entry = journal.recordEntry("When he goes quiet I want to send five messages in a row.", "ANXIOUS", saved.id)
        journal.addPostNote(
            com.drkkr.strategos.data.db.entities.JournalInsightPostNoteEntity(
                id = "demo-note", journalEntryId = entry.entryId, scenarioId = saved.id, significance = "HIGH",
                habitOrTrigger = "Urgency when facing silence", insightHeadline = "Based on your journal entry: Silence triggers urgency",
                practicalApproach = "Give the quiet a day before you reach out.", doThis = "Wait until tomorrow evening.",
                dontDoThis = "Don't send a \"just checking in\" message.", createdAt = System.currentTimeMillis()
            )
        )

        deps.settings.setAiEnabled(true)
        deps.settings.setConsentVersion(AgentConfig.CONSENT_VERSION)
        container.secretStore!!.putXaiKey("sk-SEEDED_DEMO_KEY_0000")
        println("SEED|scenario=${saved.id}|people=${saved.stakeholders.size}|payoffs=${saved.payoffs.size}|status=${pr.report?.executiveStatus}")
        assertTrue(saved.payoffs.isNotEmpty())
    }
}
