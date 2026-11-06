package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.CrossProjectPeople
import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import com.drkkr.strategos.domain.game.CompileResult
import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.game.GameModelCompiler
import com.drkkr.strategos.domain.human.ManipulationRadar
import com.drkkr.strategos.domain.human.TacticCatalog
import com.drkkr.strategos.domain.human.TacticId
import com.drkkr.strategos.domain.human.TacticLearning
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.ScenarioStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What one project looks like to the matcher (BLUEPRINT §6.2). Built from saved data only; nothing here is saved. */
data class ProjectFeatures(
    val id: String,
    val title: String,
    /** The other people in it (never the user), as CrossProjectPeople compares them: by name, ignoring case. */
    val people: List<String>,
    val relationship: String?,
    /** The game's archetype name, when it is a named one (never GENERIC). */
    val archetype: String?,
    /** Radar tags seen on at least two turns ([com.drkkr.strategos.domain.human.HumanContext.confirmedTactics]). */
    val confirmedTags: Set<String>,
    val archived: Boolean,
    val updatedAt: Long,
    /** From a recorded outcome or a learned tactic prior only; null without a record (never guessed). */
    val whatWorked: String?
)

enum class MatchStrength { STRONG, MEDIUM }

/** The `cross_project` block: the other project's title and a fixed template; [whatWorked] only from a record. */
data class CrossProjectInsight(val projectId: String, val title: String, val shared: String, val whatWorked: String?, val strength: MatchStrength) {
    fun toJson(): JsonObject = buildJsonObject {
        put("title", title); put("shared", shared); whatWorked?.let { put("what_worked", it) }
    }
}

/**
 * ID4.2: another project of the user's that shares something with this one (BLUEPRINT §6.2). Pure; the agent layer loads the
 * projects and the shown-once keys.
 * - STRONG: the same person (a name both projects have, as [CrossProjectPeople] matches people).
 * - MEDIUM: the same named archetype and the same confirmed radar tag.
 * Archived projects never match; deleted ones are not in the list. Strong matches first, then the most recently updated.
 */
object CrossProjectMatcher {
    const val SAME_PERSON = "the same person (%s)"
    const val SAME_PATTERN = "the same kind of situation, and the same sign: %s"
    const val OUTCOME_WORKED = "what you did there went well: %s"
    const val TACTIC_WORKED = "this worked with them before: %s"
    /** An outcome at or above this rating (1–5) counts as having gone well. */
    const val GOOD_RATING = 4

    /** The fixed sentence for the insight (ID4.3: used when the coach's reply leaves it out). */
    const val SENTENCE = "Like in “%s”, this involves %s."
    const val WORKED_SENTENCE = " Back then, %s."

    fun sentence(i: CrossProjectInsight): String =
        SENTENCE.format(i.title, i.shared) + (i.whatWorked?.let { WORKED_SENTENCE.format(it) } ?: "")

    /**
     * [whatHappened] with the insight as its second sentence when it doesn't already name the project's title (the coach is
     * asked to by rule 32; live it sometimes doesn't). Only the first sentence is kept before it, so the phone view shows it.
     */
    fun ensureIn(whatHappened: String, i: CrossProjectInsight): String {
        if (whatHappened.contains(i.title, ignoreCase = true)) return whatHappened
        val first = Regex("(?<=[.!?])\\s+").split(whatHappened.trim()).firstOrNull().orEmpty()
        return listOf(first, sentence(i)).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** The shown-once key for this conversation and matched project. */
    fun shownKey(conversationId: String, projectId: String) = "xproj_shown:$conversationId:$projectId"

    fun features(s: Scenario, outcomes: List<OutcomeRecord> = emptyList()): ProjectFeatures {
        val self = IntakeChecklist.self(s)?.id
        val archetype = runCatching { (GameModelCompiler.compile(s) as? CompileResult.Success)?.ir?.archetype }.getOrNull()
            ?.takeIf { it != GameArchetype.GENERIC }?.name
        return ProjectFeatures(
            id = s.id, title = s.title,
            people = s.stakeholders.filter { it.id != self && !it.name.equals("You", ignoreCase = true) && it.name.isNotBlank() }.map { it.name },
            relationship = s.humanContext?.relationship,
            archetype = archetype,
            confirmedTags = s.humanContext?.confirmedTactics().orEmpty().filter { ManipulationRadar.describe(it) != null }.toSet(),
            archived = s.status == ScenarioStatus.ARCHIVED,
            updatedAt = s.updatedAt,
            whatWorked = whatWorked(s, outcomes)
        )
    }

    /** A recorded outcome that went well (the latest), else the benign tactic with the best learned record (more yes than no). */
    private fun whatWorked(s: Scenario, outcomes: List<OutcomeRecord>): String? {
        outcomes.filter { it.scenarioId == s.id && it.outcomeRating >= GOOD_RATING && it.actualMove.isNotBlank() }
            .maxByOrNull { it.recordedAt }?.let { return OUTCOME_WORKED.format(it.actualMove) }
        val success = s.humanContext?.tacticSuccess.orEmpty()
        val best = success.keys.mapNotNull { id -> runCatching { TacticId.valueOf(id) }.getOrNull()?.let { it to TacticLearning.mean(success, id) } }
            .filter { it.second > 0.5 }.maxByOrNull { it.second }?.first ?: return null
        return TacticCatalog.offered.firstOrNull { it.id == best }?.let { TACTIC_WORKED.format(it.coachHint) }
    }

    fun match(current: ProjectFeatures, others: List<ProjectFeatures>, alreadyShown: Set<String> = emptySet()): CrossProjectInsight? {
        val candidates = others.filter { it.id != current.id && !it.archived && it.id !in alreadyShown }.sortedByDescending { it.updatedAt }
        for (o in candidates) {
            val person = current.people.firstOrNull { p -> o.people.any { CrossProjectPeople.samePerson(it, p) } } ?: continue
            return CrossProjectInsight(o.id, o.title, SAME_PERSON.format(person), o.whatWorked, MatchStrength.STRONG)
        }
        if (current.archetype == null) return null
        for (o in candidates) {
            if (o.archetype != current.archetype) continue
            val tag = current.confirmedTags.firstOrNull { it in o.confirmedTags } ?: continue
            val sign = ManipulationRadar.describe(tag)?.sign ?: continue
            return CrossProjectInsight(o.id, o.title, SAME_PATTERN.format(sign), o.whatWorked, MatchStrength.MEDIUM)
        }
        return null
    }
}
