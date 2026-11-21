package com.drkkr.strategos.server.pipeline

import com.drkkr.strategos.domain.agent.CoachReply
import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.Placeholders
import com.drkkr.strategos.domain.agent.PriorId
import com.drkkr.strategos.domain.agent.XRayGrid
import com.drkkr.strategos.domain.agent.XRayGridBuilder
import com.drkkr.strategos.domain.agent.XRayPriors
import com.drkkr.strategos.domain.human.BannedWords
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** One of your moves as the brief shows it: the X-ray's numbers (from the engine) and the coach's pros and cons. */
@Serializable
data class BriefRow(
    val move: String,
    /** 0..100 for you if the other person reacts the good way ([StrategyBrief.wellLabel]), and the bad way. */
    val well: Int,
    val bad: Int,
    /** The two blended at the brief's odds. */
    val expected: Int,
    /** HOLDS_UP, GAMBLE or BACKFIRES, read straight off [well] and [bad]. */
    val kind: String,
    val recommended: Boolean,
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
)

@Serializable data class IfThen(val ifThey: String, val thenYou: String)

/** How much one thing that matters to the person counts in the scores, as a share of 100. */
@Serializable data class Weight(val label: String, val pct: Int)

/**
 * A reply laid out as a strategy brief rather than a script: what is going on, the moves on the table with the engine's
 * numbers, the trade-offs of each, the pick and why, and how to carry it out (steps, then what to do for each way they may
 * react). Every number in it is the engine's: the coach only writes the words around them.
 */
@Serializable
data class StrategyBrief(
    val read: String = "",
    /** The engine's odds that the other person reacts the good way, 0..100, and that reaction in words. */
    val oddsPct: Int,
    val oddsLabel: String,
    val wellLabel: String,
    val badLabel: String,
    val weights: List<Weight> = emptyList(),
    val rows: List<BriefRow>,
    /** Odds (whole percent) at which the best move changes; empty when one move leads at any odds. */
    val flips: List<Int> = emptyList(),
    val robust: Boolean,
    val recommendation: String = "",
    val why: String = "",
    val plan: List<String> = emptyList(),
    val ifThen: List<IfThen> = emptyList(),
    val avoid: List<String> = emptyList(),
    /** Words to say, only when the person is about to talk to or text someone. */
    val say: String? = null,
    val followUp: String = "",
    /** "Clear-win move" when the engine is sure, "Best move for now" otherwise. */
    val moveLabel: String = "Best move for now",
    /** "brief", or "compare" when the person weighed named moves (the verdict leads `why`). */
    val mode: String = "brief",
    /** True when [weights] are the app's default weighting, not something this person said: the page says so. */
    val weightsAreDefault: Boolean = false,
)

/** A direct answer to a question (ANSWER mode): prose from the engine's state, then what it means for today's move. */
@Serializable
data class AnswerCard(val answer: String, val bottomLine: String, val followUp: String = "", val move: String = "")

/** The deterministic half of a brief: the engine's table, built the way the X-ray sheet builds it. No model involved. */
data class BriefTable(val grid: XRayGrid, val oddsLabel: String, val weights: List<Weight>, val weightsAreDefault: Boolean = false) {
    val oddsPct: Int get() = (grid.odds * 100).roundToInt()

    /** Every number the coach may use: the scores, the odds, the flip points and the weights. */
    fun numbers(): Set<Int> = buildSet {
        grid.rows.forEach { add(it.well); add(it.bad); add(it.expected) }
        add(oddsPct); add(100 - oddsPct); addAll(grid.flipPoints()); weights.forEach { add(it.pct) }
    }

    /** The table as the coach reads it. */
    fun render(): String = buildString {
        append("ODDS: ").append(oddsPct).append("% that the other person reacts the good way (\"").append(grid.wellLabel)
            .append("\"), ").append(100 - oddsPct).append("% the bad way (\"").append(grid.badLabel).append("\").\n")
        if (weights.isNotEmpty()) append("WHAT THE SCORES WEIGH: ").append(weights.joinToString(", ") { "${it.label} ${it.pct}%" }).append(".\n")
        append("MOVES (score out of 100 for the person if they react the good way / the bad way / blended at the odds):\n")
        grid.rows.forEachIndexed { i, r ->
            append("- \"").append(r.label).append("\": ").append(r.well).append(" / ").append(r.bad).append(" / ").append(r.expected)
                .append(", ").append(kindWords(r.kind.name))
            if (i == grid.bestIndex) append(", THE ENGINE'S PICK")
            append('\n')
        }
        append(standing())
    }

