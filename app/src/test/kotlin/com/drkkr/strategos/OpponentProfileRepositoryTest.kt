package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.repo.OpponentProfileRepository
import com.drkkr.strategos.domain.game.OpponentModel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpponentProfileRepositoryTest {

    private lateinit var repo: OpponentProfileRepository
    private lateinit var dbPair: Pair<com.drkkr.strategos.data.db.StrategosDatabase, java.util.concurrent.ExecutorService>

    @Before fun setUp() {
        dbPair = inMemoryDbForTest(ApplicationProvider.getApplicationContext())
        repo = OpponentProfileRepository(dbPair.first)
    }

    @After fun tearDown() = closeDbSafely(dbPair.first, dbPair.second)

    private fun model(id: String = "opp1") = OpponentModel(
        playerId = id,
        epsilon = 0.3,
        discountFactor = 0.7,
        riskAversion = 0.5,
        cooperationIndex = 0.6,
        informationBelief = 0.4
    )

    @Test fun returnsNullForUnknownName() = runTest {
        assertNull(repo.get("nobody"))
    }

    @Test fun upsertAndGetRoundTrip() = runTest {
        val m = model()
        repo.upsert("Alice", m, sampleSize = 5)
        val got = repo.get("Alice")
        assertNotNull(got)
        assertEquals(m.playerId, got!!.playerId)
        assertEquals(m.epsilon, got.epsilon, 0.0001)
        assertEquals(m.discountFactor, got.discountFactor, 0.0001)
        assertEquals(m.riskAversion, got.riskAversion)
        assertEquals(m.cooperationIndex, got.cooperationIndex)
        assertEquals(m.informationBelief, got.informationBelief)
    }

    @Test fun upsertOverwritesPrevious() = runTest {
        repo.upsert("Bob", model("b1"), sampleSize = 2)
        val updated = model("b2").copy(epsilon = 0.1)
        repo.upsert("Bob", updated, sampleSize = 3)
        val got = repo.get("Bob")
        assertNotNull(got)
        assertEquals("b2", got!!.playerId)
        assertEquals(0.1, got.epsilon, 0.0001)
    }

    @Test fun nullOptionalFieldsPreserved() = runTest {
        val m = OpponentModel(playerId = "x", epsilon = 0.2, discountFactor = 0.8)
        repo.upsert("Carol", m, sampleSize = 1)
        val got = repo.get("Carol")
        assertNotNull(got)
        assertNull(got!!.riskAversion)
        assertNull(got.cooperationIndex)
        assertNull(got.informationBelief)
    }

    @Test fun differentNamesAreIndependent() = runTest {
        repo.upsert("Dan", model("d1"), sampleSize = 1)
        repo.upsert("Eve", model("e1").copy(epsilon = 0.9), sampleSize = 1)
        assertEquals("d1", repo.get("Dan")!!.playerId)
        assertEquals(0.9, repo.get("Eve")!!.epsilon, 0.0001)
    }
}
