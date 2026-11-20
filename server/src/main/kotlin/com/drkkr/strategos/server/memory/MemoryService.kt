package com.drkkr.strategos.server.memory

import kotlinx.serialization.Serializable
import java.time.Instant

/** Namespaces (Graphiti `group_id`), one per project and one per person's journal, so nothing leaks across (SDD §3.2). */
object MemoryGroups {
    fun project(projectId: String) = "strategos_proj_$projectId"
    fun journal(userId: String) = "strategos_journal_$userId"
}

/** A structured fact carried by an episode: who, about what, and what is true now. A newer fact on the same subject+predicate supersedes it. */
@Serializable
data class FactInput(val subject: String, val predicate: String, val statement: String)

/** One timestamped thing that happened (a turn, an outcome, a journal note). [referenceTime] is when it happened, in epoch ms. */
data class Episode(
    val groupId: String,
    val name: String,
    val body: String,
    val sourceDescription: String,
    val referenceTime: Long,
    val facts: List<FactInput> = emptyList(),
)

@Serializable
data class MemoryFact(
    val id: String,
    val groupId: String,
    val subject: String,
    val predicate: String,
    val statement: String,
    /** When it became true (the episode's reference time). */
    val validFrom: Long,
    /** When a later fact replaced it; null while it is still the current state. */
    val invalidAt: Long? = null,
    val episodeName: String,
    val score: Double = 0.0,
    /** "fallback" or "graphiti". */
    val source: String = "fallback",
) {
    val current: Boolean get() = invalidAt == null
}

@Serializable
data class EpisodeHit(val name: String, val body: String, val referenceTime: Long, val score: Double)

@Serializable
data class MemoryContext(
    val currentFacts: List<MemoryFact> = emptyList(),
    val superseded: List<MemoryFact> = emptyList(),
    val episodes: List<EpisodeHit> = emptyList(),
    val backend: String = "fallback",
) {
    val isEmpty: Boolean get() = currentFacts.isEmpty() && episodes.isEmpty() && superseded.isEmpty()

    /**
     * The block Call 1 and Call 2 read. Dates are kept so the coach can say "back on the 3rd"; superseded facts are labelled
     * so an old stance is never treated as today's.
     */
    fun render(): String = buildString {
        if (isEmpty) return ""
        appendLine("WHAT YOU ALREADY KNOW FROM EARLIER (long-term memory; settled facts, never ask for them again):")
        currentFacts.forEach { appendLine("- [${day(it.validFrom)}] ${it.subject} — ${it.predicate}: ${it.statement}") }
        if (superseded.isNotEmpty()) {
            appendLine("NO LONGER TRUE (replaced by something newer above; do not treat as current):")
            superseded.forEach { appendLine("- [${day(it.validFrom)} → ${day(it.invalidAt!!)}] ${it.subject} — ${it.predicate}: ${it.statement}") }
        }
        if (episodes.isNotEmpty()) {
            appendLine("RELEVANT EARLIER MOMENTS:")
            episodes.forEach { appendLine("- [${day(it.referenceTime)}] ${it.body.replace('\n', ' ').take(400)}") }
        }
    }.trimEnd()

    private fun day(ms: Long) = Instant.ofEpochMilli(ms).toString().take(10)
}

/**
 * Month-long memory behind one port (SDD §3.2). The pipeline writes episodes after each turn (never blocking the reply) and
 * reads a [MemoryContext] before Call 1 and Call 2.
 */
interface MemoryService {
    val backend: String
    suspend fun ingest(episode: Episode)
    /** The current fact ledger for [entities], facts and episodes relevant to [query], newest-valid first. */
    suspend fun retrieveContext(groupIds: List<String>, query: String, entities: List<String> = emptyList(), limit: Int = 8): MemoryContext
    suspend fun searchFacts(groupIds: List<String>, query: String, limit: Int = 10, includeSuperseded: Boolean = true): List<MemoryFact>
    suspend fun healthy(): Boolean
}
