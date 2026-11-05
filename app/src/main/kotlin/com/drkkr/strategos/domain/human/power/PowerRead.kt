package com.drkkr.strategos.domain.human.power

import com.drkkr.strategos.domain.agent.IntakeChecklist
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
    val noHardballReason: String? = null,
    val closeDomainNote: String? = null
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
    data class Play(val sign: String = "", val lawLabel: String = "", val counter: String = "")

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
            id.name.lowercase().replace('_', ' ').replace("walkaway", "walk-away").replaceFirstChar { it.uppercase() }

        /** The law numbers in a source ("48 Laws L3, L4" -> "L3/L4"), or the chapter when there is none ("Nudge Ch.15"). */
        fun lawLabel(source: String): String {
            val laws = Regex("""L\d+""").findAll(source).map { it.value }.distinct().toList()
            if (laws.isNotEmpty()) return laws.joinToString("/")
            return Regex("""(?:Nudge )?Ch\.\s?\d+""").find(source)?.value ?: source
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
                reputation = if (Exposure.continuing(hc)) "a relationship that continues" else if (hc?.relationship != null) "this deal" else null
            )
            val domain = RelationshipDomains.of(hc?.relationship, null)
            val plays = ManipulationRadar.scan(hc, domain).watchFor.map { Play(it.sign, lawLabel(it.source), it.protect) }
            val moves = plan.cardMoves.map { m -> Move(nameOf(m.tactic.id), lawLabel(m.tactic.tactic.source), m.ev, ifExposed(m.tactic.id), m.closeCall) }
            return PowerRead(leverage, plays, moves, plan.noHardballReason, plan.closeDomainNote)
        }
    }
}

/** Encodes the Power Read for the TOOL_RESULT payload. Unknown keys are ignored and a bad payload decodes to an empty card. */
object PowerReadCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(r: PowerRead): String = json.encodeToString(PowerRead.serializer(), r)
    fun decode(text: String): PowerRead = runCatching { json.decodeFromString(PowerRead.serializer(), text) }.getOrDefault(PowerRead())
}