    /** Whole-percent odds (0..100) at which the engine's pick has the top blended score (ties count). */
    fun pickLeadsAt(): List<Int> {
        val pick = grid.rows[grid.bestIndex]
        return (0..100).filter { pct ->
            val p = pct / 100.0
            val top = grid.rows.maxOf { p * it.well + (1 - p) * it.bad }
            p * pick.well + (1 - p) * pick.bad >= top - 1e-9
        }
    }

    /** Where the pick stands on the scores alone, said so the coach cannot claim it scores highest when it does not. */
    fun standing(): String {
        val leads = pickLeadsAt()
        val pick = grid.best
        if (oddsPct in leads) {
            val lo = (oddsPct downTo 0).firstOrNull { it !in leads }; val hi = (oddsPct..100).firstOrNull { it !in leads }
            return when {
                lo == null && hi == null -> "The pick has the top score at any odds."
                hi != null -> "The pick has the top score at these odds; it stops if the odds rise to $hi%."
                else -> "The pick has the top score at these odds; it stops if the odds fall to $lo%."
            }
        }
        val top = grid.rows.maxByOrNull { it.expected }!!
        val where = if (leads.isEmpty()) "at no odds" else if (leads.first() > oddsPct) "only from ${leads.first()}% up" else "only up to ${leads.last()}%"
        return "THE PICK IS NOT THE TOP SCORE AT THESE ODDS: \"${top.label}\" scores ${top.expected} against ${pick.expected}; on these two reactions " +
            "alone the pick leads $where. The engine still picks it because its full analysis weighs more than these two reactions. " +
            "In `why`, say this plainly in one sentence, using `recommended_why`, `dominant_dynamic` or `stress_verdict`; never claim the pick scores highest."
    }

    companion object {
        /** Null when the scenario cannot fill the grid yet (fewer than two replies for them, no scores). */
        fun of(s: Scenario, engineMove: String?): BriefTable? {
            val prior = XRayPriors.of(s).firstOrNull { it.id == PriorId.TAKES_LIMIT_WELL }
            val grid = XRayGridBuilder.build(s, prior?.value ?: XRayPriors.COACH_DEFAULT, engineMove) ?: return null
            return BriefTable(grid, prior?.label ?: "It goes well", weights(s), weightsAreDefault = s.weights == Scenario.defaultWeights())
        }

        /** The weights of the dimensions the scores actually use, as whole shares of 100. */
        fun weights(s: Scenario): List<Weight> {
            val me = IntakeChecklist.self(s)?.id
            val used = s.payoffs.filter { me == null || it.stakeholderId == me }.flatMap { it.payoff.values.keys }.distinct()
            val w = used.associateWith { s.weights[it] ?: 1.0 }.filterValues { it > 0 }
            val total = w.values.sum().takeIf { it > 0 } ?: return emptyList()
            return w.entries.sortedByDescending { it.value }.map { (dim, v) -> Weight(pretty(dim.name), (v / total * 100).roundToInt()) }
        }

        private fun pretty(name: String) = name.lowercase().replace('_', ' ').replace("long term", "long-term").replaceFirstChar { it.uppercase() }
        fun kindWords(kind: String) = when (kind) { "HOLDS_UP" -> "holds up either way"; "BACKFIRES" -> "hurts either way"; else -> "a gamble on how they react" }
    }
}

