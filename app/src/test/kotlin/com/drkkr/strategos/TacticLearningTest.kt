package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.ScenarioRepository
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.TrajectoryRepository
import com.drkkr.strategos.domain.agent.CoachGuideSketch
import com.drkkr.strategos.domain.agent.GuidanceCodec
import com.drkkr.strategos.domain.human.DeliveryPlanner
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.TacticId
import com.drkkr.strategos.domain.human.TacticLearning
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Human Layer, HL6.1: what worked with THIS person. Per tactic, a Beta(alpha, beta) that starts at (1, 1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TacticLearningTest {
    private lateinit var db: StrategosDatabase
    @Before fun setUp() { db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() { db.close() }

    private val DEF = TacticId.DEFAULT_WITH_DEADLINE.name

    @Test
    fun TC_HL_LEARN_01_aSoftenedReactionAddsToAlpha_anEscalationToBeta() {
        assertEquals(mapOf(DEF to listOf(2.0, 1.0)), TacticLearning.update(emptyMap(), listOf(DEF), "softened"))
        assertEquals(mapOf(DEF to listOf(1.0, 2.0)), TacticLearning.update(emptyMap(), listOf(DEF), "withdrew_or_escalated"))
        assertEquals(mapOf(DEF to listOf(3.0, 1.0)), TacticLearning.update(mapOf(DEF to listOf(2.0, 1.0)), listOf(DEF), "softened"))
        for (nothing in listOf("stayed_neutral", "none", null)) assertTrue(TacticLearning.update(emptyMap(), listOf(DEF), nothing).isEmpty())
        assertTrue(TacticLearning.update(emptyMap(), emptyList(), "softened").isEmpty())
        // only the tactics used change
        val two = TacticLearning.update(mapOf("OTHER" to listOf(5.0, 5.0)), listOf(DEF, TacticId.PUT_IT_IN_WRITING.name), "softened")
        assertEquals(setOf(DEF, TacticId.PUT_IT_IN_WRITING.name), two.keys)
    }

    @Test
    fun TC_HL_LEARN_02_theGainIsScaledByWhatWorkedBefore_clampedToHalfAndOneAndAHalf() {
        assertEquals(0.5, TacticLearning.multiplier(mapOf(DEF to listOf(1.0, 4.0)), DEF), 1e-9)       // mean 0.2 -> 0.4 -> clamped to 0.5
        assertEquals(1.5, TacticLearning.multiplier(mapOf(DEF to listOf(4.0, 1.0)), DEF), 1e-9)       // mean 0.8 -> 1.6 -> clamped to 1.5
        assertEquals(1.0, TacticLearning.multiplier(emptyMap(), DEF), 1e-9)                           // nothing learned: (1,1) is 0.5 -> 1.0
        assertEquals(1.2, TacticLearning.multiplier(mapOf(DEF to listOf(3.0, 2.0)), DEF), 1e-9)       // mean 0.6 -> 1.2
        assertEquals(0.5, TacticLearning.mean(mapOf(DEF to listOf(2.0, 2.0)), DEF), 1e-9)
    }

    private val LTV = PayoffDimension.LONG_TERM_VALUE; private val REL = PayoffDimension.RELATIONSHIPS; private val COST = PayoffDimension.COST
    private fun openEnded(learned: Map<String, List<Double>>): Scenario {
        var s = Fx.hlRaise()
        s = s.copy(payoffs = s.payoffs.map {
            if (it.stakeholderId == "mgr" && it.actionProfile["you"] == "a2") it.copy(payoff = MultiPayoff(mapOf(LTV to 5.0, REL to 5.0, COST to 5.0))) else it
        }, actions = s.actions.map { if (it.id == "a2") it.copy(description = "[posture:boundary][f:open_ended]") else it })
        return s.copy(humanContext = HumanContext(relationship = "manager", deadline = "none", tacticSuccess = learned))
    }
    private fun gain(s: Scenario) = DeliveryPlanner.candidates(s, s.humanContext!!, s.actions.first { it.id == "a2" }, false).single { it.first.id == TacticId.DEFAULT_WITH_DEADLINE }.second

    @Test
    fun TC_HL_LEARN_03_thePlannerMultipliesItsGainByWhatWorkedWithThisPerson() {
        val base = gain(openEnded(emptyMap()))
        assertTrue(base > 0.05)
        assertEquals(base * 0.5, gain(openEnded(mapOf(DEF to listOf(1.0, 4.0)))), 1e-9)               // ignores defaults: halved
        assertEquals(base * 1.5, gain(openEnded(mapOf(DEF to listOf(4.0, 1.0)))), 1e-9)               // responds to them: raised
        assertEquals(base, gain(openEnded(mapOf(TacticId.PUT_IT_IN_WRITING.name to listOf(1.0, 9.0)))), 1e-9)   // another tactic's record does not matter
    }

    @Test
    fun TC_HL_LEARN_04_theGuidancePayloadRemembersTheTacticsUsed_oldPayloadsStillDecode() {
        val g = GuidanceCodec.decode(GuidanceCodec.encode(CoachGuideSketch(doThis = listOf("a"), tactics = listOf(DEF))))!!
        assertEquals(listOf(DEF), g.tactics)
        val old = GuidanceCodec.decode("""{"what_happened":"x","clear_win_move":"y","exact_words":"z","do_this":["a"],"dont_do_this":[],"follow_up":"f","pill":"p"}""")!!
        assertTrue(old.tactics.isEmpty())
    }

    @Test
    fun TC_HL_LEARN_05_theContextKeepsWhatItLearned_andKeysOverwriteOnlyTheirOwn() {
        val saved = HumanContext(tacticSuccess = mapOf(DEF to listOf(2.0, 1.0), "B" to listOf(1.0, 3.0)))
        val merged = saved.merge(HumanContext(tacticSuccess = mapOf(DEF to listOf(3.0, 1.0)), learnedFromMessage = "m1"))
        assertEquals(mapOf(DEF to listOf(3.0, 1.0), "B" to listOf(1.0, 3.0)), merged.tacticSuccess)
        assertEquals("m1", merged.learnedFromMessage)
        assertEquals("m1", merged.merge(HumanContext(yourStateLevel = 1.0)).learnedFromMessage)
    }

    @Test
    fun TC_HL_LEARN_06_aQuickCheckUpdatesThePriorsOfTheTacticsInThatThreadsLastReply_once() = runBlocking {
        val scenarios = ScenarioRepository(db, null).also { it.load() }
        val saved = (scenarios.save(Fx.loanFamily().copy(humanContext = HumanContext(relationship = "sibling"))) as SaveResult.Success).saved
        val conversations = ConversationRepository(db)
        val thread = conversations.getOrCreateActive(saved.id, "m", "v")
        val reply = conversations.append(thread.id, "t1", MessageRole.ASSISTANT, "A reply", payloadJson = GuidanceCodec.encode(CoachGuideSketch(doThis = listOf("a"), tactics = listOf(DEF))))
        val repo = TrajectoryRepository(db, scenarios)

        repo.submitQuickCheck(saved.id, thread.id, "Pause", "DID_AS_SUGGESTED", "DE_ESCALATED", "GROUNDED")
        val after = scenarios.find(saved.id)!!.humanContext!!
        assertEquals(listOf(2.0, 1.0), after.tacticSuccess[DEF])
        assertEquals(reply.id, after.learnedFromMessage)
        // the same reply is not counted again by a second check
        repo.submitQuickCheck(saved.id, thread.id, "Pause", "DID_AS_SUGGESTED", "DE_ESCALATED", "GROUNDED")
        assertEquals(listOf(2.0, 1.0), scenarios.find(saved.id)!!.humanContext!!.tacticSuccess[DEF])
        // an escalation on a later reply adds to beta
        val later = conversations.append(thread.id, "t2", MessageRole.ASSISTANT, "Another", payloadJson = GuidanceCodec.encode(CoachGuideSketch(doThis = listOf("a"), tactics = listOf(DEF))))
        repo.submitQuickCheck(saved.id, thread.id, "Pause", "DID_AS_SUGGESTED", "ESCALATED_ATTACKED", "GROUNDED")
        assertEquals(listOf(2.0, 2.0), scenarios.find(saved.id)!!.humanContext!!.tacticSuccess[DEF])
        assertNotNull(later)
    }
}
