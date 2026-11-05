package com.drkkr.strategos.domain.human

import com.drkkr.strategos.domain.model.Action

/**
 * How a move of "You" would be carried out, kept as an `[f:tag,tag]` marker in [Action.description], the same way
 * `[posture:x]` is (see `Postures`). Tags come from [HumanVocab.OPTION_TAGS]. No schema change.
 */
object DeliveryTags {
    private val marker = Regex("\\[f:([a-z_,]*)]")

    /** The tags on [a], in-vocabulary only. */
    fun of(a: Action): Set<String> =
        marker.find(a.description)?.groupValues?.get(1)?.split(',')?.filter { it in HumanVocab.OPTION_TAGS || it in HumanVocab.PLANNER_TAGS }?.toSet().orEmpty()

    /** `[f:a,b]` (sorted, so it is stable), or "" when there is nothing to tag. */
    fun tag(tags: Collection<String>): String =
        tags.filter { it in HumanVocab.OPTION_TAGS || it in HumanVocab.PLANNER_TAGS }.toSortedSet().takeIf { it.isNotEmpty() }?.let { "[f:${it.joinToString(",")}]" }.orEmpty()

    /** An in-memory copy of [a] with [add] turned on and [remove] turned off. Used by the planner; never saved. */
    fun with(a: Action, add: Set<String>, remove: Set<String>): Action {
        val next = tag((of(a) + add) - remove)
        val d = a.description
        val updated = when {
            marker.containsMatchIn(d) -> marker.replace(d, next.replace("\\", "\\\\"))
            next.isEmpty() -> d
            else -> d + next
        }
        return a.copy(description = updated)
    }
}
