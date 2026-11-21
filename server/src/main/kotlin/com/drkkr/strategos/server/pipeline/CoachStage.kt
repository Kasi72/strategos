package com.drkkr.strategos.server.pipeline

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.CoachReply
import com.drkkr.strategos.domain.agent.EngineDirective
import com.drkkr.strategos.domain.agent.PlainResult
import com.drkkr.strategos.domain.agent.SafetyReply
import com.drkkr.strategos.domain.agent.StakesTier
import com.drkkr.strategos.domain.intent.ConfidenceTier
import com.drkkr.strategos.domain.intent.IdentifyGaps
import com.drkkr.strategos.domain.intent.ReplyMode
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

    suspend fun reply(s: Scenario, outcome: EngineOutcome, text: String, understood: List<String>, ctx: CoachContext,
                      mode: ReplyMode = ReplyMode.BRIEF): CoachResult {
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

        val sure = d.confidence == null || d.confidence.tier == ConfidenceTier.HIGH
        // The reply's shape follows what they asked (ReplyMode, chosen in Kotlin). Whatever the mode, the classic coach reply
        // below is the fallback, so a turn never ends worse than it did before; when it is used, the turn is a SCRIPT.
        val table = BriefTable.of(s, d.moveToImplement)
        when (mode) {
            ReplyMode.BRIEF, ReplyMode.COMPARE -> table?.let { brief(it, text, d, ctx, maxTokens + BRIEF_EXTRA_TOKENS, sure, ::ok, mode) }?.let { b ->
                return CoachResult(WalkAwayMask.text(BriefAssembler.text(b), outcome.deal), usedCoach = true, brief = b, mode = mode)
            }
            ReplyMode.ANSWER -> answer(table, text, d, ctx, outcome.deal, ::ok)?.let { a ->
                return CoachResult(WalkAwayMask.text(BriefAssembler.text(a), outcome.deal), usedCoach = true, answer = a, mode = mode)
            }
            ReplyMode.SCRIPT -> Unit
        }

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
        return CoachResult(PlainResult.coach(g, planAhead = d.planAhead != null, sure = sure), usedCoach = true, mode = ReplyMode.SCRIPT)
    }

    /**
     * ANSWER mode: a direct answer from the engine's state, checked like every reply (carries out the move, keeps the
     * walk-away private, names nothing banned, no number the engine did not give). Null falls back to the classic reply.
     */
    private suspend fun answer(table: BriefTable?, text: String, d: EngineDirective, ctx: CoachContext,
                               deal: com.drkkr.strategos.domain.negotiation.DealFrame?, ok: (CoachReply) -> Boolean): AnswerCard? {
        val directive = d.toJson().toString()
        val messages = listOf(
            ChatMessage("system", ArgonPrompts.ENGINE_COACH_PROMPT),
            ChatMessage("system", BriefPrompt.ANSWER),
            ChatMessage("user", userBlock(ctx, text, d) + (table?.let { "\n\n" + BriefPrompt.TABLE_HEADER + it.render() } ?: "")),
        )
        val allowed = (table?.numbers().orEmpty()) + StrayNumbers.ints(directive)
        val theirWords = (ctx.earlierSaid + text).joinToString("\n")
        repeat(2) { attempt ->
            val r = llm.chat(ChatRequest(messages, jsonMode = true, maxTokens = 600, temperature = 0.3, purpose = "COACH"))
            if (r !is ChatResult.Ok) { log.info("ANSWER attempt ${attempt + 1}: call failed (${(r as ChatResult.Failed).message})"); return@repeat }
            val a = AnswerReply.parse(r.text)
            val shape = a?.asCoachReply()
            val stray = a?.let { StrayNumbers.find(it.allText(), allowed, theirWords) }.orEmpty()
            val clean = a != null && shape!!.implements(d.moveToImplement) && a.allText().none(com.drkkr.strategos.domain.human.BannedWords::containedIn) &&
                ok(shape) && DealCheck.usableAnswer(a.answer + "\n" + a.bottomLine, deal) && a.allText().none(com.drkkr.strategos.domain.agent.Placeholders::has) && stray.isEmpty()
            // Flags only: never the reply or the user's words.
            log.info("ANSWER attempt ${attempt + 1}: parsed=${a != null} implements=${shape?.implements(d.moveToImplement)} strayNumbers=${stray.size} usable=$clean")
            if (clean) return AnswerCard(a!!.answer.trim(), a.bottomLine.trim(), (d.nextQuestion?.text ?: a.followUp).trim(), d.moveToImplement.orEmpty())
        }
        return null
    }

    /**
     * The brief: two tries, each checked like a coach reply (carries out the engine's move, keeps the walk-away private,
     * names nothing banned) and also for numbers that are not the engine's. Null sends the turn to the usual reply.
     */
    private suspend fun brief(table: BriefTable, text: String, d: EngineDirective, ctx: CoachContext, maxTokens: Int, sure: Boolean,
                              ok: (CoachReply) -> Boolean, mode: ReplyMode): StrategyBrief? {
        val compare = mode == ReplyMode.COMPARE
        val messages = listOf(
            ChatMessage("system", ArgonPrompts.ENGINE_COACH_PROMPT),
            ChatMessage("system", BriefPrompt.ADDENDUM + if (compare) BriefPrompt.COMPARE_NOTE else ""),
            ChatMessage("user", userBlock(ctx, text, d) + "\n\n" + BriefPrompt.TABLE_HEADER + table.render()),
        )
        val theirWords = (ctx.earlierSaid + text).joinToString("\n")
        repeat(2) { attempt ->
            val r = llm.chat(ChatRequest(messages, jsonMode = true, maxTokens = maxTokens, temperature = 0.3, purpose = "COACH"))
            if (r !is ChatResult.Ok) { log.info("BRIEF attempt ${attempt + 1}: call failed (${(r as ChatResult.Failed).message})"); return@repeat }
            val words = BriefReply.parse(r.text)
            val coachShape = words?.asCoachReply()
            val stray = words?.strayNumbers(table.numbers(), theirWords).orEmpty()
            val assembled = words?.let { BriefAssembler.assemble(table, if (it.hasPlaceholder()) it.stripped() else it, sure, if (compare) "compare" else "brief") }
            // Flags only: never the reply or the user's words.
            log.info("BRIEF attempt ${attempt + 1}: parsed=${words != null} implements=${coachShape?.implements(d.moveToImplement)} " +
                "banned=${words?.namesSomethingBanned()} dealOk=${coachShape?.let(ok)} strayNumbers=${stray.size} covered=${assembled != null}")
            if (words != null && coachShape!!.implements(d.moveToImplement) && !words.namesSomethingBanned() && ok(coachShape) && stray.isEmpty() && assembled != null)
                return assembled
        }
        return null
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
        /** A brief carries more than a reply (pros and cons per move, a plan): this much more room for the model. */
        const val BRIEF_EXTRA_TOKENS = 500
        const val SAID_HEADER = "EVERYTHING THEY SAID EARLIER IN THIS THREAD (oldest first, in their words):\n"
        const val DEAL_RETRY_NOTE = "YOUR LAST REPLY WAS NOT USED: it gave away the walk-away. Keep `walk_away_private` private in every advice field and in `exact_words`: never write that figure, never tell them to state or name it, and never a figure worse for the user than it. Say \"I have a firm limit\" instead, and keep the same engine_move."
    }
}

/** [mode] is the shape the reply actually has (a failed brief or answer falls back to the classic card: SCRIPT). */
data class CoachResult(val text: String, val usedCoach: Boolean, val brief: StrategyBrief? = null, val answer: AnswerCard? = null,
                       val mode: ReplyMode = ReplyMode.SCRIPT)
