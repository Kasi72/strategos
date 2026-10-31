package com.drkkr.strategos

import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import com.drkkr.strategos.domain.flywheel.ScenarioClusters
import org.junit.Assert.*
import org.junit.Test

class ScenarioClustersTest {

    private fun record(
        scenarioId: String,
        rating: Int = 3,
        archetype: String? = null,
        followed: Boolean = true
    ): OutcomeRecord {
        val move = "MoveA"
        return OutcomeRecord(
            scenarioId = scenarioId,
            recommendedMove = move,
            actualMove = if (followed) move else "OtherMove",
            outcomeRating = rating,
            archetypeName = archetype
        )
    }

    private fun enrich(record: OutcomeRecord, players: Int = 2, constraints: Boolean = false) =
        ScenarioClusters.EnrichedRecord(record, players, constraints)

    // Empty input → empty clusters.
    @Test
    fun `empty input returns empty clusters`() {
        assertTrue(ScenarioClusters.group(emptyList()).isEmpty())
    }

    // Records with same archetype/players/constraints go into one cluster.
    @Test
    fun `identical keys group together`() {
        val records = listOf(
            enrich(record("s1", archetype = "PRISONERS_DILEMMA")),
            enrich(record("s2", archetype = "PRISONERS_DILEMMA")),
            enrich(record("s3", archetype = "PRISONERS_DILEMMA"))
        )
        val clusters = ScenarioClusters.group(records)
        assertEquals(1, clusters.size)
        assertEquals(3, clusters[0].size)
    }

    // Different archetypes → separate clusters.
    @Test
    fun `different archetypes form separate clusters`() {
        val records = listOf(
            enrich(record("s1", archetype = "PRISONERS_DILEMMA")),
            enrich(record("s2", archetype = "CHICKEN")),
            enrich(record("s3", archetype = "PRISONERS_DILEMMA"))
        )
        val clusters = ScenarioClusters.group(records)
        assertEquals(2, clusters.size)
        // Largest cluster first.
        assertEquals(2, clusters[0].size)
        assertEquals("PRISONERS_DILEMMA", clusters[0].key.archetypeName)
    }

    // Player-count difference creates separate clusters even for same archetype.
    @Test
    fun `player count differentiates clusters`() {
        val records = listOf(
            enrich(record("s1", archetype = "GENERIC"), players = 2),
            enrich(record("s2", archetype = "GENERIC"), players = 3)
        )
        val clusters = ScenarioClusters.group(records)
        assertEquals(2, clusters.size)
    }

    // averageRating and followRate computed correctly.
    @Test
    fun `averageRating and followRate are correct`() {
        val records = listOf(
            enrich(record("s1", rating = 4, followed = true)),
            enrich(record("s2", rating = 2, followed = false)),
            enrich(record("s3", rating = 3, followed = true))
        )
        val cluster = ScenarioClusters.group(records).first()
        assertEquals(3.0, cluster.averageRating(), 1e-9)
        assertEquals(2.0 / 3.0, cluster.followRate(), 1e-9)
    }

    // Null archetype falls back to "GENERIC".
    @Test
    fun `null archetype maps to GENERIC`() {
        val records = listOf(enrich(record("s1", archetype = null)))
        val cluster = ScenarioClusters.group(records).first()
        assertEquals("GENERIC", cluster.key.archetypeName)
    }

    // OutcomeRecord.followed reflects whether user followed the recommendation.
    @Test
    fun `OutcomeRecord followed flag is accurate`() {
        val followed   = record("s1", followed = true)
        val unfollowed = record("s2", followed = false)
        assertTrue(followed.followed)
        assertFalse(unfollowed.followed)
    }

    // OutcomeRecord rejects out-of-range ratings.
    @Test(expected = IllegalArgumentException::class)
    fun `OutcomeRecord rejects rating 0`() {
        record("s1", rating = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `OutcomeRecord rejects rating 6`() {
        record("s1", rating = 6)
    }
}