/** What the coach returns in brief mode ([BriefPrompt.ADDENDUM]). */
@Serializable
data class BriefReply(
    @SerialName("engine_move") val engineMove: String? = null,
    val read: String = "",
    val options: List<BriefOptionWords> = emptyList(),
    val recommendation: String = "",
    val why: String = "",
    val plan: List<String> = emptyList(),
    @SerialName("if_then") val ifThen: List<BriefBranch> = emptyList(),
    val avoid: List<String> = emptyList(),
    @SerialName("exact_words") val exactWords: String = "",
    @SerialName("follow_up") val followUp: String = "",
) {
    /** The same reply in the coach's usual shape, so the app's own checks (move, walk-away, banned names) apply unchanged. */
    fun asCoachReply() = CoachReply(engineMove, read, recommendation, exactWords, plan + ifThen.map { "If they ${it.ifThey}, ${it.thenYou}" }, avoid, followUp)

    /** Every line the person will read, for the checks that look at all of it. */
    fun allText(): List<String> = listOf(read, recommendation, why, exactWords, followUp) + plan + avoid +
        ifThen.flatMap { listOf(it.ifThey, it.thenYou) } + options.flatMap { it.pros + it.cons }

    fun namesSomethingBanned() = allText().any(BannedWords::containedIn)

    /** The reply with any [placeholder] removed from every line. */
    fun stripped(): BriefReply {
        fun t(x: String) = Placeholders.strip(x)
        fun l(x: List<String>) = x.map(::t).filter { it.isNotBlank() }
        return copy(read = t(read), recommendation = t(recommendation), why = t(why), exactWords = t(exactWords), followUp = t(followUp),
            plan = l(plan), avoid = l(avoid), ifThen = ifThen.map { BriefBranch(t(it.ifThey), t(it.thenYou)) },
            options = options.map { it.copy(pros = l(it.pros), cons = l(it.cons)) })
    }
    fun hasPlaceholder() = allText().any(Placeholders::has)

    /** Numbers in the words that are not the engine's, the person's, or small counts ("two steps", "1 week"). */
    fun strayNumbers(allowed: Set<Int>, theirWords: String): Set<Int> = StrayNumbers.find(allText(), allowed, theirWords)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        fun parse(text: String): BriefReply? = runCatching {
            json.decodeFromString(serializer(), text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        }.getOrNull()?.takeIf { it.recommendation.isNotBlank() }
    }
}

/** The one rule for every mode: a number the person reads is the engine's, the person's own, or a small count. */
object StrayNumbers {
    private val NUMBER = Regex("""\d+""")
    fun ints(text: String): Set<Int> = NUMBER.findAll(text).mapNotNull { it.value.toIntOrNull() }.toSet()
    fun find(lines: List<String>, allowed: Set<Int>, theirWords: String): Set<Int> {
        val theirs = ints(theirWords)
        return lines.flatMap { ints(it) }.filter { it > 12 && it !in allowed && it !in theirs }.toSet()
    }
}

/** What the coach returns in ANSWER mode ([BriefPrompt.ANSWER]). */
@Serializable
data class AnswerReply(
    @SerialName("engine_move") val engineMove: String? = null,
    val answer: String = "",
    @SerialName("bottom_line") val bottomLine: String = "",
    @SerialName("follow_up") val followUp: String = "",
) {
    fun allText() = listOf(answer, bottomLine, followUp)
    /** Checked like a coach reply: the bottom line must carry out the engine's move. */
    fun asCoachReply() = CoachReply(engineMove, answer, bottomLine, "", emptyList(), emptyList(), followUp)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        fun parse(text: String): AnswerReply? = runCatching {
            json.decodeFromString(serializer(), text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        }.getOrNull()?.takeIf { it.answer.length >= 20 && it.bottomLine.isNotBlank() }
    }
}

@Serializable data class BriefOptionWords(val move: String = "", val pros: List<String> = emptyList(), val cons: List<String> = emptyList())
@Serializable data class BriefBranch(@SerialName("if") val ifThey: String = "", @SerialName("then") val thenYou: String = "")

