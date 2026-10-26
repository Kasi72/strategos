package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario

/** One plain-English variable in a pattern's "What matters in your case" list. */
data class PatternVariable(val label: String, val value: String)

/** The learnable pattern for a project: a plain name, a short explainer, the case specifics, and a link. */
data class Pattern(
    val archetype: String,
    val name: String,
    val summary: String,
    val variables: List<PatternVariable>,
    val wikiUrl: String
)

/**
 * Turns the engine's behavioral-module id into something a person can learn from: a plain name, a
 * three-sentence explainer in the coach's voice, the variables that matter filled from their own
 * scenario, and a "read more" Wikipedia link. Shared by the Overview pattern card and the X-ray's
 * "Why this works" so the two never say different things. Pure; no android, no persistence.
 */
object PatternCatalog {

    private data class Entry(val name: String, val summary: String, val wikiUrl: String)

    private const val WIKI = "https://en.wikipedia.org/wiki/"
    private const val GENERIC = "pure-nash"

    // Keyed by the behavioral module id (EngineDirective.dynamicArchetype).
    private val ENTRIES: Map<String, Entry> = mapOf(
        "prisoners-dilemma" to Entry(
            "Trust trap",
            "You both gain if this works, but it only works if each of you trusts the other to carry their share. " +
                "Being wrong is costly, so the careful choice starts to look wiser than the generous one. " +
                "That pull toward playing safe is the trap — for both of you.",
            WIKI + "Prisoner%27s_dilemma"
        ),
        "stag-hunt" to Entry(
            "Mutual trust needed",
            "You can both reach the better outcome, but only by going for it together. " +
                "If either of you plays it safe, the safe move wins and the bigger prize is lost. " +
                "The whole thing turns on whether each of you believes the other will commit.",
            WIKI + "Stag_hunt"
        ),
        "chicken-game" to Entry(
            "Standoff over who gives way",
            "Neither of you wants to be the one who backs down. " +
                "If you both hold firm you both get hurt; if one yields, the other comes out ahead. " +
                "The risk is that each of you is betting the other will blink first.",
            WIKI + "Chicken_(game)"
        ),
        "relational-repair" to Entry(
            "Relationship repair",
            "The bond matters more than winning this one exchange. " +
                "A small, genuine gesture can reset the tone, but only if it reads as sincere. " +
                "Leaving the distance too long lets it harden.",
            WIKI + "Reciprocity_(social_psychology)"
        ),
        "stakeholder-trust-dynamics" to Entry(
            "Trust building over time",
            "Trust here is built slowly and spent quickly. " +
                "Each promise you keep raises what you can ask for next time; each one you break costs more than it gained. " +
                "Playing the long game beats winning any single round.",
            WIKI + "Repeated_game"
        ),
        "ultimatum" to Entry(
            "Take it or leave it",
            "One of you sets the terms and the other can only accept or walk away. " +
                "An offer that feels unfair often gets refused even when refusing costs the refuser. " +
                "So the split that looks fair usually does better than the greedy one.",
            WIKI + "Ultimatum_game"
        ),
        GENERIC to Entry(
            "A stable pattern",
            "There is a steady balance here that neither side has much reason to break on their own. " +
                "Each of you is doing the best you can given what the other is doing. " +
                "Changing the outcome usually means changing what's at stake, not just trying harder.",
            WIKI + "Nash_equilibrium"
        )
    )

    /** Trajectory stores archetypes as UPPER_SNAKE (e.g. CHICKEN_GAME); fold those onto the module ids. */
    private fun normalize(archetype: String): String =
        if (archetype in ENTRIES) archetype else archetype.trim().lowercase().replace('_', '-')

    /** The pattern for an archetype id, with variables filled from [scenario] when given. Null for an unknown/absent id. */
    fun of(archetype: String?, scenario: Scenario? = null): Pattern? {
        val key = archetype?.let(::normalize) ?: return null
        val e = ENTRIES[key] ?: return null
        return Pattern(key, e.name, e.summary, scenario?.let(::variables).orEmpty(), e.wikiUrl)
    }

    /** Convenience: read the directive's top archetype. */
    fun forDirective(directive: EngineDirective, scenario: Scenario? = null): Pattern? =
        of(directive.dynamicArchetype, scenario)

    /**
     * The handful of plain-English specifics the card shows: the user's limit, the other person's
     * main need, any third party who can sway it, and the doubt driving the tension. All optional —
     * only what the scenario actually records is shown.
     */
    fun variables(s: Scenario): List<PatternVariable> {
        val self = IntakeChecklist.self(s)
        val other = s.players().firstOrNull { it.id != self?.id }
        return buildList {
            (s.constraints.firstOrNull { it.kind == Constraint.Kind.USER_HARD } ?: s.constraints.firstOrNull())
                ?.label?.takeIf { it.isNotBlank() }?.let { add(PatternVariable("Your limit", it)) }
            other?.goals?.firstOrNull { it.isNotBlank() }?.let { add(PatternVariable("${possessive(other.name)} need", it)) }
            s.stakeholders.firstOrNull { it.gameRole == GameRole.INFLUENCER }
                ?.let { add(PatternVariable(it.name, it.role.ifBlank { "can tip it either way" })) }
            other?.fears?.firstOrNull { it.isNotBlank() }?.let { add(PatternVariable("The doubt", it)) }
        }
    }

    private fun possessive(name: String): String = when {
        name.equals("You", ignoreCase = true) -> "Your"
        name.endsWith("s", ignoreCase = true) -> "$name'"
        else -> "$name's"
    }
}
