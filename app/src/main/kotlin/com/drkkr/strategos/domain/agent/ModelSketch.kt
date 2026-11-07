package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.human.DeliveryTags
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.game.RubricPayoffs
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.contentOrNull

/**
 * SIMPLE PROFILE: what the model returns for one message. Small and flat on purpose; [toOps] turns it into change ops,
 * so every existing check (normalizer, validator, applier, provenance) still applies.
 */
@Serializable
data class ModelSketch(
    val goal: String? = null,
    val people: List<SketchPerson> = emptyList(),
    val options: List<SketchOption> = emptyList(),
    val dims: List<String> = emptyList(),
    val outcomes: List<SketchOutcome> = emptyList(),
    val limits: List<@Serializable(with = LimitSerializer::class) SketchLimit> = emptyList(),
    @Serializable(with = TextOrListSerializer::class) val ask: String? = null,
    val support: Boolean? = null,
    // Argon: coach fields. All default, so v2.0 sketches parse unchanged.
    val archetype: String? = null,
    @SerialName("trust_delta") val trustDelta: Double = 0.0,
    @SerialName("tension_delta") val tensionDelta: Double = 0.0,
    /** True when the message is history only, so the trajectory moves less ([TrajectoryRepository.recordTurn]). */
    @SerialName("backstory_only") val backstoryOnly: Boolean = false,
    /**
     * Phase F.6: what Call 1 observed about this turn's interaction, read only to update the counterpart's learned
     * Bayesian priors in Kotlin ([BayesianUpdater]) — never shown to the user and never itself a fact or a score.
     * "repair" | "boundary" | "reactive" | "none"; "softened" | "stayed_neutral" | "withdrew_or_escalated" | "none".
     */
    @SerialName("user_last_move_posture") val userLastMovePosture: String? = null,
    @SerialName("counterpart_observed_reaction") val counterpartObservedReaction: String? = null,
    /**
     * Phase G.2: how this message relates to the running conversation, so the controller can route it.
     * "situation" (new or changed facts to model — the default) or "followup_question" (the user is asking
     * about the assistant's previous reply, e.g. "what is that?", "why?", "what do you mean?"). A follow-up
     * question is answered directly from the last reply, never by re-running the whole analysis.
     */
    @SerialName("message_kind") val messageKind: String? = null,
    /**
     * Phase G.3 (ask-first): false when the user's message does not yet give enough real signal to rank options
     * honestly (most outcome scores would be guessed). The controller then asks [clarifyingQuestions] instead of
     * recommending. True/null means go ahead. Never a reason to invent scores — when false, leave them unscored.
     */
    @SerialName("enough_to_recommend") val enoughToRecommend: Boolean? = null,
    /** Phase G.3: 1-2 short, targeted questions that would most strengthen the model, used only when [enoughToRecommend] is false. */
    @SerialName("clarifying_questions") val clarifyingQuestions: List<String> = emptyList(),
    /** Only what the user said about a negotiation (fallback, lowest they'd accept, ideal, opening ask, the other side's alternative). Never guessed. */
    val negotiation: SketchNegotiation? = null,
    // Human Layer: how the other person is placed and feeling, and how each move would be carried out. All optional and
    // read only through [SketchHuman] (closed vocabularies; anything else is dropped), so old sketches parse unchanged.
    @Serializable(with = WordSerializer::class) val relationship: String? = null,
    @Serializable(with = SketchPowerSerializer::class) val power: SketchPower? = null,
    @SerialName("status_quo") @Serializable(with = TextOrListSerializer::class) val statusQuo: String? = null,
    @SerialName("status_quo_for_them") @Serializable(with = WordSerializer::class) val statusQuoForThem: String? = null,
    @Serializable(with = WordSerializer::class) val deadline: String? = null,
    @SerialName("their_vulnerable") @Serializable(with = WordSerializer::class) val theirVulnerable: String? = null,
    @SerialName("your_state") @Serializable(with = WordSerializer::class) val yourState: String? = null,
    @SerialName("their_state") @Serializable(with = WordSerializer::class) val theirState: String? = null,
    @SerialName("option_tags") @Serializable(with = OptionTagsSerializer::class) val optionTags: Map<String, List<String>> = emptyMap(),
    @SerialName("their_tactics") @Serializable(with = StringListSerializer::class) val theirTactics: List<String> = emptyList(),
    /** How the user already tried, or is about to, in the option_tags words. Read through [SketchHuman]. */
    @SerialName("last_attempt_tags") @Serializable(with = StringListSerializer::class) val lastAttemptTags: List<String> = emptyList(),
    /** A small whole number; may arrive quoted, so it is read through [SketchHuman]. */
    @SerialName("steps_for_them_to_say_yes") val stepsForThemToSayYes: JsonPrimitive? = null,
    @SerialName("goal_is") @Serializable(with = WordSerializer::class) val goalIs: String? = null,
    /** Power Mode: what the user explicitly asks the app to help them do, when it is manipulative (closed vocabulary, per turn). */
    @SerialName("user_asks_for") @Serializable(with = WordSerializer::class) val userAsksFor: String? = null,
    /** Intent Dispatch ID2.1: what the user asks for this turn, a closed word read through [com.drkkr.strategos.domain.intent.Intent.of]. Never stored. */
    @Serializable(with = WordSerializer::class) val intent: String? = null,
    @SerialName("intent_detail") @Serializable(with = IntentDetailSerializer::class) val intentDetail: IntentDetail? = null,
    /** The hypothetical, kept raw: read only through [whatIf], never by the save path (I-D3). */
    @SerialName("what_if") val rawWhatIf: JsonElement? = null
) {
    /** ID2.1: the intent of the turn; unknown or missing is standard. */
    val intentKind: com.drkkr.strategos.domain.intent.Intent get() = com.drkkr.strategos.domain.intent.Intent.of(intent)

    /**
     * The hypothetical as a sketch (same keys as the main one), one level only: a `what_if` inside it is dropped. Null when
     * absent, empty or unreadable. Read by the intent handlers on copies; [SketchConverter] never reads it.
     */
    val whatIf: ModelSketch? get() {
        val o = rawWhatIf as? JsonObject ?: return null
        val inner = JsonObject(o - "what_if")
        if (inner.isEmpty()) return null
        return runCatching { WHAT_IF_JSON.decodeFromJsonElement(ModelSketch.serializer(), inner) }.getOrNull()
    }
}

