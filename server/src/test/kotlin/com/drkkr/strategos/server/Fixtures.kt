package com.drkkr.strategos.server

import com.drkkr.strategos.server.config.ServerConfig
import com.drkkr.strategos.server.llm.ChatRequest
import com.drkkr.strategos.server.llm.ChatResult
import com.drkkr.strategos.server.llm.LlmProvider
import com.drkkr.strategos.server.memory.InMemoryTemporalFallback
import com.drkkr.strategos.server.memory.MemoryService
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plays both model calls, like the app's EngineEchoLlm: extraction (EXTRACT) answers from [extractions] in order (the last
 * repeats); the coach (COACH) reads the ENGINE DIRECTIVE from the request and implements its `move_to_implement`, so a test
 * sees the engine's pick in the reply. Every request is kept for assertions.
 */
class ScriptedLlm(
    private val extractions: List<String>,
    override val name: String = "scripted",
    private val journal: (String) -> String = { """{"mood_tag":"NEUTRAL","support":false,"impacts":[]}""" },
) : LlmProvider {
    override val model = "scripted-1"
    val requests = CopyOnWriteArrayList<ChatRequest>()
    private var turn = 0

    override suspend fun chat(request: ChatRequest): ChatResult {
        requests += request
        return when (request.purpose) {
            "EXTRACT" -> ChatResult.Ok(extractions[minOf(turn++, extractions.lastIndex)])
            "COACH" -> {
                val user = request.messages.last().content
                if (!user.contains("ENGINE DIRECTIVE:\n")) return ChatResult.Ok("Here is what I'd do next, based on what you said before.")
                val directive = Json.parseToJsonElement(user.substringAfter("ENGINE DIRECTIVE:\n")).jsonObject
                val move = directive["move_to_implement"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ChatResult.Ok(buildJsonObject {
                    put("engine_move", move)
                    put("what_happened", "You've been carrying this for a while.")
                    put("clear_win_move", "Do this: $move")
                    put("exact_words", "I'd like to sort this out with you.")
                    put("do_this", buildJsonArray { add("Keep it short") })
                    put("dont_do_this", buildJsonArray { add("Don't chase") })
                }.toString())
            }
            "JOURNAL" -> ChatResult.Ok(journal(request.messages.last().content))
            "SUMMARY" -> ChatResult.Ok("SITUATION: s\nENGINE_ADVISED: a\nUSER_TRIED: t\nOPEN: o")
            else -> ChatResult.Ok("I'm here with you. Please reach out to someone you trust.")
        }
    }

    fun coachRequests() = requests.filter { it.purpose == "COACH" }
}

object Fixtures {
    fun container(llm: LlmProvider, memory: MemoryService = InMemoryTemporalFallback()) =
        AppContainer(ServerConfig(databaseUrl = "jdbc:sqlite::memory:", llmApiKey = null), llm = llm, memoryOverride = memory)

    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    fun hasUuid(s: String) = uuid.containsMatchIn(s)

    const val REPAIR = "Tell Maya calmly what I need and ask what she needs"
    const val BOUNDARY = "Hold my boundary and pause the talk until tomorrow"
    const val REACTIVE = "Send several messages until Maya answers"

    /** A personal standoff with categorical labels only (ArgonPrompts rule 4); the reactive move feels good now and costs the bond. */
    fun personal(posture: String? = null, reaction: String? = null, repair: List<String> = listOf("+", "+", "+"),
                 boundary: List<String> = listOf("+", "+", "+")) = """{
      "goal":"Get back on good terms with Maya","archetype":"STAG_HUNT","relationship":"partner","trust_delta":0,"tension_delta":0,
      ${posture?.let { "\"user_last_move_posture\":\"$it\"," } ?: ""}${reaction?.let { "\"counterpart_observed_reaction\":\"$it\"," } ?: ""}
      "people":[{"name":"You","role":"player"},{"name":"Maya","role":"player","wants":"to feel heard","fears":"being pressured"}],
      "options":[
        {"who":"You","do":"$REPAIR","posture":"repair"},
        {"who":"You","do":"$BOUNDARY","posture":"boundary"},
        {"who":"You","do":"$REACTIVE","posture":"reactive"},
        {"who":"Maya","do":"Open up and talk it through","posture":"receptive"},
        {"who":"Maya","do":"Shut down and keep distance","posture":"defensive"}],
      "dims":["WELLBEING","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[
        {"when":["$REPAIR","Open up and talk it through"],"for_you":${l(repair)},"for_them":["+","+","+"]},
        {"when":["$REPAIR","Shut down and keep distance"],"for_you":["0","0","0"],"for_them":["0","0","0"]},
        {"when":["$BOUNDARY","Open up and talk it through"],"for_you":${l(boundary)},"for_them":["+","+","+"]},
        {"when":["$BOUNDARY","Shut down and keep distance"],"for_you":["0","0","0"],"for_them":["0","0","0"]},
        {"when":["$REACTIVE","Open up and talk it through"],"for_you":["++","--","--"],"for_them":["-","-","-"]},
        {"when":["$REACTIVE","Shut down and keep distance"],"for_you":["+","--","--"],"for_them":["-","--","-"]}]}"""

    private fun l(xs: List<String>) = xs.joinToString(",", "[", "]") { if (it.toDoubleOrNull() != null) it else "\"$it\"" }

    const val OFFER = "Offer 28.5 lakh with a two-year term"
    const val CAVE = "Accept their 25 lakh offer to close today"
    const val HOLD = "Hold at 30 lakh and wait for them"

    /** A B2B price negotiation: walk-away 27L, target 29L. */
    val commercial = """{
      "goal":"Close the FinCorp renewal at a good price","archetype":"COORDINATION","trust_delta":0,"tension_delta":0,
      "negotiation":{"lowest_acceptable":"27 lakh","ideal":"29 lakh","if_no_deal":"sign with the other client"},
      "people":[{"name":"You","role":"player"},{"name":"Ravi","role":"player","wants":"a lower price","fears":"missing Q3 payroll"}],
      "options":[
        {"who":"You","do":"$OFFER","posture":"boundary"},
        {"who":"You","do":"$CAVE","posture":"reactive"},
        {"who":"You","do":"$HOLD","posture":"boundary"},
        {"who":"Ravi","do":"Accept the offer","posture":"receptive"},
        {"who":"Ravi","do":"Push back for a lower price","posture":"defensive"}],
      "dims":["COST","LONG_TERM_VALUE","REPUTATION"],
      "outcomes":[
        {"when":["$OFFER","Accept the offer"],"for_you":["++","++","+"],"for_them":["+","+","+"]},
        {"when":["$OFFER","Push back for a lower price"],"for_you":["+","+","0"],"for_them":["0","0","0"]},
        {"when":["$CAVE","Accept the offer"],"for_you":["--","-","-"],"for_them":["++","+","0"]},
        {"when":["$CAVE","Push back for a lower price"],"for_you":["--","--","-"],"for_them":["++","+","0"]},
        {"when":["$HOLD","Accept the offer"],"for_you":["++","+","0"],"for_them":["-","0","0"]},
        {"when":["$HOLD","Push back for a lower price"],"for_you":["0","-","0"],"for_them":["-","-","0"]}]}"""
}
