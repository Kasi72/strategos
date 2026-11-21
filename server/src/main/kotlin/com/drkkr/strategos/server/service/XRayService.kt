package com.drkkr.strategos.server.service

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.PatternCatalog
import com.drkkr.strategos.domain.agent.PriorId
import com.drkkr.strategos.domain.agent.ProjectBrain
import com.drkkr.strategos.domain.agent.XRayArenaBuilder
import com.drkkr.strategos.domain.agent.XRayGridBuilder
import com.drkkr.strategos.domain.agent.XRayNotEnough
import com.drkkr.strategos.domain.agent.XRayPriors
import com.drkkr.strategos.domain.human.HumanLens
import com.drkkr.strategos.server.persistence.ProjectRepository
import com.drkkr.strategos.server.persistence.ThreadRepository
import com.drkkr.strategos.server.persistence.TurnRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable data class XRayRowDto(val label: String, val well: Int, val bad: Int, val expected: Int, val kind: String)
@Serializable data class ArenaLineDto(val label: String, val value: String, val inferred: Boolean)
@Serializable data class ArenaPersonDto(val name: String, val role: String, val lines: List<ArenaLineDto>)
@Serializable data class ArenaInfluencerDto(val name: String, val role: String, val relation: String, val sway: String, val inferred: Boolean)
@Serializable data class ArenaDto(val you: ArenaPersonDto, val them: ArenaPersonDto, val influencer: ArenaInfluencerDto?, val trust: Int?, val tension: Int?)
@Serializable data class PatternDto(val name: String, val summary: String, val wikiUrl: String)
@Serializable data class LensDto(val line: String, val topic: String, val url: String)

/**
 * The X-ray sheet's data (board 07), built exactly as the app's XRayViewModel builds it: the grid from the saved payoffs
 * pinned to the engine's own move, the flip points, the arena, the pattern and the human lens. The slider is the client's:
 * it re-blends `well`/`bad` locally ([XRayGrid.at] is two lines), so dragging never calls the server.
 */
@Serializable
data class XRayPayload(
    val ready: Boolean,
    val notEnough: String? = null,
    val rows: List<XRayRowDto> = emptyList(),
    val bestIndex: Int = 0,
    val robust: Boolean = false,
    val wellLabel: String = "",
    val badLabel: String = "",
    val coachOdds: Double = XRayPriors.COACH_DEFAULT,
    val oddsLabel: String = "",
    val flips: List<Int> = emptyList(),
    val engineMove: String? = null,
    val engineRobust: Boolean? = null,
    val arena: ArenaDto? = null,
    val pattern: PatternDto? = null,
    val humanLens: LensDto? = null,
    val modelStrip: String = "",
)

class XRayService(private val projects: ProjectRepository, private val threads: ThreadRepository, private val turns: TurnRepository) {

    fun of(projectId: String): XRayPayload? {
        val p = projects.get(projectId) ?: return null
        val s = p.scenario
        // The engine's last verdict on this project: the move the chat recommended, so the sheet never disagrees with it.
        val last = threads.list(projectId).flatMap { turns.last(it.id, 1) }.filter { it.engineDirectiveJson != null }.maxByOrNull { it.timestamp }
        val directive = last?.engineDirectiveJson?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val engineMove = directive?.get("move_to_implement")?.jsonPrimitive?.contentOrNull
        val engineRobust = directive?.get("stress_verdict")?.jsonPrimitive?.contentOrNull?.let { it == "ROBUST" }
        val archetype = last?.extractedSketchJson?.let { runCatching { Json.parseToJsonElement(it).jsonObject["archetype"]?.jsonPrimitive?.contentOrNull }.getOrNull() }

        val prior = XRayPriors.of(s).firstOrNull { it.id == PriorId.TAKES_LIMIT_WELL }
        val coach = prior?.value ?: XRayPriors.COACH_DEFAULT
        val grid = XRayGridBuilder.build(s, coach, engineMove)
            ?: return XRayPayload(ready = false, notEnough = XRayNotEnough.from(true, false)?.question)
        val best = grid.best.label
        val lens = IntakeChecklist.self(s)?.let { o -> HumanLens.of(s.humanContext, s.actionsFor(o.id).firstOrNull { it.label.equals(best, ignoreCase = true) }) }
        val counts = runCatching { ProjectBrain.from(s).counts }.getOrNull()
        val arena = XRayArenaBuilder.from(s, p.trust, p.tension)
        return XRayPayload(
            ready = true,
            rows = grid.rows.map { XRayRowDto(it.label, it.well, it.bad, it.expected, it.kind.name) },
            bestIndex = grid.bestIndex, robust = grid.robust, wellLabel = grid.wellLabel, badLabel = grid.badLabel,
            coachOdds = coach, oddsLabel = prior?.label ?: "It goes well", flips = grid.flipPoints(),
            engineMove = engineMove, engineRobust = engineRobust,
            arena = arena?.let { a ->
                fun person(x: com.drkkr.strategos.domain.agent.ArenaPerson) = ArenaPersonDto(x.name, x.role, x.lines.map { ArenaLineDto(it.label, it.value, it.inferred) })
                ArenaDto(person(a.you), person(a.them), a.influencer?.let { ArenaInfluencerDto(it.name, it.role, it.relation, it.sway, it.inferred) }, a.trust, a.tension)
            },
            pattern = PatternCatalog.of(archetype, s)?.let { PatternDto(it.name, it.summary, it.wikiUrl) },
            humanLens = lens?.let { LensDto(it.line, it.topic, it.url) },
            modelStrip = counts?.let { "${it.people} people · ${it.options} options · ${it.outcomesFilled}/${it.outcomesExpected} outcomes" }.orEmpty(),
        )
    }

    /** "Keep as my estimate": the dragged odds become the user's own prior (the app's XRayRepository.keepAsMine). */
    fun keep(projectId: String, odds: Double): XRayPayload? {
        val p = projects.get(projectId) ?: return null
        projects.saveScenario(projectId, XRayPriors.withPriors(p.scenario, mapOf(PriorId.TAKES_LIMIT_WELL to odds), markUser = true))
        return of(projectId)
    }
}