private val WHAT_IF_JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

/** ID2.1: the moves or the change the intent is about, in the user's words. */
@Serializable
data class IntentDetail(
    @Serializable(with = TextOrListSerializer::class) val change: String? = null,
    @SerialName("move_a") @Serializable(with = TextOrListSerializer::class) val moveA: String? = null,
    @SerialName("move_b") @Serializable(with = TextOrListSerializer::class) val moveB: String? = null,
    @Serializable(with = TextOrListSerializer::class) val move: String? = null
)

/** Seen live (CP-I2): Call 1 sometimes writes the detail as one string ("wait one more month vs confront him"); it is kept as `change`. */
object IntentDetailSerializer : JsonTransformingSerializer<IntentDetail>(IntentDetail.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when {
        element is JsonObject -> element
        element is JsonPrimitive && element.isString -> JsonObject(mapOf("change" to element))
        else -> JsonObject(emptyMap())
    }
}

/** The counterpart compared with "You". Every field is a closed word, read through [SketchHuman]. */
@Serializable
data class SketchPower(
    @Serializable(with = WordSerializer::class) val relative: String? = null,
    @SerialName("they_depend_on_you") @Serializable(with = WordSerializer::class) val theyDependOnYou: String? = null,
    @SerialName("you_depend_on_them") @Serializable(with = WordSerializer::class) val youDependOnThem: String? = null,
    @SerialName("your_alternative") @Serializable(with = WordSerializer::class) val yourAlternative: String? = null,
    @SerialName("their_alternative") @Serializable(with = WordSerializer::class) val theirAlternative: String? = null,
    @Serializable(with = WordSerializer::class) val setting: String? = null
)

/**
 * One closed word (relationship, a state, a level) that the model sometimes wraps: `{"kind":"manager"}`, `["upset","calm"]`.
 * Seen live: an object under `relationship` made the whole sketch unparseable and the turn failed. An object gives its first
 * text value (keys like type/value/kind first), a list its first item, anything else an empty word. [SketchHuman] then checks the
 * word against the closed vocabulary, so a wrong word is still dropped, never guessed.
 */
object WordSerializer : JsonTransformingSerializer<String>(String.serializer()) {
    private val preferred = listOf("type", "value", "kind", "relationship", "name", "label", "level", "word", "relative")
    private fun word(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull
        is JsonArray -> e.firstNotNullOfOrNull { word(it) }
        is JsonObject -> preferred.firstNotNullOfOrNull { k -> word(e[k]) } ?: e.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> null
    }
    override fun transformDeserialize(element: JsonElement): JsonElement = JsonPrimitive(word(element).orEmpty())
}

/** A string where a list belongs becomes a one-item list; anything that isn't text is skipped. */
private fun lenientList(e: JsonElement): JsonElement = when (e) {
    is JsonArray -> JsonArray(e.filterIsInstance<JsonPrimitive>())
    is JsonPrimitive -> if (e.contentOrNull.isNullOrBlank()) JsonArray(emptyList()) else JsonArray(listOf(e))
    else -> JsonArray(emptyList())
}

object StringListSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = lenientList(element)
}

object OptionTagsSerializer : JsonTransformingSerializer<Map<String, List<String>>>(MapSerializer(String.serializer(), ListSerializer(String.serializer()))) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonObject) JsonObject(element.mapValues { lenientList(it.value) }) else JsonObject(emptyMap())
}

/** `power` should be an object; a bare word or list is ignored rather than failing the whole reply. */
object SketchPowerSerializer : JsonTransformingSerializer<SketchPower>(SketchPower.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement = if (element is JsonObject) element else JsonObject(emptyMap())
}

