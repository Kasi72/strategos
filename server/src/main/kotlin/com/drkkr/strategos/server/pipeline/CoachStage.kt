package com.drkkr.strategos.server.pipeline

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.CoachReply
import com.drkkr.strategos.domain.agent.EngineDirective
import com.drkkr.strategos.domain.agent.PlainResult
import com.drkkr.strategos.domain.agent.SafetyReply
import com.drkkr.strategos.domain.agent.StakesTier
import com.drkkr.strategos.domain.intent.ConfidenceTier
import com.drkkr.strategos.domain.intent.IdentifyGaps
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealCheck
import com.drkkr.strategos.domain.negotiation.NegotiationView
import com.drkkr.strategos.domain.negotiation.WalkAwayMask
import com.drkkr.strategos.server.llm.ChatMessage
import com.drkkr.strategos.server.llm.ChatRequest
import com.drkkr.strategos.server.llm.ChatResult
import com.drkkr.strategos.server.llm.LlmProvider

/** What Call 2 reads besides the directive: long-term memory, the thread's arc, and what the person said before. */
data class CoachContext(
    val memoryBlock: String,
    val rollingSummary: String,
    val earlierSaid: List<String>,
)

/**
 * Stage 3 (SDD §3.3): the memory-aware companion. System message 1 is the app's ENGINE_COACH_PROMPT, unchanged on every turn
 * so DeepSeek's prefix cache stays warm; everything that changes rides in the user message after it.
 *
 * The guard (CompanionGuard in the SDD) is the app's own: a reply is shown only when it implements the engine's move, names
 * no law or book, keeps the walk-away private, and has no [placeholder]. Otherwise the engine's own text is shown.
 */
class CoachStage(private val llm: LlmProvider) {
    private val log = org.slf4j.LoggerFactory.getLogger("strategos.coach")

    suspend fun reply(s: Scenario, outcome: EngineOutcome, text: String, understood: List<String>, ctx: CoachContext): CoachResult {
        val d = outcome.directive
        val engineText = WalkAwayMask.text(PlainResult.build(s, NegotiationView.withinWalkAway(outcome.savedResult, outcome.deal), understood), outcome.deal)
        if (!d.ready) {
            // Kotlin decides readiness: with a gap that matters, ask that one question instead of advising.
            if (IdentifyGaps.asksInsteadOfAdvice(d.nextQuestion)) return CoachResult(d.nextQuestion!!.text, usedCoach = false)
            return CoachResult(engineText, usedCoach = false)
        }
        val maxTokens = when (d.stakesTier) { StakesTier.HIGH -> 800; StakesTier.LOW -> 400; else -> 600 }
        val band = DealCheck.band(outcome.view.negotiationContext, outcome.deal)
        fun ok(r: CoachReply) = DealCheck.usable(r, outcome.deal) && DealCheck.usableWithoutFrame(r, band)

        var messages = listOf(
            ChatMessage("system", ArgonPrompts.ENGINE_COACH_PROMPT),
            ChatMessage("user", userBlock(ctx, text, d)),
        )
        var usable: CoachReply? = null
        for (attempt in 0..1) {
            val r = llm.chat(ChatRequest(messages, jsonMode = true, maxTokens = maxTokens, temperature = 0.3, purpose = "COACH"))
            if (r !is ChatResult.Ok) { log.info("COACH attempt ${attempt + 1}: call failed (${(r as ChatResult.Failed).message})"); continue }
            val reply = CoachReply.parse(r.text)
            // Flags only: never the reply or the user's words.
            log.info("COACH attempt ${attempt + 1}: parsed=${reply != null} implements=${reply?.implements(d.moveToImplement)} banned=${reply?.namesSomethingBanned()} dealOk=${reply?.let { ok(it) }} placeholder=${reply?.hasPlaceholder()}")
            if (reply != null && reply.implements(d.moveToImplement) && !reply.namesSomethingBanned() && ok(reply)) {
                if (!reply.hasPlaceholder()) { usable = reply; break }
                usable = reply
            }
            if (attempt == 0 && reply != null && !ok(reply)) {
                messages = messages.dropLast(1) + ChatMessage("user", DEAL_RETRY_NOTE + "\n\n" + messages.last().content)
            }
        }
        val reply = usable?.let { if (it.hasPlaceholder()) it.stripped() else it } ?: return CoachResult(engineText, usedCoach = false)
        var g = reply.guide().copy(
            pill = listOfNotNull(d.moveToImplement?.takeIf { it.isNotBlank() }, d.dynamicLabel, if (d.stressVerdict == "FRAGILE") "Fragile" else "Robust").joinToString(" · "),
            watchFor = d.watchFor.map { "${it.sign} ${it.protect}" },
            tactics = d.delivery?.tactics.orEmpty().map { it.id.name },
        )
        if (d.nextQuestion != null) g = g.copy(followUp = d.nextQuestion.text)
        val sure = d.confidence == null || d.confidence.tier == ConfidenceTier.HIGH
        return CoachResult(PlainResult.coach(g, planAhead = d.planAhead != null, sure = sure), usedCoach = true)
    }

