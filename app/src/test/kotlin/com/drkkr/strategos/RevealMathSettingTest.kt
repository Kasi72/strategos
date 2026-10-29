package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.repo.RevealMath
import com.drkkr.strategos.data.repo.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RevealMathSettingTest {
    private lateinit var db: StrategosDatabase
    private lateinit var settings: SettingsRepository

    @Before fun setUp() { db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext()); settings = SettingsRepository(db) }
    @After fun tearDown() { db.close() }

    @Test fun TC_REVEAL_01_defaultsToOnTap() = runBlocking {
        assertEquals(RevealMath.ON_TAP, settings.revealMath())
    }

    @Test fun TC_REVEAL_02_persistsAndReadsBack() = runBlocking {
        settings.setRevealMath(RevealMath.ALWAYS)
        assertEquals(RevealMath.ALWAYS, settings.revealMath())
        settings.setRevealMath(RevealMath.NEVER)
        assertEquals(RevealMath.NEVER, settings.revealMath())
    }

    @Test fun TC_REVEAL_03_isObservable() = runBlocking {
        settings.setRevealMath(RevealMath.ALWAYS)
        assertEquals(RevealMath.ALWAYS.name, settings.observeRevealMath().first())
    }

    @Test fun TC_REVEAL_04_unknownValueFallsBackToOnTap() = runBlocking {
        db.settingDao().put(com.drkkr.strategos.data.db.entities.SettingEntity(SettingsRepository.UI_REVEAL_MATH, "GARBAGE"))
        assertEquals(RevealMath.ON_TAP, settings.revealMath())
    }
}