object BriefPrompt {
    /**
     * Sent as a second system message after ENGINE_COACH_PROMPT, which stays first and unchanged so the prefix cache holds.
     * It keeps every rule of that prompt about what may be advised (the move, the walk-away, delivery, no manipulation) and
     * changes only the shape of the answer and the ban on numbers, which no longer applies to the engine's own table.
     */
    const val ADDENDUM = """
STRATEGY BRIEF MODE. This turn you answer as a strategist laying out the decision, not only as a script writer. Every rule above about WHAT you may advise still holds (the move, the walk-away, delivery, timing, no manipulation, no placeholders, no named laws or books). These parts change:

A. NUMBERS. You get OPTIONS ON THE TABLE: the engine's own scores for each move. You MAY use those numbers, the odds and the flip points, exactly as given, to compare moves ("scores 71 either way", "only pays off if he takes it well"). This replaces the "never a number" and "words only" parts of rules 28, 29 and 30 for these figures only. Never invent any other number, percentage or score.
B. SHAPE. Return ONLY this JSON object, instead of the JSON SCHEMA above:
{
  "engine_move": "move_to_implement, copied exactly",
  "read": "1 to 2 sentences: what is really going on and what is at stake (the what_happened rules apply)",
  "options": [{"move": "a move copied exactly from OPTIONS ON THE TABLE", "pros": ["..."], "cons": ["..."]}],
  "recommendation": "the move to make, carrying out move_to_implement (the clear_win_move rules apply)",
  "why": "1 to 2 sentences: why this beats the other moves here, citing the table where it helps",
  "plan": ["step 1", "step 2"],
  "if_then": [{"if": "how the other person reacts, a short clause with their name or he/she/they", "then": "what the person does next"}],
  "avoid": ["..."],
  "exact_words": "",
  "follow_up": "..."
}
C. OPTIONS. One entry per move on the table, in the table's order, `move` copied word for word. 1 or 2 `pros` and 1 or 2 `cons` each, 14 words or fewer, concrete to THIS situation (what it gets them, what it risks, how the other person is likely to take it). The table already shows every score next to the move: do not repeat scores in `pros` or `cons`. A move that "hurts either way" still gets an honest pro.
D. HOW TO TACKLE IT. `plan` is 2 to 4 concrete steps in order, with timing where it matters, that carry out the recommendation. `if_then` is 1 to 3 branches for how the other person may react (use the table's good and bad reactions, and `plan_ahead` if present): what they do, then exactly what the person does next. If the pick is fragile, one branch says what to switch to.
E. AVOID. 1 or 2 bullets, rule 3 applies (the blocked or dominated moves and what they would trigger).
F. WORDS TO SAY. Always leave `exact_words` empty. Words to say are a separate step the person asks for; `plan` says what to do, not the script.
G. BREVITY. The whole brief should read in under a minute: short sentences, no repetition between `why`, `pros` and `plan`.
"""

    /** Added after [ADDENDUM] when the person weighed named moves ("A or B?"). */
    const val COMPARE_NOTE = """
COMPARE. The person is weighing specific moves (`compare` in the directive names them, with its `better`, `why` and each move's `band`). Begin `why` with the verdict between exactly those moves: which is better and why, or that they are too close to call. Then the rest of the brief as usual.
"""

    /**
     * ANSWER mode, sent after ENGINE_COACH_PROMPT like [ADDENDUM]: a direct question gets a direct answer, no script card.
     */
    const val ANSWER = """
ANSWER MODE. The person asked a direct question. Answer it; do not write a script or a list of do's and don'ts. Every rule above about WHAT you may advise still holds (the move, the walk-away, no manipulation, no placeholders, no named laws or books).
A. NUMBERS. You MAY cite the figures in OPTIONS ON THE TABLE (if given) and the numbers in the ENGINE DIRECTIVE, exactly as given. Never invent any other number, percentage, score or odds.
B. ANSWER FROM THE ENGINE. `what_if` present: say what shifts and whether the best move changes (rule 27). `their_reaction` present: how they would likely take it and why (rule 29; the table's numbers may be cited). `plan_ahead` present: each branch, "if they ..., then you ..." (rule 31). Otherwise answer from the directive and the table.
C. Return ONLY this JSON object, instead of the JSON SCHEMA above:
{
  "engine_move": "move_to_implement, copied exactly",
  "answer": "2 to 5 short sentences that answer their question directly",
  "bottom_line": "one sentence: what this means for today's move, carrying out move_to_implement",
  "follow_up": "one short, situation-specific next step or question (rules 10 and 26)"
}
"""

    const val TABLE_HEADER = "OPTIONS ON THE TABLE (computed by the engine, authoritative):\n"
}