/** Plain-word negotiation facts as the user stated them. Every field is optional. */
@Serializable
data class SketchNegotiation(
    @SerialName("if_no_deal") @Serializable(with = TextOrListSerializer::class) val ifNoDeal: String? = null,
    @SerialName("lowest_acceptable") @Serializable(with = TextOrListSerializer::class) val lowestAcceptable: String? = null,
    @SerialName("ideal") @Serializable(with = TextOrListSerializer::class) val ideal: String? = null,
    @SerialName("opening_ask") @Serializable(with = TextOrListSerializer::class) val openingAsk: String? = null,
    /** What the OTHER side could do instead. Renamed from `their_alternative` (which the `power` object also uses, for a strength). */
    @SerialName("their_if_no_deal") @Serializable(with = TextOrListSerializer::class) val theirIfNoDeal: String? = null,
    /** The old name, still read so earlier replies and fixtures parse. */
    @SerialName("their_alternative") @Serializable(with = TextOrListSerializer::class) val theirAlternative: String? = null,
    @SerialName("their_limit") @Serializable(with = TextOrListSerializer::class) val theirLimit: String? = null
) {
    fun toContext() = com.drkkr.strategos.domain.model.NegotiationContext(
        batna = ifNoDeal.orEmpty().take(200), reservationPoint = lowestAcceptable.orEmpty().take(200),
        aspirationPoint = ideal.orEmpty().take(200), openingAnchor = openingAsk.orEmpty().take(200),
        counterpartBatna = (theirIfNoDeal ?: theirAlternative).orEmpty().take(200), counterpartReservation = theirLimit.orEmpty().take(200)
    )
    fun hasContent() = toContext().let { it.batna.isNotBlank() || it.reservationPoint.isNotBlank() || it.aspirationPoint.isNotBlank() ||
        it.openingAnchor.isNotBlank() || it.counterpartBatna.isNotBlank() || it.counterpartReservation.isNotBlank() }
}

/** The coach guide. It is no longer part of [ModelSketch] (call 1 extracts only); call 2's [CoachReply] carries it. */
@Serializable
data class CoachGuideSketch(
    @SerialName("what_happened") val whatHappened: String = "",
    @SerialName("clear_win_move") val clearWinMove: String = "",
    @SerialName("exact_words") val exactWords: String = "",
    @SerialName("do_this") val doThis: List<String> = emptyList(),
    @SerialName("dont_do_this") val dontDoThis: List<String> = emptyList(),
    /** Phase G.4: a short, situation-specific closing line (a concrete next step or a real question), replacing the static footer. */
    @SerialName("follow_up") val followUp: String = "",
    /** The engine's one-line label for this reply ("Wait · Mutual trust needed · Robust"), set by Kotlin, never by the model. Shown as the pill. */
    @SerialName("pill") val pill: String = "",
    /** Human Layer: things to watch for (what is being done TO the user), each one plain sentence with its protective move. Joins the Overview's standing do/don't box. */
    @SerialName("watch_for") val watchFor: List<String> = emptyList(),
    /** Human Layer: which delivery tactics this reply used (internal ids, never shown), so a later reaction can teach what worked. */
    @SerialName("tactics") val tactics: List<String> = emptyList()
)

/**
 * Text that may arrive as a list. The saved model is shown to the model with wants and fears as lists, and it copies that
 * form back (seen live: the whole follow-up reply was rejected over `"fears":["…"]`). A list becomes one string.
 */
object TextOrListSerializer : JsonTransformingSerializer<String>(String.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonPrimitive(element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { t -> t.isNotEmpty() } }.joinToString("; "))
        // The Argon prompt mentions `ask.text`, so a question may arrive as {"text": "...", "options": [...]}.
        is JsonObject -> (element["text"] as? JsonPrimitive) ?: JsonPrimitive("")
        else -> element
    }
}

