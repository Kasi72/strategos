package com.drkkr.strategos.domain.human.power

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.human.CoerciveControl
import com.drkkr.strategos.domain.human.ManipulationRadar
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Power Read card (blueprint §6). Built by Kotlin, never by the model, and the only place a law or chapter is named (I-H2 is
 * relaxed for this card only; Call 2 still gets plain hints). Every field has a default so older payloads still decode.
 */
@Serializable
data class PowerRead(
    val leverage: Leverage = Leverage(),
    val theirPlays: List<Play> = emptyList(),
    val yourMoves: List<Move> = emptyList(),
    /** Why there are no moves, from the plan (pm6.1); null when there are moves or the plan gave none. */
    val noMoves: NoMovesReason? = null,
    /** That reason's fixed line ([NoMovesLines]), the only text the card shows in place of moves. */
    val noMovesLine: String? = null,
    /**
     * The leverage chips this card asks the user to set ("yourAlternative", "theyDependOnYou"), see [PowerCardGate]. A chip with no
     * value that is not asked is not shown, so older cards never say "tap to set".
     */
    val ask: List<String> = emptyList()
) {
    /** Who needs whom, the alternatives on each side, who decides, whose clock it is, and the reputation at stake. */
    @Serializable
    data class Leverage(
        val theyNeedYou: String? = null, val youNeedThem: String? = null,
        val yourFallback: String? = null, val theirFallback: String? = null,
        val decides: String? = null, val clock: String? = null, val reputation: String? = null
    ) {
        fun summary(): String = listOfNotNull(
            theyNeedYou?.let { "They need you: $it" }, youNeedThem?.let { "You need them: $it" },
            yourFallback?.let { "Your fallback: $it" }, theirFallback?.let { "Theirs: $it" },
            decides?.let { "Decides: $it" }, clock?.let { "Clock: $it" }, reputation?.let { "At stake: $it" }
        ).joinToString(" · ")
    }

    /** A pattern the radar sees being used on the user, its law, and the counter-move. */
    @Serializable
    data class Play(val sign: String = "", val lawLabel: String = "", val counter: String = "", val seenOnce: Boolean = false)

    /** A hardball move: plain name, law, EV after exposure, what it costs if seen through, and whether the lead is a close call. */
    @Serializable
    data class Move(val name: String = "", val lawLabel: String = "", val ev: Double = 0.0, val ifExposed: String = "", val closeCall: Boolean = false)

    companion object {
        private const val TRUE_MOVE = "little lost — it's true"
        private val IF_EXPOSED = mapOf(
            StrategicId.HOLD_YOUR_NUMBER to "little lost — keeping your number private is expected",
            StrategicId.FIRM_POSTURE to "they may test how firm you really are",
            StrategicId.AGGRESSIVE_ANCHOR to "you'll need the data that justifies it",
            StrategicId.LET_THEM_MOVE_FIRST to "little lost — asking first is normal",
            StrategicId.STRATEGIC_SILENCE to "little lost — a short answer is still an answer",
            StrategicId.COLOR_THE_CHOICES to "they may reorder the options themselves",
            StrategicId.REAL_COMPETITION to TRUE_MOVE,
            StrategicId.CREDIBLE_WALKAWAY to TRUE_MOVE,
            StrategicId.CONTROL_THE_CLOCK to "they may push back on the timing",
            StrategicId.DONT_SHOW_YOUR_HAND to "little lost — privacy about your needs is normal",
            StrategicId.GO_TO_THE_DECIDER to "the person you went around may feel bypassed",
            StrategicId.BUILD_INDISPENSABILITY to "they may spread the work more widely",
            StrategicId.ALLIES_FIRST to "they may see it as lobbying before the meeting",
            StrategicId.STAY_UNREADABLE to "they may read you as inconsistent",
            StrategicId.DISDAIN_THE_JAB to "little lost — staying calm reads as strength"
        )

        fun ifExposed(id: StrategicId): String = IF_EXPOSED.getValue(id)

        /** "REAL_COMPETITION" -> "Real competition"; the walk-away keeps its hyphen. */
        fun nameOf(id: StrategicId): String =
            id.name.lowercase().replace('_', ' ').replace("walkaway", "walk-away").replace("dont ", "don't ").replaceFirstChar { it.uppercase() }

        /**
         * The law numbers in a source ("48 Laws L3, L4" -> "L3/L4"), or the chapter when there is none ("Nudge Ch.15"). A source with
         * neither gets no label, so no chip (pm6.1: no fallback text on the card).
         */
        fun lawLabel(source: String): String {
            val laws = Regex("""L\d+""").findAll(source).map { it.value }.distinct().toList()
            if (laws.isNotEmpty()) return laws.joinToString("/")
            return Regex("""(?:Nudge )?Ch\.\s?\d+""").find(source)?.value.orEmpty()
        }

        /** The card for this turn, from the saved model, the radar and the plan. */
        fun build(s: Scenario, plan: PowerPlan): PowerRead {
            val hc = s.humanContext
            val owner = IntakeChecklist.self(s)
            val them = owner?.let { o -> s.players().firstOrNull { it.id != o.id } }
            val top = s.stakeholders.maxByOrNull { it.authority }
            val decides = when {
                top == null || top.authority <= 0 -> null
                top.id == owner?.id -> "You"
                top.id == them?.id && them.approvalRole.isNotBlank() -> them.approvalRole
                else -> top.name
            }
            val clock = when {
                "artificial_deadline" in hc?.theirTactics.orEmpty() -> "theirs (their deadline may be pressure)"
                hc?.deadline == "hard" -> "a hard deadline"
                hc?.deadline == "soft" -> "a soft deadline"
                else -> null
            }
            val leverage = Leverage(
                theyNeedYou = hc?.theyDependOnYou, youNeedThem = hc?.youDependOnThem,
                yourFallback = hc?.yourAlternative, theirFallback = hc?.theirAlternative,
                decides = decides, clock = clock,
                reputation = when {
                    hc?.relationship in Exposure.CLOSE -> "the relationship"
                    Exposure.continuing(hc) -> "a relationship that continues"
                    hc?.relationship != null -> "this deal"
                    else -> null
                }
            )
            val domain = RelationshipDomains.of(hc?.relationship, null)
            // Every pattern described, newest first, from its first mention (labelled "seen once"); the engine itself still waits
            // for two (support routing, the defensive gain). Coercive patterns in a close relationship route to support instead.
            val routed = ManipulationRadar.scan(hc, domain).routeToSupport
            val skip = if (CoerciveControl.isRoutingDomain(domain)) CoerciveControl.TAGS else emptySet()
            val plays = if (routed || hc == null) emptyList() else hc.theirTactics.filter { it !in skip }.mapNotNull { tag ->
                ManipulationRadar.describe(tag)?.let { Play(it.sign, lawLabel(it.source), it.protect, seenOnce = (hc.tacticTurns[tag] ?: 0) < 2) }
            }
            val moves = plan.cardMoves.map { m -> Move(nameOf(m.tactic.id), lawLabel(m.tactic.tactic.source), m.ev, ifExposed(m.tactic.id), m.closeCall) }
            val noMoves = plan.noMoves.takeIf { moves.isEmpty() }
            return PowerRead(leverage, plays, moves, noMoves, NoMovesLines.of(noMoves, plan.readAs, them?.name))
        }
    }
}

