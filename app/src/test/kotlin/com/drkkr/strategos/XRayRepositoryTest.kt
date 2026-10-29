package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.ScenarioRepository
import com.drkkr.strategos.data.repo.XRayRepository
import com.drkkr.strategos.domain.agent.PriorId
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.fixtures.Fx
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XRayRepositoryTest {
    private lateinit var db: StrategosDatabase
    private lateinit var scenarios: ScenarioRepository
    private lateinit var xray: XRayRepository
    private lateinit var sid: String

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        scenarios = ScenarioRepository(db, null).also { it.load() }
        sid = (scenarios.save(Fx.loanFamily()) as SaveResult.Success).saved.id
        xray = XRayRepository(scenarios)
    }
    @After fun tearDown() { db.close() }

    @Test fun TC_XRAYREPO_01_keepAsMinePersistsAsYours() = runBlocking {
        assertTrue(xray.keepAsMine(sid, mapOf(PriorId.TAKES_LIMIT_WELL to 0.75)))
        val bro = scenarios.find(sid)!!.stakeholders.first { it.id == "bro" }
        assertEquals(0.75, bro.probRespectsBoundary!!, 1e-9)
        assertEquals(FactStatus.USER_ESTIMATE, bro.factStatus)
        assertTrue(xray.priors(sid).single { it.id == PriorId.TAKES_LIMIT_WELL }.isUserSet)
    }

    @Test fun TC_XRAYREPO_02_resetClearsThePersistedPrior() = runBlocking {
        xray.keepAsMine(sid, mapOf(PriorId.TAKES_LIMIT_WELL to 0.75))
        assertTrue(xray.reset(sid, setOf(PriorId.TAKES_LIMIT_WELL)))
        assertNull(scenarios.find(sid)!!.stakeholders.first { it.id == "bro" }.probRespectsBoundary)
    }
}
