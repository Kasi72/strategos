package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Scenario

/**
 * LEAN PROFILE: the agent sees the first 8 characters of each UUID (a UUID costs ~20 tokens, 8 hex chars ~4).
 * Handlers expand a unique prefix back to the full id before validation.
 */
object ShortIds {
    const val LENGTH = 8
    private const val MIN_PREFIX = 6

    fun short(id: String): String = if (id.length > LENGTH && id[LENGTH] == '-') id.take(LENGTH) else id

    /** Returns the full id for [token]: itself if it exists, else the single id it is a prefix of, else [token] unchanged. */
    fun expand(token: String?, ids: Collection<String>): String? {
        if (token == null || token in ids || token.length < MIN_PREFIX) return token
        return ids.filter { it.startsWith(token) }.singleOrNull() ?: token
    }

    /** Expands short ids in every reference field. Refs defined in the batch are never expanded. */
    fun expandOps(ops: List<ChangeOp>, s: Scenario): List<ChangeOp> {
        val ids = s.stakeholders.map { it.id } + s.actions.map { it.id } + s.constraints.map { it.id }
        val refs = ops.mapNotNull { it.ref }.toSet()
        fun x(token: String?) = if (token != null && token in refs) token else expand(token, ids)
        return ops.map { op ->
            op.copy(
                targetId = x(op.targetId),
                stakeholder = x(op.stakeholder),
                profile = op.profile?.map { ProfilePick(x(it.stakeholder)!!, x(it.action)!!) }
            )
        }
    }
}
