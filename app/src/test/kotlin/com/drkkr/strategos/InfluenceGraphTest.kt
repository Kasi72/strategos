package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.InfluenceGraph
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.*
import org.junit.Test

class InfluenceGraphTest {

    @Test fun TC_INFLUENCE_01_oneNodePerStakeholderWithSelfMarked() {
        val g = InfluenceGraph.from(Fx.loanFamily())
        assertEquals(4, g.nodes.size)
        assertEquals("You", g.nodes.single { it.isSelf }.name)
    }

    @Test fun TC_INFLUENCE_02_everyoneElsePullsTowardYouWithLabels() {
        val g = InfluenceGraph.from(Fx.loanFamily())
        val you = g.nodes.single { it.isSelf }.id
        val byFrom = g.edges.filter { it.toId == you }.associateBy { e -> g.nodes.first { it.id == e.fromId }.name }
        assertEquals("wants their way", byFrom.getValue("Brother").label)   // the other player
        assertEquals("can block this", byFrom.getValue("Wife").label)       // influencer with veto
        assertEquals("can sway this", byFrom.getValue("Parents").label)     // plain influencer
    }

    @Test fun TC_INFLUENCE_03_weightTracksSwayWithAFloor() {
        val g = InfluenceGraph.from(Fx.loanFamily())
        val byFrom = g.edges.associateBy { e -> g.nodes.first { it.id == e.fromId }.name }
        assertEquals(0.6, byFrom.getValue("Brother").weight, 1e-9)   // influence 6
        assertEquals(0.4, byFrom.getValue("Parents").weight, 1e-9)   // influence 4
        assertEquals(0.2, byFrom.getValue("Wife").weight, 1e-9)      // no authority/influence -> floor
    }

    @Test fun TC_INFLUENCE_04_inferredPersonMakesNodeAndEdgeInferred() {
        val s = Fx.loanFamily().let { base ->
            base.copy(stakeholders = base.stakeholders.map {
                if (it.name == "Parents") it.copy(factStatus = FactStatus.AI_INFERRED) else it
            })
        }
        val g = InfluenceGraph.from(s)
        assertTrue(g.nodes.single { it.name == "Parents" }.inferred)
        val parentsId = g.nodes.single { it.name == "Parents" }.id
        assertTrue(g.edges.single { it.fromId == parentsId }.inferred)
    }

    @Test fun TC_INFLUENCE_05_declaredAllianceAddsOneEdgePerPair() {
        val s = Fx.loanFamily().let { base ->
            base.copy(stakeholders = base.stakeholders.map {
                if (it.name == "Wife") it.copy(coalitionsWith = listOf("you")) else it
            })
        }
        val g = InfluenceGraph.from(s)
        assertEquals(1, g.edges.count { it.label == "allied" })
    }
}
