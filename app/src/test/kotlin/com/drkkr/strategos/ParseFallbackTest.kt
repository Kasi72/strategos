package com.drkkr.strategos

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.ui.StrategosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * With no API key configured (the default, and the case under Robolectric where
 * encrypted storage is unavailable), parseAndCreateScenario must fall back to the
 * deterministic heuristic parser rather than fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParseFallbackTest {

    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun noApiKey_usesHeuristicParserAndExtractsEntities() {
        val vm = StrategosViewModel(ApplicationProvider.getApplicationContext<Application>())
        assertTrue("no key expected in test storage", !vm.state.value.hasApiKey)

        vm.parseAndCreateScenario(
            "The clinical director could veto while the department head wants to pilot the protocol."
        )

        val st = vm.state.value
        assertEquals("heuristic", st.parseSource)
        val scenario = st.activeScenario!!
        assertTrue("heuristic should extract stakeholders", scenario.stakeholders.isNotEmpty())
        assertTrue("heuristic should extract actions", scenario.actions.isNotEmpty())
    }

    @Test
    fun extractIntoActiveScenario_mergesEntitiesPreservingIdentity() {
        val vm = StrategosViewModel(ApplicationProvider.getApplicationContext<Application>())
        val base = Scenario(
            id = "keep-me",
            title = "My conflict",
            description = "The clinical director could veto while the department head wants to pilot the protocol.",
            decisionOwner = "me"
        )
        vm.setActiveScenario(base)

        vm.extractIntoActiveScenario()

        val s = vm.state.value.activeScenario!!
        assertEquals("id must be preserved", "keep-me", s.id)
        assertEquals("title must be preserved", "My conflict", s.title)
        assertEquals("owner must be preserved", "me", s.decisionOwner)
        assertTrue("stakeholders should be merged in", s.stakeholders.isNotEmpty())
        assertTrue("actions should be merged in", s.actions.isNotEmpty())
        assertEquals("heuristic", vm.state.value.parseSource)
    }
}