/** A limit may be a plain string (the Argon prompt's example does this) or {label, kind}. */
object LimitSerializer : JsonTransformingSerializer<SketchLimit>(SketchLimit.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive) JsonObject(mapOf("label" to element)) else element
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SketchPerson(
    val name: String = "", val role: String? = null,
    @JsonNames("can_block") val veto: Boolean? = null,
    @Serializable(with = TextOrListSerializer::class) val wants: String? = null,
    @Serializable(with = TextOrListSerializer::class) val fears: String? = null,
    // The saved-model digest calls wants "goals"; accept that spelling too.
    @Serializable(with = TextOrListSerializer::class) val goals: String? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SketchOption(
    @SerialName("for") @JsonNames("who") val owner: String = "", @JsonNames("do") val label: String = "", val posture: String? = null,
    /** The `ruled_out_before` move this option is again, in new words (extraction rule 25). Matched by [com.drkkr.strategos.domain.human.RuledOut.find]. */
    @Serializable(with = TextOrListSerializer::class) val revisits: String? = null,
    /** What the user said since that answers the earlier concern, in their words; null when nothing has (the old ceiling stays). */
    @Serializable(with = TextOrListSerializer::class) val changed: String? = null
)

/**
 * One outcome as Call 1 wrote it. Intent Dispatch ID1.1: each cell is a label (`++ + 0 - --` or the words), read by
 * [com.drkkr.strategos.domain.game.RubricPayoffs]; an older reply's numbers are still read. The raw cells are kept as they came,
 * and [SketchConverter] fills [scores], [forYou] and [forThem] with numbers once the turn's dimensions are known, before
 * [PostureContrast] runs.
 */
@Serializable
data class SketchOutcome(
    val picks: Map<String, String> = emptyMap(),
    @SerialName("scores") val rawScores: Map<String, List<JsonElement>> = emptyMap(),
    // BLUEPRINT-QUESTION: the §5.1 prompt writes outcomes as when/for_you/for_them (first and second player), not picks/scores. Both are read.
    @SerialName("when") val whenPicks: List<String> = emptyList(),
    @SerialName("for_you") val rawForYou: List<JsonElement> = emptyList(),
    @SerialName("for_them") val rawForThem: List<JsonElement> = emptyList(),
    @Transient val scores: Map<String, List<Double>> = emptyMap(),
    @Transient val forYou: List<Double> = emptyList(),
    @Transient val forThem: List<Double> = emptyList(),
    /** Some cell came as a number, not a label (provenance [com.drkkr.strategos.domain.game.RubricPayoffs.AI_SCORED_NUMERIC]). */
    @Transient val numeric: Boolean = false
)

@Serializable
data class SketchLimit(val label: String = "", val kind: String? = null)

/** The result of turning a sketch into ops. [ops] may be empty when the model only asked a question. */
data class SketchOps(val ops: List<ChangeOp>, val ask: String?, val support: Boolean)

object SketchParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Finds the first JSON object in [text] (models add prose or code fences) and decodes it. Null when it doesn't parse. */
    fun parse(text: String): ModelSketch? = tryParse(text).getOrNull()

    /** Like [parse] but keeps the reason, so the debug trace can say why a reply was rejected. */
    fun tryParse(text: String): Result<ModelSketch> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return Result.failure(IllegalArgumentException("no JSON object in the reply"))
        return runCatching { json.decodeFromString(ModelSketch.serializer(), text.substring(start, end + 1)) }
    }
}

object SketchConverter {
    private val dashes = Regex("[\\u2010-\\u2015\\u2212]")
    private val spaces = Regex("\\s+")
    private val placeholders = setOf("short", "string", "text", "word", "...", "n/a", "none", "null", "unknown")

    /** Comparison form of a name or label: lowercase, one kind of hyphen, single spaces (models vary these). */
    internal fun norm(s: String): String = s.trim().lowercase().replace(dashes, "-").replace(spaces, " ")

    /** [label] within [ChangeSetValidator.MAX_LABEL]: cut at the last space that fits, trailing punctuation dropped. */
    internal fun fit(label: String): String {
        if (label.length <= ChangeSetValidator.MAX_LABEL) return label
        val cut = label.take(ChangeSetValidator.MAX_LABEL + 1).substringBeforeLast(' ').trimEnd(',', ';', ':', '-', ' ', '—')
        return cut.ifEmpty { label.take(ChangeSetValidator.MAX_LABEL) }
    }

