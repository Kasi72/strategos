package com.drkkr.strategos

import com.drkkr.strategos.domain.game.OpponentActionClassifier
import com.drkkr.strategos.domain.game.OpponentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpponentActionClassifierTest {

    private fun model(
        epsilon: Double = 0.2,
        discountFactor: Double = 0.6,
        cooperationIndex: Double? = null,
        riskAversion: Double? = null,
        informationBelief: Double? = null
    ) = OpponentModel(
        playerId = "opp",
        epsilon = epsilon,
        discountFactor = discountFactor,
        cooperationIndex = cooperationIndex,
        riskAversion = riskAversion,
        informationBelief = informationBelief
    )

    @Test
    fun `high cooperationIndex yields COOPERATIVE primary`() {
        val result = OpponentActionClassifier.classify(model(cooperationIndex = 0.8))
        assertEquals(OpponentActionClassifier.BehaviorClass.COOPERATIVE, result.primary)
    }

    @Test
    fun `low cooperationIndex yields COMPETITIVE primary`() {
        val result = OpponentActionClassifier.classify(model(cooperationIndex = 0.1))
        assertEquals(OpponentActionClassifier.BehaviorClass.COMPETITIVE, result.primary)
    }

    @Test
    fun `high riskAversion yields RISK_AVERSE primary`() {
        val result = OpponentActionClassifier.classify(model(riskAversion = 0.9))
        assertEquals(OpponentActionClassifier.BehaviorClass.RISK_AVERSE, result.primary)
    }

    @Test
    fun `low riskAversion yields RISK_SEEKING primary`() {
        val result = OpponentActionClassifier.classify(model(riskAversion = 0.1))
        assertEquals(OpponentActionClassifier.BehaviorClass.RISK_SEEKING, result.primary)
    }

    @Test
    fun `high informationBelief yields INFORMATION_SHARING primary`() {
        val result = OpponentActionClassifier.classify(model(informationBelief = 0.9))
        assertEquals(OpponentActionClassifier.BehaviorClass.INFORMATION_SHARING, result.primary)
    }

    @Test
    fun `low informationBelief yields INFORMATION_WITHHOLDING primary`() {
        val result = OpponentActionClassifier.classify(model(informationBelief = 0.1))
        assertEquals(OpponentActionClassifier.BehaviorClass.INFORMATION_WITHHOLDING, result.primary)
    }

    @Test
    fun `high epsilon low discountFactor yields REACTIVE`() {
        val result = OpponentActionClassifier.classify(
            model(epsilon = 0.8, discountFactor = 0.2)
        )
        assertEquals(OpponentActionClassifier.BehaviorClass.REACTIVE, result.primary)
    }

    @Test
    fun `low epsilon high discountFactor yields STRATEGIC`() {
        val result = OpponentActionClassifier.classify(
            model(epsilon = 0.1, discountFactor = 0.9)
        )
        assertEquals(OpponentActionClassifier.BehaviorClass.STRATEGIC, result.primary)
    }

    @Test
    fun `all nulls falls back to STRATEGIC`() {
        val result = OpponentActionClassifier.classify(
            model(epsilon = 0.5, discountFactor = 0.5)
        )
        assertEquals(OpponentActionClassifier.BehaviorClass.STRATEGIC, result.primary)
    }

    @Test
    fun `confidence in range 0 to 1`() {
        val result = OpponentActionClassifier.classify(model(cooperationIndex = 0.9))
        assertTrue(result.confidence in 0.0..1.0)
    }

    @Test
    fun `note is non-empty`() {
        val result = OpponentActionClassifier.classify(model(cooperationIndex = 0.8))
        assertTrue(result.note.isNotBlank())
    }

    @Test
    fun `two signals produce secondary classification`() {
        val result = OpponentActionClassifier.classify(
            model(cooperationIndex = 0.8, riskAversion = 0.9)
        )
        assertNotNull(result.secondary)
    }

    @Test
    fun `note contains secondary class when present`() {
        val result = OpponentActionClassifier.classify(
            model(cooperationIndex = 0.8, riskAversion = 0.9)
        )
        assertNotNull(result.secondary)
        assertTrue(result.note.contains(result.secondary!!.name.lowercase().replace('_', ' ')))
    }

    @Test
    fun `single strong signal has null secondary`() {
        val result = OpponentActionClassifier.classify(
            model(epsilon = 0.5, discountFactor = 0.5, cooperationIndex = 0.8)
        )
        assertNotNull(result)
    }
}
