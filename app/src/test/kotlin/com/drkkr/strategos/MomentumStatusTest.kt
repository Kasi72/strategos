package com.drkkr.strategos

import com.drkkr.strategos.domain.analysis.MomentumStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MomentumStatusTest {

    @Test fun TC_MOMENTUM_01_trustRisingTensionEasingIsImproving() {
        assertEquals(MomentumStatus.IMPROVING, MomentumStatus.from(trustSlope = 2.0, tensionSlope = -1.5))
    }

    @Test fun TC_MOMENTUM_02_trustFallingOrTensionRisingIsTense() {
        assertEquals(MomentumStatus.TENSE, MomentumStatus.from(trustSlope = -2.0, tensionSlope = 1.0))
        assertEquals(MomentumStatus.TENSE, MomentumStatus.from(trustSlope = 0.0, tensionSlope = 2.0))
    }

    @Test fun TC_MOMENTUM_03_flatOrBelowThresholdIsSteady() {
        assertEquals(MomentumStatus.STEADY, MomentumStatus.from(trustSlope = 0.0, tensionSlope = 0.0))
        // Net change sits right at the threshold, not past it.
        assertEquals(MomentumStatus.STEADY, MomentumStatus.from(trustSlope = 0.25, tensionSlope = -0.25))
    }

    @Test fun TC_MOMENTUM_04_slopeOfRisingSeriesIsPositive() {
        // A clean +5-per-step ramp has slope 5.
        assertEquals(5.0, MomentumStatus.slope(listOf(50.0, 55.0, 60.0, 65.0)), 1e-9)
    }

    @Test fun TC_MOMENTUM_05_shortOrDegenerateSeriesReadsSteady() {
        assertEquals(0.0, MomentumStatus.slope(emptyList()), 0.0)
        assertEquals(0.0, MomentumStatus.slope(listOf(60.0)), 0.0)
        assertEquals(0.0, MomentumStatus.slope(listOf(60.0, 60.0, 60.0)), 0.0)
        assertEquals(MomentumStatus.STEADY, MomentumStatus.of(listOf(60.0, 60.0), listOf(40.0, 40.0)))
    }

    @Test fun TC_MOMENTUM_06_ofSeriesClassifiesRisingTrustFallingTension() {
        val trust = listOf(48.0, 52.0, 58.0, 63.0)
        val tension = listOf(60.0, 55.0, 50.0, 44.0)
        assertEquals(MomentumStatus.IMPROVING, MomentumStatus.of(trust, tension))
    }

    @Test fun TC_MOMENTUM_07_nonFiniteSlopesFallBackToSteady() {
        assertEquals(MomentumStatus.STEADY, MomentumStatus.from(Double.NaN, Double.POSITIVE_INFINITY))
    }
}
