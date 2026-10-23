package com.drkkr.strategos

import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.fixtures.Fx
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceDefaultsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun TC_PROV_01_v1JsonDecodesToUserEstimateAndPlayer() {
        val s = json.decodeFromString<Scenario>(Fx.v1ScenarioJson)
        assertTrue(s.stakeholders.isNotEmpty() && s.actions.isNotEmpty() && s.constraints.isNotEmpty() && s.payoffs.isNotEmpty())
        assertTrue(s.stakeholders.all { it.factStatus == FactStatus.USER_ESTIMATE })
        assertTrue(s.actions.all { it.factStatus == FactStatus.USER_ESTIMATE })
        assertTrue(s.constraints.all { it.factStatus == FactStatus.USER_ESTIMATE })
        assertTrue(s.payoffs.all { it.factStatus == FactStatus.USER_ESTIMATE })
    }

    @Test
    fun TC_PROV_02_roundTripIsEqual() {
        val original = Fx.pd().copy(
            stakeholders = Fx.pd().stakeholders.map { it.copy(factStatus = FactStatus.AI_INFERRED, provenanceNote = "why") }
        )
        val decoded = json.decodeFromString<Scenario>(json.encodeToString(original))
        assertEquals(original, decoded)
    }
}
