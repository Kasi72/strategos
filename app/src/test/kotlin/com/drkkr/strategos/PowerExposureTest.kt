package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.HumanVocab
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.human.power.Exposure
import com.drkkr.strategos.domain.human.power.PowerConstants
import com.drkkr.strategos.domain.human.power.StrategicCatalog
import com.drkkr.strategos.domain.human.power.StrategicId
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Power Mode, PM2.2: how likely a hardball move is to be seen through (q), and what it is worth after that (EV). */
class PowerExposureTest {
    private val c = PowerConstants.DEFAULT
    private fun q(s: Scenario): Double {
        val ctx = s.humanContext
        val them = IntakeChecklist.self(s)?.let { o -> s.players().first { it.id != o.id } }
        return Exposure.q(ctx, RelationshipDomains.of(ctx?.relationship, null), them, c)
    }
    private fun tactic(id: StrategicId) = StrategicCatalog.tactics.single { it.id == id }

    @Test fun TC_PM_EXP_01_vendorExposureIsFloorValue() = assertEquals(0.05, q(Fx.pmVendor()), 1e-9)

    @Test fun TC_PM_EXP_02_aCofounderContinues() = assertEquals(0.40, q(Fx.pmCofounder()), 1e-9)

    @Test fun TC_PM_EXP_03_aProbingManager() = assertEquals(0.60, q(Fx.pmManager(probes = true)), 1e-9)

    @Test fun TC_PM_EXP_04_qStaysWithinItsBounds() {
        val r = Random(7)
        val rels = HumanVocab.RELATIONSHIP.toList() + listOf<String?>(null)
        val domains = RelationshipDomain.values().filter { !it.isClose }
        repeat(2000) {
            val k = PowerConstants(
                qBase = domains.associateWith { r.nextDouble(-0.5, 1.5) }, qContinuing = r.nextDouble(-0.5, 1.0),
                qSuspicious = r.nextDouble(-0.5, 1.0), qNewCounterpart = r.nextDouble(-1.0, 0.5),
                qMin = r.nextDouble(0.0, 0.3), qMax = r.nextDouble(0.5, 1.0)
            )
            val ctx = HumanContext(relationship = rels[r.nextInt(rels.size)], theirTactics = if (r.nextBoolean()) listOf("probes_you") else emptyList())
            val them = Stakeholder(id = "t", name = "T", credibility = r.nextInt(0, 11))
            val v = Exposure.q(ctx, domains[r.nextInt(domains.size)], them, k)
            assertTrue("$v outside [${k.qMin}, ${k.qMax}]", v >= k.qMin - 1e-12 && v <= k.qMax + 1e-12)
        }
    }

    @Test fun TC_PM_EXP_05_holdYourNumberAtTheFloor() =
        assertEquals(0.95 * 0.8 - 0.05 * 0.2 * 1.5, Exposure.ev(tactic(StrategicId.HOLD_YOUR_NUMBER), 0.05, 50.0, c), 1e-9)

    @Test fun TC_PM_EXP_06_goToTheDeciderAndMoreTrustMeansMoreToLose() {
        assertEquals(0.1025, Exposure.ev(tactic(StrategicId.GO_TO_THE_DECIDER), 0.45, 50.0, c), 1e-9)
        assertEquals(-0.01, Exposure.ev(tactic(StrategicId.GO_TO_THE_DECIDER), 0.45, 100.0, c), 1e-9)
        assertEquals("trust defaults to 50", Exposure.ev(tactic(StrategicId.GO_TO_THE_DECIDER), 0.45, 50.0, c), Exposure.ev(tactic(StrategicId.GO_TO_THE_DECIDER), 0.45, null, c), 1e-12)
    }

    @Test fun lowCredibilityCountsAsSuspicion() {
        val vendor = HumanContext(relationship = "vendor")
        assertEquals(0.05, Exposure.q(vendor, RelationshipDomain.NEGOTIATION, Stakeholder(id = "t", name = "T"), c), 1e-9)
        assertEquals(0.20, Exposure.q(vendor, RelationshipDomain.NEGOTIATION, Stakeholder(id = "t", name = "T", credibility = 3), c), 1e-9)
        assertTrue("probes_you" in HumanVocab.POWER_THEIR_TACTICS)
    }
}
