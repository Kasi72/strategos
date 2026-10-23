package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.framework.ModelValidator
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToLong

/** Compact model digest for `get_scenario` (blueprint §8.12). */
object ScenarioDigest {
    const val MAX_STAKEHOLDERS = 12
    const val MAX_ACTIONS = 40
    const val MAX_CONSTRAINTS = 20
    const val MAX_MISSING_PROFILES = 10

    fun build(s: Scenario): JsonObject {
        val stakeholders = s.stakeholders.take(MAX_STAKEHOLDERS)
        val actions = s.actions.take(MAX_ACTIONS)
        val constraints = s.constraints.take(MAX_CONSTRAINTS)
        val truncated = s.stakeholders.size > MAX_STAKEHOLDERS || s.actions.size > MAX_ACTIONS || s.constraints.size > MAX_CONSTRAINTS
        val (players, influencers) = stakeholders.partition { it in s.players() }
        val coverage = PayoffCoverage.of(s)

        return buildJsonObject {
            put("scenario_id", s.id)
            put("version", s.version)
            put("title", s.title)
            put("description", s.description.take(600))
            put("decision_owner", s.decisionOwner)
            put("stakes", s.stakes.take(300))
            put("time_horizon", s.timeHorizonLabel)
            put("decision_rule", s.decisionRule.name)
            put("lifecycle_stage", s.lifecycleStage.name)
            put("workflow_stage", s.lifecycleStage.toWorkflowStage().name)
            put("completeness", s.completenessScore())
            put("players", JsonArray(players.map(::stakeholder)))
            put("influencers", JsonArray(influencers.map(::stakeholder)))
            putJsonArray("actions") {
                actions.forEach { a ->
                    addJsonObject {
                        put("id", a.id); put("stakeholder_id", a.stakeholderId); put("label", a.label)
                        put("legal_status", a.legalStatus.name); put("reversible", a.reversible)
                        put("difficulty", a.implementationDifficulty); put("status", a.factStatus.name)
                    }
                }
            }
            putJsonObject("payoffs") {
                putJsonArray("dimensions_in_use") { coverage.dimensions.forEach { add(it.name) } }
                put("filled", coverage.filled)
                put("expected", coverage.expected)
                put("ai_inferred", s.payoffs.count { it.factStatus == FactStatus.AI_INFERRED })
                putJsonArray("missing_profiles") {
                    coverage.missingProfiles.take(MAX_MISSING_PROFILES).forEach { profile ->
                        add(buildJsonArray {
                            profile.forEach { (sid, aid) -> addJsonObject { put("stakeholder", sid); put("action", aid) } }
                        })
                    }
                }
            }
            putJsonArray("constraints") {
                constraints.forEach { c ->
                    addJsonObject { put("id", c.id); put("label", c.label); put("kind", c.kind.name); put("status", c.factStatus.name) }
                }
            }
            putJsonArray("beliefs") {
                s.beliefs.forEach { b ->
                    addJsonObject { put("label", b.label); put("probability", round2(b.probability)); put("status", b.status.name) }
                }
            }
            put("evidence_count", s.evidenceSources.size)
            putJsonArray("validity_issues") {
                ModelValidator.validate(s).issues.forEach { i ->
                    addJsonObject { put("severity", i.severity.name); put("field", i.field); put("message", i.message) }
                }
            }
            put("truncated", truncated)
        }
    }

    private fun stakeholder(st: Stakeholder) = buildJsonObject {
        put("id", st.id); put("name", st.name); put("type", st.type.name)
        put("authority", st.authority); put("influence", st.influence); put("credibility", st.credibility)
        put("can_veto", st.canVeto); put("can_exit", st.canExit); put("can_delay", st.canDelay)
        putJsonArray("goals") { st.goals.take(3).forEach { add(it) } }
        putJsonArray("fears") { st.fears.take(3).forEach { add(it) } }
        put("status", st.factStatus.name)
    }

    internal fun round2(v: Double): JsonPrimitive = JsonPrimitive((v * 100).roundToLong() / 100.0)
}

/** Payoff coverage over PLAYER profiles: `expected` = profiles x players. */
data class PayoffCoverage(
    val dimensions: List<PayoffDimension>,
    val filled: Int,
    val expected: Int,
    val missingProfiles: List<Map<String, String>>
) {
    companion object {
        fun of(s: Scenario): PayoffCoverage {
            val players = s.players().map { it.id }
            val profiles = profiles(players.map { p -> s.actionsFor(p).map { p to it.id } })
            val playerSet = players.toSet()
            val keys = s.payoffs.filter { it.stakeholderId in playerSet }.map { it.stakeholderId to it.actionProfile }.toSet()
            var filled = 0
            val missing = mutableListOf<Map<String, String>>()
            for (profile in profiles) {
                val have = players.count { (it to profile) in keys }
                filled += have
                if (have < players.size) missing += profile
            }
            val dims = s.payoffs.flatMap { it.payoff.values.keys }.distinct().sortedBy { it.ordinal }
            return PayoffCoverage(dims, filled, profiles.size * players.size, missing)
        }

        /** Cartesian product, in player then action order. Empty when any player has no actions. */
        private fun profiles(choices: List<List<Pair<String, String>>>): List<Map<String, String>> {
            if (choices.isEmpty() || choices.any { it.isEmpty() }) return emptyList()
            return choices.fold(listOf(emptyMap())) { acc, options ->
                acc.flatMap { partial -> options.map { (p, a) -> partial + (p to a) } }
            }
        }
    }
}