/** Builds the stored brief from the engine's table and the coach's words; null when the words do not cover the table. */
object BriefAssembler {
    fun assemble(table: BriefTable, words: BriefReply, sure: Boolean, mode: String = "brief"): StrategyBrief? {
        val g = table.grid
        val byMove = words.options.associateBy { norm(it.move) }
        val rows = g.rows.mapIndexed { i, r ->
            val w = byMove[norm(r.label)]
            BriefRow(r.label, r.well, r.bad, r.expected, r.kind.name, i == g.bestIndex,
                w?.pros.orEmpty().map(String::trim).filter(String::isNotEmpty).take(2),
                w?.cons.orEmpty().map(String::trim).filter(String::isNotEmpty).take(2))
        }
        // A brief without trade-offs for most moves is the old reply in a table: not worth showing.
        if (rows.count { it.pros.isNotEmpty() || it.cons.isNotEmpty() } * 2 < rows.size) return null
        return StrategyBrief(
            read = words.read.trim(), oddsPct = table.oddsPct, oddsLabel = table.oddsLabel, wellLabel = g.wellLabel, badLabel = g.badLabel,
            weights = table.weights, rows = rows, flips = g.flipPoints(), robust = g.robust,
            recommendation = words.recommendation.trim(), why = words.why.trim(),
            plan = words.plan.map(String::trim).filter(String::isNotEmpty).take(4),
            ifThen = words.ifThen.filter { it.ifThey.isNotBlank() && it.thenYou.isNotBlank() }.take(3).map { IfThen(condition(it.ifThey), it.thenYou.trim()) },
            avoid = words.avoid.map(String::trim).filter(String::isNotEmpty).take(2),
            // Words to say are opt-in ("Draft exact words"): a brief never carries them, whatever the model returned.
            say = null,
            followUp = words.followUp.trim(),
            moveLabel = if (sure) "Clear-win move" else "Best move for now",
            mode = mode, weightsAreDefault = table.weightsAreDefault,
        )
    }

    /** An answer as plain text: the stored reply, for memory and for clients that draw only text. */
    fun text(a: AnswerCard): String = buildString {
        append(a.answer.trim())
        if (a.bottomLine.isNotBlank()) append("\n\nBottom line: ").append(a.bottomLine.trim())
        if (a.followUp.isNotBlank()) append("\n\n").append(a.followUp.trim())
    }

    /**
     * The brief as plain text: what is stored as the turn's reply, fed to memory and the rolling summary, and shown by any
     * client that does not draw the brief. The X-ray numbers are written out so the text stands on its own.
     */
    fun text(b: StrategyBrief): String = buildString {
        if (b.read.isNotBlank()) append("What's happening underneath: ").append(b.read).append("\n\n")
        append(b.moveLabel).append(": ").append(b.recommendation).append('\n')
        if (b.why.isNotBlank()) append(b.why).append('\n')
        append("\nYour options (odds ").append(b.oddsPct).append("%: ").append(b.oddsLabel).append(")\n")
        b.rows.forEach { r ->
            append("• ").append(r.move).append(": ").append(r.expected).append("/100 (")
                .append(r.well).append(" if it goes well, ").append(r.bad).append(" if it doesn't)")
            if (r.recommended) append(" ← the pick")
            append('\n')
            r.pros.forEach { append("  + ").append(it).append('\n') }
            r.cons.forEach { append("  − ").append(it).append('\n') }
        }
        if (b.plan.isNotEmpty() || b.ifThen.isNotEmpty()) {
            append("\nHow to tackle it\n")
            b.plan.forEachIndexed { i, s -> append(i + 1).append(". ").append(s).append('\n') }
            b.ifThen.forEach { append("• If ").append(it.ifThey).append(": ").append(it.thenYou).append('\n') }
        }
        if (b.avoid.isNotEmpty()) { append("\nDon't do this:\n"); b.avoid.forEach { append("• ").append(it).append('\n') } }
        b.say?.let { append("\nWhat to say: \"").append(it.trim('"')).append("\"\n") }
        if (b.followUp.isNotBlank()) append('\n').append(b.followUp)
    }.trim()

    /** "if they go quiet" / "If Arjun shrugs" / "they go quiet" all become the bare clause, drawn after "If". */
    fun condition(s: String): String = s.trim().removePrefix("If ").removePrefix("if ").trim().trimEnd(',', ':')

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
