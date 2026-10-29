package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder

data class IntakeGap(val id: String, val about: String? = null, val hint: String)

/** What the Guided interview still needs, in priority order (blueprint §17.2). The live context sends the first 5. */
object IntakeChecklist {
    /** Memory FACTs containing this tag mean the user said there are no limits. */
    const val LIMITS_NONE_TAG = "limits_none"

    // BLUEPRINT-QUESTION: §17.2 has gaps(s) only; [limitsAnswered] carries the memory check (FACT tagged limits_none).
    fun gaps(s: Scenario, limitsAnswered: Boolean = false): List<IntakeGap> = buildList {
        if (s.description.length < 30) add(IntakeGap("situation", hint = "what is going on, in their words"))
        if (s.stakes.isBlank()) add(IntakeGap("goal", hint = "what a good outcome looks like for them"))
        val self = self(s)
        if (self == null) add(IntakeGap("self", hint = "add the user as a person named You"))
        if (s.stakeholders.size < 2) add(IntakeGap("others", hint = "who else is involved or affected"))
        if (s.players().size < 2) add(IntakeGap("roles", hint = "whose choice matters most besides the user"))
        s.stakeholders.filter { it.authority == 0 && it.influence == 0 && !it.canVeto && !it.canDelay }.forEach {
            add(IntakeGap("power", it.name, "can they stop it or make it harder?"))
        }
        s.stakeholders.filter { it.goals.isEmpty() && it.fears.isEmpty() }.forEach {
            add(IntakeGap("wants_fears", it.name, "what they want and what worries them"))
        }
        if (self != null && s.actionsFor(self.id).size < 2) add(IntakeGap("my_options", hint = "what the user could do, including waiting"))
        s.players().filter { it.id != self?.id && s.actionsFor(it.id).size < 2 }.forEach {
            add(IntakeGap("their_options", it.name, "how they might respond"))
        }
        if (s.constraints.isEmpty() && !limitsAnswered) add(IntakeGap("limits", hint = "anything the user won't do or that must not happen"))
        val coverage = PayoffCoverage.of(s)
        if (coverage.expected == 0 || coverage.filled < coverage.expected) add(IntakeGap("outcomes", hint = "how each combination would feel"))
    }

    fun self(s: Scenario): Stakeholder? = s.stakeholders.firstOrNull {
        it.name.equals("You", ignoreCase = true) ||
            (s.decisionOwner.isNotBlank() && (it.name == s.decisionOwner || it.id == s.decisionOwner))
    }
}
