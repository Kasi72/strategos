package com.drkkr.strategos

import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.negotiation.DealBlock
import com.drkkr.strategos.domain.negotiation.DealFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 1: anchor_target = target ± 15% buffer in the direction of higher/lower. */
class DealBlockAnchorTest {

    private fun nc(walk: String, target: String = "", theirLimit: String = "") =
        NegotiationContext(batna = "", reservationPoint = walk, aspirationPoint = target, counterpartBatna = "", counterpartReservation = theirLimit)

    private fun block(walk: String, target: String = "", theirLimit: String = ""): DealBlock {
        val frame = DealFrame.build(nc(walk, target, theirLimit), null, "") ?: error("no frame")
        return DealBlock.from(frame)
    }

    @Test fun TC_ANCHOR_01_salaryNegotiationHigherIsBetterApplies15PctAboveTarget() {
        // target = 100k, anchor should be 115k
        val b = block(walk = "80k", target = "100k")
        val anchor = b.anchorTarget ?: error("anchorTarget null")
        assertEquals(115_000.0, anchor.value, 0.01)
    }

    @Test fun TC_ANCHOR_02_buyerNegotiationLowerIsBetterApplies15PctBelowTarget() {
        // target = 90k (buyer wants to pay 90k, walk-away is 110k), anchor should be 76.5k
        val b = block(walk = "110k", target = "90k")
        val anchor = b.anchorTarget ?: error("anchorTarget null")
        assertEquals(76_500.0, anchor.value, 0.01)
    }

    @Test fun TC_ANCHOR_03_noTargetMeansNullAnchor() {
        // Only walk-away known, no target, direction cannot be inferred from figures
        // DealFrame.build returns null when direction cannot be determined without ctx/relationship
        // So we can only verify that when frame has no target, anchorTarget is null.
        val b = block(walk = "80k", target = "100k")
        // Build a block directly with a frame that has null target
        val frame = DealFrame.build(nc(walk = "80k", target = "100k"), null, "")!!
        val noTargetFrame = frame.copy(target = null)
        val noTargetBlock = DealBlock.from(noTargetFrame)
        assertNull(noTargetBlock.anchorTarget)
    }

    @Test fun TC_ANCHOR_04_anchorAppearsInJson() {
        val b = block(walk = "80k", target = "100k")
        val json = b.toJson()
        val anchorJson = json["anchor_target"] ?: error("anchor_target missing from JSON")
        assertEquals("115,000", anchorJson.toString().trim('"'))
    }
}
