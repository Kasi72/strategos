package com.drkkr.strategos

import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.fixtures.Fx
import com.drkkr.strategos.framework.MatrixSupport
import com.drkkr.strategos.framework.nash.PureNashModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MatrixPlayersTest {

    @Test
    fun TC_MATRIX_01_influencerIsNotAMatrixPlayer() {
        val r = MatrixSupport.buildChecked(Fx.pdWithInfluencer())
        assertNotNull(r)
        assertEquals(listOf("p1", "p2"), r!!.form.players)
        assertEquals(100, r.coveragePct)
    }

    @Test
    fun TC_MATRIX_02_nashWithInfluencerMatchesPlainPd() {
        @Suppress("UNCHECKED_CAST")
        fun eq(s: Scenario) = PureNashModule().analyze(s).calculations["equilibria"] as List<List<Int>>
        val withInfluencer = eq(Fx.pdWithInfluencer())
        assertEquals(1, withInfluencer.size)
        assertEquals(listOf(1, 1), withInfluencer.first())   // index 1 = Defect for both players
        assertEquals(eq(Fx.pd()), withInfluencer)
    }

    @Test
    fun TC_MATRIX_03_onePlayerTwoInfluencersReturnsNull() {
        val s = Fx.pd().let {
            it.copy(stakeholders = listOf(
                it.stakeholders[0],
                it.stakeholders[1].copy(gameRole = GameRole.INFLUENCER),
                Stakeholder(id = "m1", name = "Media", gameRole = GameRole.INFLUENCER)
            ))
        }
        assertEquals(1, s.players().size)
        assertNull(MatrixSupport.buildChecked(s))
    }
}
