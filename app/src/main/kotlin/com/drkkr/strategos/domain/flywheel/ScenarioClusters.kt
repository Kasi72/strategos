package com.drkkr.strategos.domain.flywheel

/**
 * Groups [OutcomeRecord]s into clusters by structural similarity:
 * archetype, player count (approximated from outcome entries), and presence of constraints.
 *
 * Used by the data flywheel to surface patterns across past decisions and improve
 * future Bayesian priors. Pure domain: no android.* or androidx.* imports.
 *
 * Archetype and player count are supplied via [EnrichedRecord] — thin wrappers that
 * attach compile-time metadata the engine computed when the recommendation was made.
 */
object ScenarioClusters {

    /**
     * An [OutcomeRecord] enriched with metadata the engine knows at recommendation time
     * but that is not stored on the outcome itself.
     *
     * @param record       The base outcome record.
     * @param playerCount  Number of PLAYER-role stakeholders when the move was recommended.
     * @param hasConstraints Whether the scenario had at least one limit/constraint.
     */
    data class EnrichedRecord(
        val record: OutcomeRecord,
        val playerCount: Int,
        val hasConstraints: Boolean
    )

    /**
     * A cluster key that identifies structurally similar decision situations.
     *
     * @param archetypeName  Detected game archetype, or "GENERIC" when none.
     * @param playerCount    Number of PLAYER-role stakeholders.
     * @param hasConstraints Whether the scenario had at least one limit/constraint.
     */
    data class ClusterKey(
        val archetypeName: String,
        val playerCount: Int,
        val hasConstraints: Boolean
    )

    /** A cluster: one key and the enriched records that belong to it. */
    data class Cluster(
        val key: ClusterKey,
        val records: List<EnrichedRecord>
    ) {
        val size: Int get() = records.size

        /** Average user-reported outcome rating across records in this cluster. */
        fun averageRating(): Double = if (records.isEmpty()) 0.0 else records.sumOf { it.record.outcomeRating.toDouble() } / size

        /** Fraction of records where the user followed the recommendation. */
        fun followRate(): Double = if (records.isEmpty()) 0.0 else records.count { it.record.followed }.toDouble() / size
    }

    /**
     * Groups [records] by [ClusterKey].
     * Returns clusters sorted by size descending (largest cluster first).
     */
    fun group(records: List<EnrichedRecord>): List<Cluster> =
        records.groupBy { keyOf(it) }
            .map { (key, members) -> Cluster(key, members) }
            .sortedByDescending { it.size }

    private fun keyOf(r: EnrichedRecord): ClusterKey = ClusterKey(
        archetypeName  = r.record.archetypeName ?: "GENERIC",
        playerCount    = r.playerCount,
        hasConstraints = r.hasConstraints
    )
}
