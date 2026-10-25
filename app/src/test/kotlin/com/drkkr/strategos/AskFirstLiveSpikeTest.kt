package com.drkkr.strategos

import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.llm.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase G.3 accept (manual, env-gated, lightweight — 3 live calls): does Call 1 ask the user 1-2 clarifying questions
 * before recommending when the situation is thin? Runs the real [ArgonPrompts.EXTRACTION_PROMPT] over three thin
 * scenarios (relationship / work / corporate) and prints `enough_to_recommend` + the questions.
 *   set -a; source .env; set +a; ./gradlew :app:testDebugUnitTest --tests "*AskFirstLiveSpikeTest*"
 * The scenarios are synthetic, so the extracted questions are safe to print; the key is never printed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AskFirstLiveSpikeTest {
    // Prefer the app's default provider (DeepSeek: key + base + model all match AgentConfig). Fall back to the Groq
    // pairing in .env (XAI_API_KEY is a Groq key, so it must go with XAI_BASE_URL / XAI_MODEL, not the DeepSeek base).
    private val deepseekKey = System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }
    private val key = deepseekKey ?: System.getenv("GROQ_API_KEY")?.takeIf { it.isNotBlank() } ?: System.getenv("XAI_API_KEY")
    private val base = when {
        deepseekKey != null -> AgentConfig.BASE_URL
        else -> System.getenv("XAI_BASE_URL")?.takeIf { it.isNotBlank() } ?: AgentConfig.BASE_URL
    }
    private val model = when {
        deepseekKey != null -> AgentConfig.DEFAULT_MODEL
        else -> System.getenv("XAI_MODEL")?.takeIf { it.isNotBlank() } ?: AgentConfig.DEFAULT_MODEL
    }
    private val client by lazy { XaiResponsesClient(keyProvider = { key }, baseUrl = base) }

    private fun say(check: String, result: String, detail: String = "") = println("ASKFIRST|$check|$result|$detail")

    private fun extractionInput(story: String): JsonArray = buildJsonArray {
        add(buildJsonObject { put("role", "system"); put("content", ArgonPrompts.EXTRACTION_PROMPT) })
        add(buildJsonObject {
            put("role", "user")
            put("content", "APP CONTEXT (what is already saved):\n{\"title\":\"New situation\"}\nUSER SAYS:\n$story")
        })
    }

    @Test
    fun asksBeforeRecommending() = runBlocking {
        Assume.assumeTrue("GROQ_API_KEY / XAI_API_KEY not set", !key.isNullOrBlank())

        // Deliberately thin first messages: no outcomes, little about what each side wants or would do.
        val scenarios = listOf(
            "relationship" to "My girlfriend has been distant for the past week and I don't know what to do.",
            "work" to "My manager keeps piling extra tasks on me and I'm not sure how to handle it.",
            "corporate" to "There's tension with a colleague on my team and it's starting to affect our project."
        )

        var asked = 0
        for ((tag, story) in scenarios) {
            val r = client.create(LlmRequest(model, extractionInput(story), null, maxOutputTokens = AgentConfig.ONE_SHOT_MAX_OUTPUT_TOKENS, temperature = 0.1))
            if (r !is LlmResult.Ok) { say(tag, "FAIL", "call failed: $r"); delay(9_000); continue }
            val sketch = SketchParser.parse(r.output.assistantText())
            if (sketch == null) { say(tag, "FAIL", "unparseable reply"); delay(9_000); continue }
            val qs = sketch.clarifyingQuestions.map { it.trim() }.filter { it.isNotEmpty() }
            val askedHere = sketch.enoughToRecommend == false && qs.isNotEmpty()
            if (askedHere) asked++
            say(tag, if (askedHere) "ASKED" else "RECOMMENDED",
                "enough_to_recommend=${sketch.enoughToRecommend} message_kind=${sketch.messageKind} questions=${qs.size} usage=${r.usage}")
            qs.forEachIndexed { i, q -> say("$tag-Q${i + 1}", "INFO", q) }
            delay(9_000)    // keep under the 8k tokens/minute bucket
        }
        say("SUMMARY", "INFO", "asked_first in $asked/${scenarios.size} thin scenarios")
    }

    /**
     * Phase G.2 accept (manual, env-gated, 2 live calls): a follow-up question about the previous reply should be
     * (1) classified `message_kind=followup_question` by Call 1, and (2) answered directly by [ArgonPrompts.ANSWER_FOLLOWUP_PROMPT]
     * — defining what was asked, not repeating the reply and not inventing a new recommendation.
     */
    @Test
    fun answersFollowUpDirectly() = runBlocking {
        Assume.assumeTrue("GROQ_API_KEY / XAI_API_KEY not set", !key.isNullOrBlank())

        val priorReply = "The strongest move is to hold a calm boundary: say clearly what you need, then give her room to come to you rather than chasing. If she stays distant, that tells you something too."
        val question = "What do you mean by a calm boundary?"

        // 1) Classification: Call 1 sees the prior reply in context, then the question.
        val classifyInput = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", ArgonPrompts.EXTRACTION_PROMPT) })
            add(buildJsonObject { put("role", "assistant"); put("content", priorReply) })
            add(buildJsonObject {
                put("role", "user")
                put("content", "APP CONTEXT (what is already saved):\n{\"title\":\"Distant girlfriend\",\"people\":[\"You\",\"Girlfriend\"]}\nUSER SAYS:\n$question")
            })
        }
        val c = client.create(LlmRequest(model, classifyInput, null, maxOutputTokens = AgentConfig.ONE_SHOT_MAX_OUTPUT_TOKENS, temperature = 0.1))
        if (c is LlmResult.Ok) {
            val sketch = SketchParser.parse(c.output.assistantText())
            val kind = sketch?.messageKind
            say("classify", if (kind == "followup_question") "PASS" else "WRONG", "message_kind=$kind usage=${c.usage}")
        } else say("classify", "FAIL", "call failed: $c")
        delay(9_000)

        // 2) Direct answer: the follow-up prompt should define "calm boundary", not re-recommend.
        val answerInput = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", ArgonPrompts.ANSWER_FOLLOWUP_PROMPT) })
            add(buildJsonObject {
                put("role", "user")
                put("content", "THE PERSON ASKS:\n$question\n\nYOUR PREVIOUS REPLY:\n$priorReply")
            })
        }
        val a = client.create(LlmRequest(model, answerInput, null, maxOutputTokens = 600, temperature = 0.3))
        if (a is LlmResult.Ok) {
            val words = a.output.assistantText().trim()
            val onTopic = words.contains("boundary", ignoreCase = true)
            say("answer", if (words.length in 20..900 && !words.startsWith("{")) "PASS" else "WEAK", "len=${words.length} mentionsBoundary=$onTopic usage=${a.usage}")
            say("answer-TEXT", "INFO", words.replace("\n", " / "))
        } else say("answer", "FAIL", "call failed: $a")
    }

    /**
     * Phase G.4 accept (manual, env-gated, 3 live calls): the user's roster example. A "what do you mean by roster?
     * what should I do here?" double-click on a recommendation should (1) classify as a follow-up and (2) come back with
     * concrete, actionable steps for the SAME advised move — not a restatement. Also checks (3) the coach writes a
     * dynamic `follow_up` closing, never the static "tell me what happens next".
     */
    @Test
    fun actionableDoubleClickAndDynamicClosing() = runBlocking {
        Assume.assumeTrue("GROQ_API_KEY / XAI_API_KEY not set", !key.isNullOrBlank())

        val priorReply = "The strongest move is to stop approving ad-hoc leave and sit down with him until you agree a roster in writing."
        val question = "What do you mean by roster? What should I do here?"

        // 1) Classify the double-click.
        val classifyInput = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", ArgonPrompts.EXTRACTION_PROMPT) })
            add(buildJsonObject { put("role", "assistant"); put("content", priorReply) })
            add(buildJsonObject { put("role", "user"); put("content", "APP CONTEXT (what is already saved):\n{\"title\":\"Colleague taking too many leaves\"}\nUSER SAYS:\n$question") })
        }
        val c = client.create(LlmRequest(model, classifyInput, null, maxOutputTokens = AgentConfig.ONE_SHOT_MAX_OUTPUT_TOKENS, temperature = 0.1))
        if (c is LlmResult.Ok) {
            val kind = SketchParser.parse(c.output.assistantText())?.messageKind
            say("roster-classify", if (kind == "followup_question") "PASS" else "WRONG", "message_kind=$kind usage=${c.usage}")
        } else say("roster-classify", "FAIL", "$c")
        delay(9_000)

        // 2) Actionable answer: defines "roster" and gives concrete steps, not a copy of the prior reply.
        val answerInput = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", ArgonPrompts.ANSWER_FOLLOWUP_PROMPT) })
            add(buildJsonObject { put("role", "user"); put("content", "THE PERSON ASKS:\n$question\n\nYOUR PREVIOUS REPLY:\n$priorReply") })
        }
        val a = client.create(LlmRequest(model, answerInput, null, maxOutputTokens = 600, temperature = 0.3))
        if (a is LlmResult.Ok) {
            val words = a.output.assistantText().trim()
            val definesRoster = words.contains("roster", ignoreCase = true)
            val notARepeat = words.take(40) != priorReply.take(40)
            say("roster-answer", if (definesRoster && notARepeat && words.length in 20..1100) "PASS" else "WEAK", "len=${words.length} definesRoster=$definesRoster notARepeat=$notARepeat usage=${a.usage}")
            say("roster-answer-TEXT", "INFO", words.replace("\n", " / "))
        } else say("roster-answer", "FAIL", "$a")
        delay(9_000)

        // 3) Dynamic closing: a synthetic directive through the coach prompt should yield a non-generic follow_up.
        val directive = """{"move_to_implement":"Agree a written roster with him","complementary_move":null,"recommended_move":"Agree a written roster with him","recommended_why":"It removes the ad-hoc approvals that keep slipping","supporting_frameworks":[],"safe_fallback_move":null,"blocked_or_dominated_moves":[{"move":"Keep approving each request","reason":"it rewards the pattern","triggers":null}],"dominant_dynamic":["A fairness standoff over coverage"],"influencers_in_play":[],"stress_verdict":"ROBUST","stress_flip":null,"known_stakeholder_facts":[],"bayesian_learning_note":null}"""
        val coachInput = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", ArgonPrompts.ENGINE_COACH_PROMPT) })
            add(buildJsonObject { put("role", "user"); put("content", "THE PERSON SAYS:\nMy colleague keeps taking leave and it's hurting the team.\n\nENGINE DIRECTIVE:\n$directive") })
        }
        val g = client.create(LlmRequest(model, coachInput, null, maxOutputTokens = 600, temperature = 0.3, jsonObjectMode = true))
        if (g is LlmResult.Ok) {
            val fu = com.drkkr.strategos.domain.agent.CoachReply.parse(g.output.assistantText())?.followUp?.trim().orEmpty()
            val dynamic = fu.isNotBlank() && !fu.contains("happens next", ignoreCase = true) && !fu.contains("how it goes", ignoreCase = true)
            say("coach-followup", if (dynamic) "PASS" else "WEAK", "follow_up=\"$fu\" usage=${g.usage}")
        } else say("coach-followup", "FAIL", "$g")
    }
}