    /** A plain question about the last reply, answered from it (ANSWER_FOLLOWUP_PROMPT); null sends the turn down the full pipeline. */
    suspend fun answerFollowUp(text: String, priorReply: String, reportJson: String?, ctx: CoachContext,
                               deal: com.drkkr.strategos.domain.negotiation.DealFrame?): String? {
        val prior = WalkAwayMask.text(priorReply, deal)
        val digest = reportJson?.let { WalkAwayMask.text(it, deal) }
        val content = buildString {
            if (ctx.memoryBlock.isNotBlank()) append(ctx.memoryBlock).append("\n\n")
            if (ctx.earlierSaid.isNotEmpty()) append(SAID_HEADER).append(ctx.earlierSaid.joinToString("\n") { "- $it" }).append("\n\n")
            append("THE PERSON ASKS:\n").append(text).append("\n\n")
            append("YOUR PREVIOUS REPLY:\n").append(prior.take(1_200))
            if (!digest.isNullOrBlank()) append("\n\nANALYSIS BEHIND IT (for your reference only, never quote scores):\n").append(digest.take(1_500))
        }
        repeat(2) {
            val r = llm.chat(ChatRequest(listOf(ChatMessage("system", ArgonPrompts.ANSWER_FOLLOWUP_PROMPT), ChatMessage("user", content)),
                maxTokens = 600, temperature = 0.3, purpose = "COACH")) as? ChatResult.Ok ?: return null
            val words = r.text.trim().takeIf { it.length >= 20 && !it.startsWith("{") } ?: return null
            if (DealCheck.usableAnswer(words, deal)) return words
            if (it == 1) return WalkAwayMask.text(DealCheck.sanitizeAnswer(words, deal), deal)
        }
        return null
    }

    /** Support turn (I-H5): no strategy, a short warm reply pointing to someone they trust. */
    suspend fun safetyReply(text: String): String {
        val messages = listOf(ChatMessage("system", ArgonPrompts.SAFETY_REPLY_PROMPT), ChatMessage("user", "THE PERSON SAYS:\n$text"))
        repeat(2) {
            val r = llm.chat(ChatRequest(messages, maxTokens = 300, temperature = 0.3, purpose = "SAFETY"))
            if (r is ChatResult.Ok && SafetyReply.acceptable(r.text.trim())) return r.text.trim()
        }
        return SafetyReply.FALLBACK
    }

    private fun userBlock(ctx: CoachContext, text: String, d: EngineDirective) = buildString {
        if (ctx.memoryBlock.isNotBlank()) append(ctx.memoryBlock).append("\n\n")
        if (ctx.rollingSummary.isNotBlank()) append("THE STORY SO FAR (rolling summary of this thread):\n").append(ctx.rollingSummary).append("\n\n")
        if (ctx.earlierSaid.isNotEmpty()) append(SAID_HEADER).append(ctx.earlierSaid.joinToString("\n") { "- $it" }).append("\n\n")
        append("THE PERSON SAYS:\n").append(text).append("\n\nENGINE DIRECTIVE:\n").append(d.toJson())
    }

    companion object {
        const val SAID_HEADER = "EVERYTHING THEY SAID EARLIER IN THIS THREAD (oldest first, in their words):\n"
        const val DEAL_RETRY_NOTE = "YOUR LAST REPLY WAS NOT USED: it gave away the walk-away. Keep `walk_away_private` private in every advice field and in `exact_words`: never write that figure, never tell them to state or name it, and never a figure worse for the user than it. Say \"I have a firm limit\" instead, and keep the same engine_move."
    }
}

data class CoachResult(val text: String, val usedCoach: Boolean)
