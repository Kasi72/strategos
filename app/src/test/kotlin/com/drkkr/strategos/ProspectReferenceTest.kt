package com.drkkr.strategos

import com.drkkr.strategos.domain.game.ProspectTheoryTransform
import org.junit.Assert.assertEquals
import org.junit.Test

/** Human Layer, HL0.3: reference-point prospect transform. */
class ProspectReferenceTest {

    @Test
    fun TC_HL_PT_01_noOpAtTheReference() {
        assertEquals(5.0, ProspectTheoryTransform.referenceValue(5.0, 5.0), 0.0)
    }

    @Test
    fun TC_HL_PT_02_gainsUnchangedLossesDoubled() {
        assertEquals(7.0, ProspectTheoryTransform.referenceValue(7.0, 5.0), 1e-9)
        assertEquals(1.0, ProspectTheoryTransform.referenceValue(3.0, 5.0), 1e-9)
    }

    @Test
    fun TC_HL_PT_03_existingDefaultsUntouched() {
        val d = ProspectTheoryTransform.DEFAULT
        assertEquals(0.88, d.alpha, 0.0)
        assertEquals(2.25, d.lambda, 0.0)
        assertEquals(2.0, ProspectTheoryTransform.HUMAN_LAYER.lambda, 0.0)
        assertEquals(1.0, ProspectTheoryTransform.HUMAN_LAYER.alpha, 0.0)
    }
}
