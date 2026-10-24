package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    val limits: List<SketchLimit> = emptyList(),
    val ask: String? = null,
    val support: Boolean? = null
)

/**
 * Text that may arrive as a list. The saved model is shown to the model with wants and fears as lists, and it copies that
 * form back (seen live: the whole follow-up reply was rejected over `"fears":["…"]`). A list becomes one string.
 */
object TextOrListSerializer : JsonTransformingSerializer<String>(String.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) JsonPrimitive(element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { t -> t.isNotEmpty() } }.joinToString("; "))
        else element
}

@Serializable
data class SketchPerson(
    val name: String = "", val role: String? = null, val veto: Boolean? = null,
    @Serializable(with = TextOrListSerializer::class) val wants: String? = null,
    @Serializable(with = TextOrListSerializer::class) val fears: String? = null,
    // The saved-model digest calls wants "goals"; accept that spelling too.
    @Serializable(with = TextOrListSerializer::class) val goals: String? = null
)

@Serializable
data class SketchOption(@SerialName("for") val owner: String = "", val label: String = "")

@Serializable
data class SketchOutcome(val picks: Map<String, String> = emptyMap(), val scores: Map<String, List<Double>> = emptyMap())

@Serializable
data class SketchLimit(val label: String = "", val kind: String? = null)

/** The result of turning a sketch into ops. [ops] may be empty when the model only asked a question. */
data class SketchOps(val ops: List<ChangeOp>, val ask: String?, val support: Boolean)

object SketchParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Finds the first JSON object in [text] (models add prose or code fences) and decodes it. Null when it doesn't parse. */
    fun parse(text: String): ModelSketch? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString(ModelSketch.serializer(), text.substring(start, end + 1)) }.getOrNull()
    }
}

object SketchConverter {
    private val dashes = Regex("[\\u2010-\\u2015\\u2212]")
    private val spaces = Regex("\\s+")
    private val placeholders = setOf("short", "string", "text", "word", "...", "n/a", "none", "null", "unknown")

    /** Comparison form of a name or label: lowercase, one kind of hyphen, single spaces (models vary these). */
    internal fun norm(s: String): String = s.trim().lowercase().replace(dashes, "-").replace(spaces, " ")

    /** Text the model filled in, or null when it left a placeholder behind (seen live: "short"). */
    internal fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && norm(it) !in placeholders }

    private const val MAX_PLAYERS = ChangeSetValidator.MAX_PLAYERS
    private const val MAX_OPTIONS_PER_PLAYER = 4
    private const val NEAR_DUPLICATE_MIN = 8
    private const val RATIONALE = "From your description"
    private val DEFAULT_DIMS = listOf(PayoffDimension.WELLBEING, PayoffDimension.RELATIONSHIPS, PayoffDimension.COST)
    private val nonRef = Regex("[^a-z0-9_]+")

    /** The one clarifying question, unless the model echoed the prompt's instruction instead of asking (seen live). */
    private fun askOf(sketch: ModelSketch): String? =
        clean(sketch.ask)?.takeUnless { norm(it).startsWith("only if") || norm(it).contains("too thin") }

    fun toOps(sketch: ModelSketch, s: Scenario): SketchOps {
        // A reply that only asks a question (or flags support) saves nothing.
        val hasContent = sketch.people.isNotEmpty() || sketch.options.isNotEmpty() || sketch.limits.isNotEmpty() ||
            sketch.outcomes.isNotEmpty() || !sketch.goal.isNullOrBlank()
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
        val existingNames = idByName.keys.toSet()

        fun youExists() = s.stakeholders.any { key(it.name) == "you" } || sketch.people.any { key(it.name) == "you" }
        val people = if (youExists()) sketch.people else listOf(SketchPerson("You", "PLAYER")) + sketch.people
        val fields = ChangeOp(
            OpType.SET_SCENARIO_FIELDS, rationale = RATIONALE,
            scenarioFields = ScenarioFieldsPatch(
                decisionOwner = if (s.decisionOwner.isBlank()) "You" else null,
                stakes = sketch.goal?.takeIf { s.stakes.isBlank() && it.isNotBlank() }?.take(200)
            )
        )
        if (fields.scenarioFields!!.decisionOwner != null || fields.scenarioFields.stakes != null) ops += fields

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
        for (o in sketch.options) {
            val owner = idByName[key(o.owner)] ?: continue
            val label = o.label.trim()
            if (owner !in players || label.isEmpty() || label.length > ChangeSetValidator.MAX_LABEL) continue
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
            ops += ChangeOp(OpType.ADD_ACTION, ref = r, stakeholder = owner, label = label, rationale = RATIONALE)
        }

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
        for (o in sketch.outcomes) {
            val picks = o.picks.entries.associate { key(it.key) to key(it.value) }
            val profile = playerList.map { pid ->
                val label = picks[nameOf[pid]] ?: return@map null
                val aid = optionFor(pid, label) ?: return@map null
                ProfilePick(pid, aid)
            }
            if (profile.any { it == null }) continue
            val picksList = profile.filterNotNull()
            val scores = o.scores.entries.associate { key(it.key) to it.value }
            for (pid in playerList) {
                val values = scores[nameOf[pid]] ?: continue
                if (values.size < dims.size) continue
                if (!seen.add("$pid|" + picksList.joinToString(",") { it.action })) continue
                ops += ChangeOp(
                    OpType.SET_PAYOFF, stakeholder = pid, profile = picksList, rationale = RATIONALE,
                    values = dims.mapIndexed { i, d -> DimValue(d, values[i].coerceIn(0.0, 10.0)) }
                )
            }
        }
        return SketchOps(ops, askOf(sketch), sketch.support == true)
    }
}