/**
 * When the Power Read card is shown, and which chips it asks for (the Bumble project, 7 Nov: the same card with "tap to set" under
 * every reply). A chip is asked only when its answer could change the advice, and once per conversation; the card is shown only
 * when it differs from the last one in this conversation, or asks something new.
 */
object PowerCardGate {
    const val YOUR_FALLBACK = "yourAlternative"
    const val THEY_NEED_YOU = "theyDependOnYou"

    /**
     * [fallbackMatters]: the engine's pick would change with your fallback known. "They need you" only feeds the Power moves, so it
     * is asked only when there are moves on the card and the relationship is not a close one. [askedBefore]: keys asked earlier here.
     */
    fun ask(read: PowerRead, s: Scenario, fallbackMatters: Boolean, askedBefore: Set<String>): List<String> {
        val hc = s.humanContext
        return buildList {
            if (hc?.yourAlternative == null && fallbackMatters) add(YOUR_FALLBACK)
            if (hc?.theyDependOnYou == null && read.yourMoves.isNotEmpty() && hc?.relationship !in Exposure.CLOSE) add(THEY_NEED_YOU)
        }.filter { it !in askedBefore }
    }

    /** True when [read] should be shown after [last] (the previous card in this conversation, or null). */
    fun show(read: PowerRead, last: PowerRead?): Boolean = last == null || read.ask.isNotEmpty() || read.copy(ask = emptyList()) != last.copy(ask = emptyList())
}

/** Encodes the Power Read for the TOOL_RESULT payload. Unknown keys are ignored and a bad payload decodes to an empty card. */
object PowerReadCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(r: PowerRead): String = json.encodeToString(PowerRead.serializer(), r)
    fun decode(text: String): PowerRead = runCatching { json.decodeFromString(PowerRead.serializer(), text) }.getOrDefault(PowerRead())
}
