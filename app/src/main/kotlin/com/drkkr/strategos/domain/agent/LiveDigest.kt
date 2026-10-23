package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.framework.ModelIssue
import com.drkkr.strategos.domain.framework.ModelValidator
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * LEAN PROFILE: the compact model summary sent in every live context (instead of [ScenarioDigest]).
 * Short ids, false/empty fields left out, at most 12 people, 24 options, 10 limits and 3 missing combinations.
 */
object LiveDigest {
    private const val MAX_PEOPLE = 12
    private const val MAX_ACTIONS = 24
    private const val MAX_CONSTRAINTS = 10
    private const val MAX_MISSING = 3

    /** [forSketch]: the one-shot flow addresses people by name, so ids are left out and options say who they belong to. */
    fun build(s: Scenario, forSketch: Boolean = false): JsonObject = buildJsonObject {
        val id = ShortIds::short
        val nameOf = s.stakeholders.associate { it.id to it.name }
        put("title", s.title.take(80))
        if (s.description.isNotBlank()) put("situation", s.description.take(300))
        if (s.decisionOwner.isNotBlank()) put("owner", s.decisionOwner)
        if (s.stakes.isNotBlank()) put("stakes", s.stakes.take(150))
        val players = s.players().map { it.id }.toSet()
        putJsonArray("people") {
            s.stakeholders.take(MAX_PEOPLE).forEach { p ->
                addJsonObject {
                    if (!forSketch) put("id", id(p.id))
                    put("name", p.name)
                    put("role", if (p.id in players) "PLAYER" else "INFLUENCER")
                    if (p.canVeto) put("veto", true)
                    if (p.canDelay) put("delay", true)
                    if (p.authority > 0) put("authority", p.authority)
                    if (p.influence > 0) put("influence", p.influence)
                    if (p.goals.isNotEmpty()) putJsonArray("goals") { p.goals.take(2).forEach { add(it.take(80)) } }
                    if (p.fears.isNotEmpty()) putJsonArray("fears") { p.fears.take(2).forEach { add(it.take(80)) } }
                    if (p.factStatus == FactStatus.AI_INFERRED) put("ai", true)
                }
            }
        }
        putJsonArray("options") {
            s.actions.take(MAX_ACTIONS).forEach { a ->
                addJsonObject {
                    if (forSketch) put("for", nameOf[a.stakeholderId].orEmpty())
                    else { put("id", id(a.id)); put("by", id(a.stakeholderId)) }
                    put("label", a.label.take(60))
                }
            }
        }
        val coverage = PayoffCoverage.of(s)
        putJsonObject("outcomes") {
            putJsonArray("dims") { coverage.dimensions.forEach { add(it.name) } }
            put("filled", coverage.filled); put("of", coverage.expected)
            if (!forSketch && coverage.missingProfiles.isNotEmpty()) putJsonArray("missing") {
                coverage.missingProfiles.take(MAX_MISSING).forEach { profile -> addJsonArray { profile.values.forEach { add(id(it)) } } }
            }
        }
        if (s.constraints.isNotEmpty()) putJsonArray("limits") {
            s.constraints.take(MAX_CONSTRAINTS).forEach { c ->
                addJsonObject { if (!forSketch) put("id", id(c.id)); put("label", c.label.take(60)); put("kind", c.kind.name) }
            }
        }
        if (s.beliefs.isNotEmpty()) put("beliefs", s.beliefs.size)
        val errors = ModelValidator.validate(s).issues.filter { it.severity == ModelIssue.Severity.ERROR }
        if (errors.isNotEmpty()) putJsonArray("problems") { errors.take(3).forEach { add(it.message.take(120)) } }
    }
}
