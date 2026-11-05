package com.drkkr.strategos

import com.drkkr.strategos.agent.ChatItem
import com.drkkr.strategos.agent.MessageMapper
import com.drkkr.strategos.data.db.entities.MessageEntity
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.human.power.PowerPlanner
import com.drkkr.strategos.domain.human.power.PowerRead
import com.drkkr.strategos.domain.human.power.PowerReadCodec
import com.drkkr.strategos.domain.human.power.StrategicId
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Power Mode, PM4.3: the Kotlin-built Power Read card payload. */
class PowerReadTest {
    private fun read(s: Scenario): PowerRead {
        val d = RelationshipDomains.of(s.humanContext?.relationship, null)
        return PowerRead.build(s, PowerPlanner.plan(s, d, true, false, false, 50.0, 0.0))
    }

    @Test fun TC_PM_READ_01_roundTripAndEmptyObjectDecode() {
        val r = read(Fx.pmVendor())
        assertEquals(r, PowerReadCodec.decode(PowerReadCodec.encode(r)))
        assertEquals(PowerRead(), PowerReadCodec.decode("{}"))
        assertEquals(PowerRead(), PowerReadCodec.decode("not json"))
    }

    @Test fun TC_PM_READ_02_theVendorsLeverageMap() {
        val line = read(Fx.pmVendor()).leverage.summary()
        assertTrue(line, line.contains("Your fallback: strong"))
        assertTrue(line, line.contains("Theirs: weak"))
    }

    @Test fun TC_PM_READ_03_aTrueMoveLosesLittleIfSeen() {
        assertEquals("little lost — it's true", PowerRead.ifExposed(StrategicId.REAL_COMPETITION))
        assertEquals("little lost — it's true", PowerRead.ifExposed(StrategicId.CREDIBLE_WALKAWAY))
        for (id in StrategicId.values()) assertTrue(id.name, PowerRead.ifExposed(id).isNotBlank())
    }

    @Test fun theCardNamesTheLawAndTheEv() {
        val r = read(Fx.pmVendor())
        assertEquals(listOf("Real competition", "Credible walk-away", "Hold your number"), r.yourMoves.map { it.name })
        assertEquals("L20", r.yourMoves.first().lawLabel)
        assertEquals("L3/L4", r.yourMoves.last().lawLabel)
        assertEquals(1.1175, r.yourMoves.first().ev, 1e-9)
        // a spouse: no moves, the close-relationship note
        val spouse = read(Fx.pmSpouse())
        assertTrue(spouse.yourMoves.isEmpty())
        assertEquals(PowerPlanner.CLOSE_DOMAIN_NOTE, spouse.closeDomainNote)
    }

    @Test fun theirPlaysComeFromTheRadarWithTheLaw() {
        val s = Fx.pmManager(probes = true)
        val plays = read(s).theirPlays
        assertEquals(1, plays.size)
        assertEquals("They're digging for your limits.", plays.single().sign)
        assertEquals("L14", plays.single().lawLabel)
    }

    @Test fun theDeciderIsTheHighestAuthority_orTheApproverTheyName() {
        val s = Fx.pmVendor()
        assertEquals("Manager", read(s).leverage.decides)          // the raise fixture's other side has authority 8
        val named = s.copy(stakeholders = s.stakeholders.map { if (it.id == "mgr") it.copy(approvalRole = "Needs Priya's director to sign") else it })
        assertEquals("Needs Priya's director to sign", read(named).leverage.decides)
    }

    @Test fun TC_PM_READ_05_aPowerReadRowBecomesAPowerReadItem() {
        val payload = PowerReadCodec.encode(read(Fx.pmVendor()))
        val msgs = listOf(
            MessageEntity("u", "c", 1, "t", MessageRole.USER.name, "hi", createdAt = 1),
            MessageEntity("p", "c", 2, "t", MessageRole.TOOL_RESULT.name, "Power Read", toolName = "power_read", payloadJson = payload, uiKind = UiKind.POWER_READ.name, createdAt = 2),
            MessageEntity("a", "c", 3, "t", MessageRole.ASSISTANT.name, "reply", createdAt = 3)
        )
        val item = MessageMapper.toChatItems(msgs, emptyList(), null).filterIsInstance<ChatItem.PowerRead>().single()
        assertEquals(PowerReadCodec.decode(payload), item.payload)
        assertFalse(item.payload.yourMoves.isEmpty())
    }
}
