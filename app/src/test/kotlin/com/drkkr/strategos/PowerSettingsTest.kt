package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.repo.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Power Mode, PM1.1: the per-conversation switch and the Power Mode lock (I-P1, I-P4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PowerSettingsTest {
    private lateinit var db: StrategosDatabase
    private lateinit var settings: SettingsRepository

    @Before fun setUp() {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        settings = SettingsRepository(db)
    }

    @After fun tearDown() = db.close()

    @Test fun TC_PM_SET_01_offByDefault() = runBlocking {
        assertFalse(settings.isPowerMode("c1"))
        assertFalse(settings.observePowerMode("c1").first())
        assertFalse(settings.powerLock())
    }

    @Test fun TC_PM_SET_02_onForOneConversationOnly() = runBlocking {
        settings.setPowerMode("c1", true)
        assertTrue(settings.isPowerMode("c1"))
        assertFalse(settings.isPowerMode("c2"))
        assertTrue(settings.observePowerMode("c1").first())
        settings.setPowerMode("c1", false)
        assertFalse(settings.isPowerMode("c1"))
    }

    @Test fun TC_PM_SET_03_theLockForcesItOff() = runBlocking {
        settings.setPowerMode("c1", true)
        settings.setPowerLock(true)
        assertTrue(settings.powerLock())
        assertFalse(settings.isPowerMode("c1"))
        assertFalse(settings.observePowerMode("c1").first())
        settings.setPowerLock(false)
        assertTrue("the user's choice is kept under the lock", settings.isPowerMode("c1"))
    }
}
