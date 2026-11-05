package com.drkkr.strategos

import com.drkkr.strategos.domain.human.HumanConstants
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.RelationshipDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, stage HL0: relationship domain, constants, reference-point prospect transform. */
class HumanFoundationTest {

    @Test
    fun TC_HL_DOM_01_relationshipMapsToDomain() {
        assertEquals(RelationshipDomain.INTIMATE, RelationshipDomains.of("spouse", null))
        assertEquals(RelationshipDomain.FAMILY, RelationshipDomains.of("sibling", null))
        assertEquals(RelationshipDomain.WORK_HIERARCHY, RelationshipDomains.of("manager", null))
        assertEquals(RelationshipDomain.WORK_PEER, RelationshipDomains.of("cofounder", null))
        assertEquals(RelationshipDomain.NEGOTIATION, RelationshipDomains.of("vendor", null))
        assertEquals(RelationshipDomain.ADVERSARIAL, RelationshipDomains.of("opponent", null))
    }

    @Test
    fun TC_HL_DOM_01b_restOfVocabularyAndSpelling() {
        assertEquals(RelationshipDomain.INTIMATE, RelationshipDomains.of("partner", null))
        assertEquals(RelationshipDomain.FAMILY, RelationshipDomains.of("Parent", null))
        assertEquals(RelationshipDomain.FAMILY, RelationshipDomains.of("child", null))
        assertEquals(RelationshipDomain.FAMILY, RelationshipDomains.of(" relative ", null))
        assertEquals(RelationshipDomain.FRIENDSHIP, RelationshipDomains.of("friend", null))
        assertEquals(RelationshipDomain.WORK_HIERARCHY, RelationshipDomains.of("report", null))
        assertEquals(RelationshipDomain.WORK_PEER, RelationshipDomains.of("peer", null))
        assertEquals(RelationshipDomain.NEGOTIATION, RelationshipDomains.of("client", null))
        assertEquals(RelationshipDomain.NEGOTIATION, RelationshipDomains.of("landlord", null))
    }

    @Test
    fun TC_HL_DOM_02_unknownResolvesToMostProtective() {
        assertEquals(RelationshipDomain.INTIMATE, RelationshipDomains.of(null, "RELATIONAL_REPAIR"))
        assertEquals(RelationshipDomain.INTIMATE, RelationshipDomains.of("xyz", null))
        assertEquals(RelationshipDomain.INTIMATE, RelationshipDomains.of("other", ""))
        // A non-repair archetype with an unknown relationship falls back to FRIENDSHIP (still BENIGN-only).
        assertEquals(RelationshipDomain.FRIENDSHIP, RelationshipDomains.of("xyz", "NEGOTIATION"))
    }

    @Test
    fun TC_HL_DOM_03_isCloseOnlyForCloseDomains() {
        val close = RelationshipDomain.values().filter { it.isClose }.toSet()
        assertEquals(setOf(RelationshipDomain.INTIMATE, RelationshipDomain.FAMILY, RelationshipDomain.FRIENDSHIP), close)
        assertFalse(RelationshipDomain.WORK_PEER.isClose)
    }

    @Test
    fun TC_HL_CONST_01_scaledChangesEffectSizesOnly() {
        val d = HumanConstants.DEFAULT
        val s = d.scaled(2.0)
        assertEquals(d.lossAversion * 2, s.lossAversion, 1e-9)
        assertEquals(d.faceCost * 2, s.faceCost, 1e-9)
        assertEquals(d.interestShift * 2, s.interestShift, 1e-9)
        assertEquals(d.inertia * 2, s.inertia, 1e-9)
        assertEquals(d.frictionPerStep * 2, s.frictionPerStep, 1e-9)
        assertEquals(d.settingShift * 2, s.settingShift, 1e-9)
        assertEquals(d.commitShift * 2, s.commitShift, 1e-9)
        // thresholds, caps, counts and the share are untouched
        assertEquals(d.cellCap, s.cellCap, 0.0)
        assertEquals(d.deltaPMin, s.deltaPMin, 0.0)
        assertEquals(d.maxTactics, s.maxTactics)
        assertEquals(d.frictionMax, s.frictionMax, 0.0)
        assertEquals(d.privateFaceShare, s.privateFaceShare, 0.0)
        assertEquals(d, d.scaled(1.0))
    }

    @Test
    fun TC_HL_CONST_02_defaultsMatchTheBlueprint() {
        val d = HumanConstants.DEFAULT
        assertEquals(2.0, d.lossAversion, 0.0)
        assertEquals(1.5, d.faceCost, 0.0)
        assertEquals(0.5, d.interestShift, 0.0)
        assertEquals(0.5, d.inertia, 0.0)
        assertEquals(0.25, d.frictionPerStep, 0.0)
        assertEquals(0.75, d.frictionMax, 0.0)
        assertEquals(0.5, d.settingShift, 0.0)
        assertEquals(0.25, d.commitShift, 0.0)
        assertEquals(2.0, d.cellCap, 0.0)
        assertEquals(0.02, d.deltaPMin, 0.0)
        assertEquals(2, d.maxTactics)
        assertTrue(d.privateFaceShare > 0.33 && d.privateFaceShare < 0.34)
    }
}
