package com.drkkr.strategos

import com.drkkr.strategos.domain.human.Ethics
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.Tactic
import com.drkkr.strategos.domain.human.TacticId
import com.drkkr.strategos.domain.human.power.PowerGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Power Mode, PM1.2: the gate for STRATEGIC tactics (blueprint §4; I-P2, I-P4, I-H4, I-H5). */
class PowerGateTest {
    private val nonClose = RelationshipDomain.values().filter { !it.isClose }.toSet()
    private fun t(e: Ethics, domains: Set<RelationshipDomain> = nonClose) =
        Tactic(TacticId.SAY_LESS, "L4", e, true, domains, { _, _, _ -> true }, emptySet(), emptySet(), "x")
    private val strategic = t(Ethics.STRATEGIC)
    private val N = RelationshipDomain.NEGOTIATION
    private val calm = HumanContext()

    @Test fun TC_PM_GATE_01_offMeansNo() {
        assertFalse(PowerGate.allowed(strategic, calm, N, powerOn = false, support = false, powerLock = false))
        assertTrue(PowerGate.allowed(strategic, calm, N, powerOn = true, support = false, powerLock = false))
    }

    @Test fun TC_PM_GATE_02_neverInACloseRelationship() {
        val everywhere = t(Ethics.STRATEGIC, RelationshipDomain.values().toSet())
        for (d in listOf(RelationshipDomain.INTIMATE, RelationshipDomain.FAMILY, RelationshipDomain.FRIENDSHIP))
            assertFalse("$d", PowerGate.allowed(everywhere, calm, d, powerOn = true, support = false, powerLock = false))
    }

    @Test fun TC_PM_GATE_03_neverOnASupportTurn() =
        assertFalse(PowerGate.allowed(strategic, calm, N, powerOn = true, support = true, powerLock = false))

    @Test fun TC_PM_GATE_04_neverWithAVulnerableCounterpart() {
        assertFalse(PowerGate.allowed(strategic, HumanContext(theirVulnerable = "unwell"), N, powerOn = true, support = false, powerLock = false))
        assertTrue(PowerGate.allowed(strategic, HumanContext(theirVulnerable = "none"), N, powerOn = true, support = false, powerLock = false))
    }

    @Test fun TC_PM_GATE_05_neverWithACoercivePattern() {
        for (tag in listOf("keeps_you_dependent", "isolates_you", "hot_cold"))
            assertFalse(tag, PowerGate.allowed(strategic, HumanContext(theirTactics = listOf(tag)), RelationshipDomain.WORK_PEER, powerOn = true, support = false, powerLock = false))
        assertTrue(PowerGate.allowed(strategic, HumanContext(theirTactics = listOf("artificial_deadline")), RelationshipDomain.WORK_PEER, powerOn = true, support = false, powerLock = false))
    }

    @Test fun TC_PM_GATE_06_neverUnderTheLock() =
        assertFalse(PowerGate.allowed(strategic, calm, N, powerOn = true, support = false, powerLock = true))

    @Test fun TC_PM_GATE_07_manipulativeIsNeverAllowed() {
        val manipulative = t(Ethics.MANIPULATIVE, RelationshipDomain.values().toSet())
        val ctxs = listOf<HumanContext?>(null, calm, HumanContext(theirVulnerable = "none"), HumanContext(theirTactics = listOf("probes_you")))
        val flags = listOf(true, false)
        for (d in RelationshipDomain.values()) for (on in flags) for (support in flags) for (lock in flags) for (c in ctxs)
            assertFalse("$d on=$on support=$support lock=$lock", PowerGate.allowed(manipulative, c, d, on, support, lock))
    }

    @Test fun benignAndContextTacticsKeepUsingTheHumanLayerGate() {
        val benign = t(Ethics.BENIGN, RelationshipDomain.values().toSet())
        assertTrue(PowerGate.allowed(benign, calm, RelationshipDomain.INTIMATE, powerOn = false, support = false, powerLock = false))
        assertFalse(PowerGate.allowed(benign, calm, RelationshipDomain.INTIMATE, powerOn = true, support = true, powerLock = false))
        assertFalse(PowerGate.allowed(t(Ethics.STRATEGIC, setOf(RelationshipDomain.WORK_HIERARCHY)), calm, N, powerOn = true, support = false, powerLock = false))
    }
}