    /** Text the model filled in, or null when it left a placeholder behind (seen live: "short"). */
    internal fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && norm(it) !in placeholders }

    private const val MAX_PLAYERS = ChangeSetValidator.MAX_PLAYERS
    private const val MAX_OPTIONS_PER_PLAYER = 4
    private const val NEAR_DUPLICATE_MIN = 8
    private const val RATIONALE = "From your description"
    private val DEFAULT_DIMS = listOf(PayoffDimension.WELLBEING, PayoffDimension.RELATIONSHIPS, PayoffDimension.COST)
    private val nonRef = Regex("[^a-z0-9_]+")

    /**
     * ID1.1: every outcome's cells as numbers in [dims] order (labels through [RubricPayoffs]; an older reply's numbers as they
     * were). A player whose cells have a missing one, or too few, gets no numbers for that outcome, so that entry stays missing.
     */
    internal fun numbers(outcomes: List<SketchOutcome>, dims: List<PayoffDimension>): List<SketchOutcome> = outcomes.map { o ->
        val you = RubricPayoffs.row(o.rawForYou, dims)
        val them = RubricPayoffs.row(o.rawForThem, dims)
        val byName = o.rawScores.mapValues { RubricPayoffs.row(it.value, dims) }
        o.copy(
            forYou = you?.values.orEmpty(), forThem = them?.values.orEmpty(),
            scores = byName.mapNotNull { (k, r) -> r?.let { k to it.values } }.toMap(),
            numeric = you?.numeric == true || them?.numeric == true || byName.values.any { it?.numeric == true }
        )
    }

    /** The one clarifying question, unless the model echoed the prompt's instruction instead of asking (seen live). */
    private fun askOf(sketch: ModelSketch): String? =
        clean(sketch.ask)?.takeUnless { norm(it).startsWith("only if") || norm(it).contains("too thin") }

    /**
     * [humanFields] is false for the repair pass: its human readings were already counted by the first request of the turn,
     * and a value must not be "confirmed" twice within one turn.
     */
    fun toOps(
        sketch: ModelSketch, s: Scenario, humanFields: Boolean = true,
        /** What the user's report of how the last reply landed taught about the tactics it used (see TacticLearning). */
        learned: com.drkkr.strategos.domain.human.TacticLearning.Learned? = null
    ): SketchOps {
        // A reply that only asks a question (or flags support) saves nothing.
        val hasContent = sketch.people.isNotEmpty() || sketch.options.isNotEmpty() || sketch.limits.isNotEmpty() ||
            sketch.outcomes.isNotEmpty() || !sketch.goal.isNullOrBlank() || sketch.negotiation?.hasContent() == true
        if (!hasContent) return SketchOps(emptyList(), askOf(sketch), sketch.support == true)
        val ops = mutableListOf<ChangeOp>()
        val taken = mutableSetOf<String>()
        fun ref(base: String): String {
            val slug = base.lowercase().replace(nonRef, "_").trim('_').take(34).ifEmpty { "item" }.padEnd(2, '_')
            var r = slug
            var n = 2
            while (r in taken) r = "${slug}_${n++}"
            taken += r
            return r
        }
        fun key(name: String) = norm(name)

        // ── People ────────────────────────────────────────────────────────────
        val idByName = s.stakeholders.associate { key(it.name) to it.id }.toMutableMap()
        val players = s.players().map { it.id }.toMutableSet()
        // A stand-in name ("Her", "the other person") takes the real name the first time one appears for that side, instead of a
        // second person being added (seen in testing, 7 Nov: the other person stayed "Her" for a whole thread).
        val renamed = PlaceholderNames.rename(s, sketch.people.map { it.name.trim() to it.role })
        renamed?.let { (id, name) ->
            ops += ChangeOp(OpType.UPDATE_STAKEHOLDER, targetId = id, name = name, rationale = RATIONALE)
            idByName[key(name)] = id
        }
        val existingNames = idByName.keys.toSet()

        fun youExists() = s.stakeholders.any { key(it.name) == "you" } || sketch.people.any { key(it.name) == "you" }
        val people = if (youExists()) sketch.people else listOf(SketchPerson("You", "PLAYER")) + sketch.people
        // Human Layer: what the model read about power, status quo and state (closed vocabularies only; AI_INFERRED).
        val reported = sketch.counterpartObservedReaction in setOf("softened", "stayed_neutral", "withdrew_or_escalated")
        // They have reported how the held move went: stop asking "did you send it?".
        val humanReading = if (humanFields) SketchHuman.context(sketch).let { c ->
            if (reported && s.humanContext?.pendingCooling != null) (c ?: HumanContext()).copy(pendingCooling = "") else c
        } else null
        // Routing to support expires after a run of messages with no coercive tag (see CoerciveControl). Only a real reading counts.
        val coerciveNow = humanReading?.theirTactics.orEmpty().any { it in com.drkkr.strategos.domain.human.CoerciveControl.TAGS }
        val coerciveSaved = s.humanContext?.theirTactics.orEmpty().any { it in com.drkkr.strategos.domain.human.CoerciveControl.TAGS }
        val quiet = when {
            !humanFields -> null
            coerciveNow -> 0
            coerciveSaved -> (s.humanContext?.routingQuietTurns ?: 0) + 1
            else -> null
        }
        val withQuiet = quiet?.let { q -> (humanReading ?: HumanContext()).copy(routingQuietTurns = q) } ?: humanReading
        // The user's habits (how they already tried) are kept for three turns, newest first; a turn with none ages the older ones.
        val savedAttempts = s.humanContext?.attemptHistory.orEmpty()
        val thisTurn = humanReading?.lastAttemptTags.orEmpty()
        val history = if (humanFields && (thisTurn.isNotEmpty() || savedAttempts.isNotEmpty())) (listOf(thisTurn) + savedAttempts).take(3) else null
        val withHistory = history?.let { h -> (withQuiet ?: HumanContext()).copy(attemptHistory = h, lastAttemptTags = h.flatten().distinct()) } ?: withQuiet
        val learnedCtx = if (humanFields && learned != null) (withHistory ?: HumanContext()).copy(tacticSuccess = learned.success, learnedFromMessage = learned.messageId) else withHistory
        // Power Mode: `user_asks_for` lives for one turn. A turn that says nothing about it still sends a patch, so a saved one is cleared.
        val humanCtx = if (humanFields && learnedCtx == null && s.humanContext?.userAsksFor != null) HumanContext() else learnedCtx
        val fields = ChangeOp(
            OpType.SET_SCENARIO_FIELDS, rationale = RATIONALE,
            scenarioFields = ScenarioFieldsPatch(
                decisionOwner = if (s.decisionOwner.isBlank()) "You" else null,
                stakes = sketch.goal?.takeIf { s.stakes.isBlank() && it.isNotBlank() }?.take(200),
                // A relationship that goes on: this is what lets the repeated-game modules apply.
                repeated = true.takeIf { !s.repeated && sketch.options.any { o -> Postures.clean(o.posture) != null } },
                negotiationContext = sketch.negotiation?.takeIf { it.hasContent() }?.toContext(),
                humanContext = humanCtx
            ),
            source = OpSource.AI_INFERRED.takeIf { humanCtx != null }
        )
        if (fields.scenarioFields!!.decisionOwner != null || fields.scenarioFields.stakes != null || fields.scenarioFields.repeated != null ||
            fields.scenarioFields.negotiationContext != null || fields.scenarioFields.humanContext != null) ops += fields

        for (p in people) {
            val name = p.name.trim()
            if (name.isEmpty() || name.length > ChangeSetValidator.MAX_NAME) continue
            val k = key(name)
            if (k in existingNames) {
                val id = idByName.getValue(k)
                if (p.veto == true || (clean(p.wants) ?: clean(p.goals)) != null || clean(p.fears) != null) {
                    ops += ChangeOp(
                        OpType.UPDATE_STAKEHOLDER, targetId = id, rationale = RATIONALE,
                        canVeto = p.veto?.takeIf { it }, goals = (clean(p.wants) ?: clean(p.goals))?.let { listOf(it.take(120)) },
                        fears = clean(p.fears)?.let { listOf(it.take(120)) }
                    )
                }
                continue
            }
            if (k in idByName) continue                                    // repeated within the sketch
            val wantsPlayer = k == "you" || p.role.equals("PLAYER", ignoreCase = true)
            val role = if (wantsPlayer && players.size < MAX_PLAYERS) GameRole.PLAYER else GameRole.INFLUENCER
            val r = ref(name)
            idByName[k] = r
            if (role == GameRole.PLAYER) players += r
            ops += ChangeOp(
                OpType.ADD_STAKEHOLDER, ref = r, name = name, gameRole = role, canVeto = p.veto?.takeIf { it }, rationale = RATIONALE,
                goals = (clean(p.wants) ?: clean(p.goals))?.let { listOf(it.take(120)) },
                fears = clean(p.fears)?.let { listOf(it.take(120)) }
            )
        }

        // ── Options (players only) ────────────────────────────────────────────
        val optionIds = s.actions.associate { "${it.stakeholderId}|${key(it.label)}" to it.id }.toMutableMap()
        val optionCount = s.actions.groupingBy { it.stakeholderId }.eachCount().toMutableMap()
        // A fresh list of 2+ options for a player replaces that player's earlier AI-guessed options, so rewordings don't pile up.
        // Options the user confirmed or estimated are never touched.
        val freshLabels = sketch.options.mapNotNull { o -> idByName[key(o.owner)]?.let { it to key(o.label.trim()) } }.distinct().groupBy({ it.first }, { it.second })
        val youId = idByName["you"]
        // A move of "You" dropped here while even its best case was worse than today is remembered (see RuledOut).
        val ruledNow = mutableListOf<com.drkkr.strategos.domain.human.RuledOutMove>()
        for ((owner, labels) in freshLabels) {
            if (owner !in players || labels.size < 2) continue
            for (old in s.actions.filter { it.stakeholderId == owner && it.factStatus == com.drkkr.strategos.domain.model.FactStatus.AI_INFERRED && key(it.label) !in labels }) {
                if (humanFields && owner == youId) com.drkkr.strategos.domain.human.RuledOut.bestCase(s, owner, old.id)
                    ?.takeIf { com.drkkr.strategos.domain.human.RuledOut.isRuledOut(it) }
                    ?.let { ruledNow += com.drkkr.strategos.domain.human.RuledOutMove(old.label, it) }
                ops += ChangeOp(OpType.REMOVE_ACTION, targetId = old.id, rationale = RATIONALE)
                optionIds.remove("$owner|${key(old.label)}")
                optionCount[owner] = (optionCount[owner] ?: 1) - 1
            }
        }
        val tagsByLabel = SketchHuman.optionTags(sketch)
        for (o in sketch.options) {
            val owner = idByName[key(o.owner)] ?: continue
            val full = o.label.trim()
            // A label over the limit is cut at a word, not dropped (seen live, 7 Nov: a 121-character "not yet" option vanished, and the
            // engine picked the declaration because waiting was no longer on the list). Outcomes still find it: they match on "contains".
            val label = fit(full)
            if (owner !in players || label.isEmpty()) continue
            val k = "$owner|${key(label)}"
            // Near-duplicates ("X" and "X (e.g., details)") are the same option; short labels must match exactly.
            val nearDuplicate = optionIds.keys.any { other ->
                other.startsWith("$owner|") && other.substringAfter("|").let { o ->
                    val l = key(label)
                    minOf(o.length, l.length) >= NEAR_DUPLICATE_MIN && (o.contains(l) || l.contains(o))
                }
            }
            if (k in optionIds || nearDuplicate || (optionCount[owner] ?: 0) >= MAX_OPTIONS_PER_PLAYER) continue
            val r = ref("${o.owner}_$label")
            optionIds[k] = r
            optionCount[owner] = (optionCount[owner] ?: 0) + 1
            ops += ChangeOp(OpType.ADD_ACTION, ref = r, stakeholder = owner, label = label,
                description = (Postures.tag(o.posture) + (if (key(o.owner) == "you") DeliveryTags.tag(SketchHuman.guardTags(o.posture, label, (tagsByLabel[key(full)] ?: tagsByLabel[key(label)]).orEmpty())) else "")).ifEmpty { null },
                rationale = RATIONALE)
        }

        if (ruledNow.isNotEmpty()) {
            val i = ops.indexOfFirst { it.type == OpType.SET_SCENARIO_FIELDS }
            if (i >= 0) ops[i] = ops[i].let { f -> f.copy(scenarioFields = f.scenarioFields!!.copy(humanContext = (f.scenarioFields.humanContext ?: HumanContext()).copy(ruledOut = ruledNow))) }
            else ops.add(0, ChangeOp(OpType.SET_SCENARIO_FIELDS, rationale = RATIONALE, source = OpSource.AI_INFERRED,
                scenarioFields = ScenarioFieldsPatch(humanContext = HumanContext(ruledOut = ruledNow))))
        }
        // The ceiling a returning move keeps: a "You" option Call 1 marked as a ruled-out move again, with nothing changed since.
        val ruledAll = ruledNow + s.humanContext?.ruledOut.orEmpty()
        val ceilingByLabel = sketch.options.filter { key(it.owner) == "you" && clean(it.changed) == null }
            .mapNotNull { o -> com.drkkr.strategos.domain.human.RuledOut.find(ruledAll, clean(o.revisits))?.let { key(o.label.trim()) to it.best } }.toMap()

        // ── Limits ────────────────────────────────────────────────────────────
        val knownLimits = s.constraints.map { key(it.label) }.toMutableSet()
        for (l in sketch.limits) {
            val label = l.label.trim()
            if (label.isEmpty() || !knownLimits.add(key(label))) continue
            val kind = OpConstraintKind.entries.firstOrNull { it.name.equals(l.kind?.trim(), ignoreCase = true) } ?: OpConstraintKind.POLICY
            ops += ChangeOp(OpType.ADD_CONSTRAINT, ref = ref("limit_$label"), label = label.take(ChangeSetValidator.MAX_LABEL), constraintKind = kind, rationale = RATIONALE)
        }

        // ── Outcomes ──────────────────────────────────────────────────────────
        val inUse = s.payoffs.flatMap { it.payoff.values.keys }.distinct().sortedBy { it.ordinal }
        val dims = inUse.ifEmpty {
            sketch.dims.mapNotNull { d -> PayoffDimension.entries.firstOrNull { it.name.equals(d.trim(), ignoreCase = true) } }
                .distinct().takeIf { it.size in 3..6 } ?: DEFAULT_DIMS
        }
        val playerList = players.toList()
        val labelled = numbers(sketch.outcomes, dims)
        /** Exact (normalised) match first; otherwise the owner's one option whose wording contains, or is contained in, the pick. */
        fun optionFor(pid: String, label: String): String? {
            optionIds["$pid|$label"]?.let { return it }
            val close = optionIds.entries.filter { (k, _) -> k.startsWith("$pid|") }.filter { (k, _) ->
                val own = k.substringAfter("|")
                own.isNotEmpty() && label.isNotEmpty() && (own.contains(label) || label.contains(own))
            }
            return close.singleOrNull()?.value
        }
        val nameOf = idByName.entries.associate { (n, id) -> id to n }
        val seen = mutableSetOf<String>()
        val youKey = playerList.firstOrNull { nameOf[it] == "you" } ?: playerList.firstOrNull()
        val otherKey = playerList.firstOrNull { it != youKey }
        /** The outcome's picks as player name → option label, in the form the loop below matches on. */
        fun picksOf(o: SketchOutcome): Map<String, String> =
            if (o.whenPicks.size >= 2 && playerList.size >= 2) playerList.take(2).mapIndexed { i, pid -> nameOf.getValue(pid) to key(o.whenPicks[i]) }.toMap()
            else o.picks.entries.associate { key(it.key) to key(it.value) }
        val numericPicks = labelled.filter { it.numeric }.map { picksOf(it) }.toSet()
        val outcomes = if (youKey != null && otherKey != null) {
            val contrasted = PostureContrast.apply(labelled, sketch.options, nameOf.getValue(youKey), nameOf.getValue(otherKey), dims)
            // Phase F.6: the learned priors already live on `s` (OneShotTurn applies BayesianUpdater before calling toOps).
            val other = s.stakeholders.firstOrNull { it.id == otherKey }
            BayesianPayoffAdjustment.apply(
                contrasted, sketch.options, nameOf.getValue(youKey),
                other?.probSoftensOnRepair ?: BayesianUpdater.DEFAULT_SOFTENS, other?.probRespectsBoundary ?: BayesianUpdater.DEFAULT_BOUNDARY
            )
        } else labelled
        for (o in outcomes) {
            // The Argon form: when = [first player's option, second player's option], scores in the same player order.
            val byOrder = o.whenPicks.size >= 2 && playerList.size >= 2
            val picks = picksOf(o)
            val rationale = if (picks in numericPicks) "$RATIONALE · ${RubricPayoffs.AI_SCORED_NUMERIC}" else RATIONALE
            val profile = playerList.map { pid ->
                val label = picks[nameOf[pid]] ?: return@map null
                val aid = optionFor(pid, label) ?: return@map null
                ProfilePick(pid, aid)
            }
            if (profile.any { it == null }) continue
            val picksList = profile.filterNotNull()
            val scores = if (byOrder) mapOf(nameOf.getValue(playerList[0]) to o.forYou, nameOf.getValue(playerList[1]) to o.forThem)
                         else o.scores.entries.associate { key(it.key) to it.value }
            val ceiling = youKey?.let { y -> picks[nameOf[y]]?.let { l -> ceilingByLabel[l] ?: ceilingByLabel.entries.firstOrNull { (k, _) -> k.contains(l) || l.contains(k) }?.value } }
            for (pid in playerList) {
                val values = scores[nameOf[pid]] ?: continue
                if (values.size < dims.size) continue
                if (!seen.add("$pid|" + picksList.joinToString(",") { it.action })) continue
                ops += ChangeOp(
                    OpType.SET_PAYOFF, stakeholder = pid, profile = picksList, rationale = rationale,
                    values = dims.mapIndexed { i, d ->
                        val v = values[i].coerceIn(0.0, 10.0)
                        DimValue(d, if (ceiling != null && pid == youKey) com.drkkr.strategos.domain.human.RuledOut.cap(d, v, ceiling) else v)
                    }
                )
            }
        }

        // ── Power (Human Layer) ───────────────────────────────────────────────
        // Fill the counterpart's authority and influence only while both are still at their default 0, so a value the
        // user or an earlier turn set is never overwritten. A strong alternative means they can walk away.
        // BLUEPRINT-QUESTION: UPDATE_STAKEHOLDER stamps the whole stakeholder AI_INFERRED (applier), so this one op can downgrade
        // a stakeholder whose goals the user had confirmed. Same as every other update op today; left as is.
        if (otherKey != null) {
            val saved = s.stakeholders.firstOrNull { it.id == otherKey }
            val levels = when (humanCtx?.theirPower) { "they_higher" -> 8 to 5; "equal" -> 5 to 5; "you_higher" -> 3 to 5; else -> null }
            val fill = levels != null && (saved?.authority ?: 0) == 0 && (saved?.influence ?: 0) == 0
            // Only once "strong" is CONFIRMED (the same reading on two turns in a row), never from a single turn.
            val confirmedStrong = humanCtx != null && s.humanContext?.theirAlternative != "strong" &&
                (s.humanContext ?: HumanContext()).merge(humanCtx).theirAlternative == "strong"
            val canExit = confirmedStrong && saved?.canExit != true
            if (fill || canExit) ops += ChangeOp(
                OpType.UPDATE_STAKEHOLDER, targetId = otherKey, rationale = RATIONALE, source = OpSource.AI_INFERRED,
                authority = levels?.first?.takeIf { fill }, influence = levels?.second?.takeIf { fill }, canExit = true.takeIf { canExit }
            )
        }
        return SketchOps(ops, askOf(sketch), sketch.support == true)
    }
}

