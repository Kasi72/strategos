package com.drkkr.strategos.server.memory

import com.drkkr.strategos.server.persistence.Database
import java.util.UUID
import kotlin.math.ln

/**
 * The offline memory (SDD §4.2): deterministic, no model calls. Episodes are searched by keyword (IDF-weighted) with
 * entity and recency boosts; facts form a temporal ledger where a newer fact on the same subject + predicate closes the
 * older one's validity window. With a [db] it persists (so localhost keeps its memory across restarts without Graphiti);
 * without one it is purely in memory, for unit tests.
 */
class InMemoryTemporalFallback(private val db: Database? = null) : MemoryService {
    override val backend = "fallback"

    private val episodes = mutableListOf<Episode>()
    private val facts = mutableListOf<MemoryFact>()
    private val lock = Any()

    init {
        db?.query("SELECT * FROM memory_episodes ORDER BY ref_time") { rs ->
            Episode(rs.getString("group_id"), rs.getString("name"), rs.getString("body"), rs.getString("source"), rs.getLong("ref_time"))
        }?.let(episodes::addAll)
        db?.query("SELECT * FROM memory_facts ORDER BY valid_from") { rs ->
            MemoryFact(rs.getString("id"), rs.getString("group_id"), rs.getString("subject"), rs.getString("predicate"),
                rs.getString("statement"), rs.getLong("valid_from"), rs.getLong("invalid_at").takeIf { !rs.wasNull() }, rs.getString("episode_name"))
        }?.let(facts::addAll)
    }

    override suspend fun ingest(episode: Episode) = synchronized(lock) {
        episodes += episode
        db?.update("INSERT INTO memory_episodes VALUES (?,?,?,?,?,?)", UUID.randomUUID().toString(), episode.groupId, episode.name,
            episode.body, episode.sourceDescription, episode.referenceTime)
        episode.facts.forEach { f -> addFact(episode, f) }
    }

    private fun addFact(episode: Episode, f: FactInput) {
        if (f.statement.isBlank() || f.subject.isBlank()) return
        val key = norm(f.subject) to norm(f.predicate)
        val sameKey = facts.withIndex().filter { (_, x) -> x.groupId == episode.groupId && (norm(x.subject) to norm(x.predicate)) == key }
        // Repeating what is already current changes nothing.
        if (sameKey.any { (_, x) -> x.current && norm(x.statement) == norm(f.statement) }) return
        // Facts can arrive out of order (a back-filled episode): only facts older than this one are closed by it,
        // and this one is born closed when something newer on the same key already exists.
        val newer = sameKey.map { it.value }.filter { it.validFrom > episode.referenceTime }.minByOrNull { it.validFrom }
        sameKey.forEach { (i, x) ->
            if (x.validFrom <= episode.referenceTime && (x.invalidAt == null || x.invalidAt > episode.referenceTime)) {
                facts[i] = x.copy(invalidAt = episode.referenceTime)
                db?.update("UPDATE memory_facts SET invalid_at = ? WHERE id = ?", episode.referenceTime, x.id)
            }
        }
        val fact = MemoryFact(UUID.randomUUID().toString(), episode.groupId, f.subject.trim(), f.predicate.trim(), f.statement.trim(),
            episode.referenceTime, newer?.validFrom, episode.name)
        facts += fact
        db?.update("INSERT INTO memory_facts VALUES (?,?,?,?,?,?,?,?)", fact.id, fact.groupId, fact.subject, fact.predicate,
            fact.statement, fact.validFrom, fact.invalidAt, fact.episodeName)
    }

    override suspend fun retrieveContext(groupIds: List<String>, query: String, entities: List<String>, limit: Int): MemoryContext = synchronized(lock) {
        val inGroups = facts.filter { it.groupId in groupIds }
        val scoredFacts = rank(inGroups, query, entities) { "${it.subject} ${it.predicate} ${it.statement}" }
        val people = entities.map(::norm).filter { it.isNotEmpty() }.toSet()
        // The ledger: every current fact about a person in this turn, then the current facts that match the words.
        val ledger = inGroups.filter { it.current && norm(it.subject) in people }.sortedByDescending { it.validFrom }
        val current = (ledger + scoredFacts.filter { it.first.current && it.second > 0 }.map { it.first })
            .distinctBy { it.id }.take(limit * 2)
        val currentKeys = current.map { norm(it.subject) to norm(it.predicate) }.toSet()
        val superseded = inGroups.filter { !it.current && (norm(it.subject) to norm(it.predicate)) in currentKeys }
            .sortedByDescending { it.validFrom }.take(limit)
        val eps = rank(episodes.filter { it.groupId in groupIds }, query, entities) { it.body }
            .filter { it.second > 0 }.take(limit)
            .map { (e, s) -> EpisodeHit(e.name, e.body, e.referenceTime, s) }
        MemoryContext(current, superseded, eps, backend)
    }

    override suspend fun searchFacts(groupIds: List<String>, query: String, limit: Int, includeSuperseded: Boolean): List<MemoryFact> = synchronized(lock) {
        rank(facts.filter { it.groupId in groupIds && (includeSuperseded || it.current) }, query, emptyList()) { "${it.subject} ${it.predicate} ${it.statement}" }
            .filter { it.second > 0 }
            // Current state first, then by relevance, then newest.
            .sortedWith(compareByDescending<Pair<MemoryFact, Double>> { it.first.current }.thenByDescending { it.second }.thenByDescending { it.first.validFrom })
            .take(limit).map { (f, s) -> f.copy(score = s) }
    }

    override suspend fun healthy() = true

    /** IDF-weighted keyword overlap, +1.5 per named person mentioned, and a small recency tie-break. */
    private fun <T> rank(items: List<T>, query: String, entities: List<String>, text: (T) -> String): List<Pair<T, Double>> {
        if (items.isEmpty()) return emptyList()
        val docs = items.map { tokens(text(it)) }
        val df = HashMap<String, Int>().apply { docs.forEach { d -> d.forEach { merge(it, 1, Int::plus) } } }
        val q = tokens(query)
        val names = entities.map(::norm).filter { it.isNotEmpty() }
        return items.mapIndexed { i, item ->
            val d = docs[i]
            val words = q.filter { it in d }.sumOf { ln(1.0 + items.size.toDouble() / (df[it] ?: 1)) }
            val who = names.count { n -> n in norm(text(item)) } * 1.5
            val score = words + who
            item to (if (score > 0) score + 0.01 * i / items.size else 0.0)
        }.sortedByDescending { it.second }
    }

    companion object {
        private val STOP = setOf(
            "the", "and", "for", "with", "that", "this", "you", "your", "are", "was", "but", "not", "have", "has", "had", "how", "what",
            "why", "who", "when", "where", "will", "would", "should", "could", "can", "him", "her", "his", "she", "they", "them",
            "about", "from", "into", "just", "like", "then", "than", "there", "their", "been", "were", "our", "out", "all", "any",
            "tomorrow", "today", "now", "said", "says", "tell", "told", "want", "wants",
        )
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        fun tokens(s: String): Set<String> = norm(s).split(' ').filter { it.length >= 3 && it !in STOP }
            .map { it.removeSuffix("s").takeIf { w -> w.length >= 3 } ?: it }.toSet()
    }
}
