package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.HumanVocab

/** Reads the Human Layer fields of a [ModelSketch] through the closed vocabularies. Nothing out of vocabulary gets through. */
object SketchHuman {

    /** The context this one message adds, or null when the model said nothing usable about the human side. */
    fun context(sk: ModelSketch): HumanContext? {
        val p = sk.power
        val your = HumanVocab.clean(sk.yourState, HumanVocab.YOUR_STATE)
        val their = HumanVocab.clean(sk.theirState, HumanVocab.THEIR_STATE)?.takeIf { it != "unknown" }
        val c = HumanContext(
            relationship = HumanVocab.clean(sk.relationship, HumanVocab.RELATIONSHIP),
            theirPower = HumanVocab.clean(p?.relative, HumanVocab.POWER_RELATIVE),
            theyDependOnYou = HumanVocab.clean(p?.theyDependOnYou, HumanVocab.DEPENDENCE),
            youDependOnThem = HumanVocab.clean(p?.youDependOnThem, HumanVocab.DEPENDENCE),
            yourAlternative = HumanVocab.clean(p?.yourAlternative, HumanVocab.ALTERNATIVE),
            theirAlternative = HumanVocab.clean(p?.theirAlternative, HumanVocab.ALTERNATIVE),
            setting = HumanVocab.clean(p?.setting, HumanVocab.SETTING),
            statusQuo = SketchConverter.clean(sk.statusQuo)?.take(HumanContext.STATUS_QUO_MAX),
            statusQuoForThem = HumanVocab.clean(sk.statusQuoForThem, HumanVocab.STATUS_QUO_FOR_THEM),
            deadline = HumanVocab.clean(sk.deadline, HumanVocab.DEADLINE),
            theirVulnerable = HumanVocab.clean(sk.theirVulnerable, HumanVocab.VULNERABLE),
            yourStateLevel = HumanVocab.stateLevel(your), theirStateLevel = HumanVocab.stateLevel(their),
            yourStateRaw = your, theirStateRaw = their,
            theirTactics = sk.theirTactics.mapNotNull { HumanVocab.clean(it, HumanVocab.THEIR_TACTICS) }.filter { it != "none" }.distinct().take(HumanContext.TACTICS_MAX),
            stepsForThemToSayYes = sk.stepsForThemToSayYes?.content?.trim()?.toIntOrNull()?.takeIf { it in 1..9 },
            goalIs = HumanVocab.clean(sk.goalIs, HumanVocab.GOAL_IS)
        )
        return c.takeIf { it != HumanContext() }
    }

    /** Delivery tags per option, keyed by the normalised option label ([SketchConverter.norm]). Unknown tags are dropped. */
    fun optionTags(sk: ModelSketch): Map<String, Set<String>> =
        sk.optionTags.entries.mapNotNull { (label, tags) ->
            val clean = tags.mapNotNull { HumanVocab.clean(it, HumanVocab.OPTION_TAGS) }.toSet()
            clean.takeIf { it.isNotEmpty() }?.let { SketchConverter.norm(label) to it }
        }.toMap()
}