/** Stand-in names for the other person, replaced by a real name once the user gives one. */
object PlaceholderNames {
    private val STAND_INS = setOf("her", "him", "them", "she", "he", "they", "the other person", "other person", "the other side",
        "counterpart", "the counterpart", "my match", "the match", "match", "this person", "the person")

    fun isStandIn(name: String): Boolean = name.trim().lowercase().replace(Regex("\\s+"), " ") in STAND_INS

    /**
     * (stakeholder id, new name) when exactly one saved person (not "You") has a stand-in name, this sketch does not use that
     * stand-in, and it brings exactly one new name that is not a stand-in itself. Null otherwise.
     */
    fun rename(s: Scenario, sketchPeople: List<Pair<String, String?>>): Pair<String, String>? {
        fun k(n: String) = n.trim().lowercase().replace(Regex("\\s+"), " ")
        val standIns = s.stakeholders.filter { k(it.name) != "you" && isStandIn(it.name) }
        val only = standIns.singleOrNull() ?: return null
        val names = sketchPeople.map { it.first }.filter { it.isNotEmpty() }
        if (names.any { k(it) == k(only.name) }) return null
        val saved = s.stakeholders.map { k(it.name) }.toSet()
        val fresh = names.filter { k(it) != "you" && k(it) !in saved && !isStandIn(it) && it.length <= ChangeSetValidator.MAX_NAME }.distinctBy(::k)
        return fresh.singleOrNull()?.let { only.id to it }
    }
}
