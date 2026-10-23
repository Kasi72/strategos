package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.DimValue
import com.drkkr.strategos.domain.agent.OpConstraintKind
import com.drkkr.strategos.domain.agent.OpSource
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.agent.ProfilePick
import com.drkkr.strategos.domain.agent.ScenarioFieldsPatch
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.LegalStatus
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.domain.model.StakeholderType
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

class ChangeSetValidatorTest {

    private fun op(type: OpType, rationale: String? = "because", block: ChangeOp.() -> ChangeOp = { this }) =
        ChangeOp(type, rationale = rationale).block()

    private fun reasons(s: Scenario, vararg ops: ChangeOp) =
        ChangeSetValidator.validate(s, ops.toList()).rejected.associate { it.index to it.reason }

    private val pdProfile = listOf(ProfilePick("p1", "c1"), ProfilePick("p2", "c2"))

    @Test
    fun TC_VAL_01_addWithoutRefRejected() {
        val r = reasons(Fx.pd(), op(OpType.ADD_STAKEHOLDER) { copy(name = "Regulator") })
        assertEquals("ref_required", r[0])
    }

    @Test
    fun TC_VAL_02_duplicateRefRejected() {
        val a = op(OpType.ADD_STAKEHOLDER) { copy(ref = "reg", name = "Regulator") }
        val r = reasons(Fx.pd(), a, a.copy(name = "Other"))
        assertEquals(null, r[0])
        assertEquals("bad_ref", r[1])
    }

    @Test
    fun TC_VAL_03_forwardRefRejected() {
        val r = reasons(
            Fx.pd(),
            op(OpType.ADD_ACTION) { copy(ref = "inspect", stakeholder = "reg", label = "Inspect") },
            op(OpType.ADD_STAKEHOLDER) { copy(ref = "reg", name = "Regulator") }
        )
        assertEquals("unknown_stakeholder", r[0])
        assertEquals(null, r[1])
    }

    @Test
    fun TC_VAL_04_refUsedLaterInBatch() {
        val v = ChangeSetValidator.validate(
            Fx.pd(), listOf(
                op(OpType.ADD_STAKEHOLDER) { copy(ref = "reg", name = "Regulator") },
                op(OpType.ADD_ACTION) { copy(ref = "inspect", stakeholder = "reg", label = "Inspect") }
            )
        )
        assertEquals(2, v.accepted.size)
        assertTrue(v.rejected.isEmpty())
        UUID.fromString(v.refIds.getValue("reg"))
    }

    @Test
    fun TC_VAL_05_profileMissingPlayer() {
        val r = reasons(Fx.pd(), op(OpType.SET_PAYOFF) {
            copy(stakeholder = "p1", profile = listOf(ProfilePick("p1", "c1")),
                values = listOf(DimValue(PayoffDimension.LONG_TERM_VALUE, 4.0)))
        })
        assertEquals("bad_profile", r[0])
    }

    @Test
    fun TC_VAL_06_payoffForInfluencer() {
        val r = reasons(Fx.pdWithInfluencer(), op(OpType.SET_PAYOFF) {
            copy(stakeholder = "m1", profile = pdProfile, values = listOf(DimValue(PayoffDimension.LONG_TERM_VALUE, 4.0)))
        })
        assertEquals("payoff_for_non_player", r[0])
    }

    @Test
    fun TC_VAL_07_dimensionMismatch() {
        val r = reasons(Fx.pd(), op(OpType.SET_PAYOFF) {
            copy(stakeholder = "p1", profile = pdProfile,
                values = listOf(DimValue(PayoffDimension.SAFETY, 4.0), DimValue(PayoffDimension.COST, 2.0)))
        })
        assertEquals("dimension_mismatch:[LONG_TERM_VALUE]", r[0])
    }

    @Test
    fun TC_VAL_07b_newBatchNeedsThreeToSixDimensions() {
        val s = Fx.pd().copy(payoffs = emptyList())
        val two = op(OpType.SET_PAYOFF) {
            copy(stakeholder = "p1", profile = pdProfile,
                values = listOf(DimValue(PayoffDimension.SAFETY, 4.0), DimValue(PayoffDimension.COST, 2.0)))
        }
        val three = two.copy(values = two.values!! + DimValue(PayoffDimension.WELLBEING, 5.0))
        assertTrue(reasons(s, two)[0]!!.startsWith("dimension_mismatch"))
        assertTrue(reasons(s, three).isEmpty())
        // The first accepted set_payoff fixes the set for the rest of the batch.
        val other = three.copy(stakeholder = "p2", values = listOf(
            DimValue(PayoffDimension.SAFETY, 1.0), DimValue(PayoffDimension.COST, 1.0), DimValue(PayoffDimension.ETHICS, 1.0)))
        assertEquals("dimension_mismatch:[COST,SAFETY,WELLBEING]", reasons(s, three, other)[1])
    }

    @Test
    fun TC_VAL_08_outOfRange() {
        val r = reasons(Fx.pd(), op(OpType.UPDATE_STAKEHOLDER) { copy(targetId = "p1", authority = 11) })
        assertEquals("out_of_range:authority", r[0])
    }

    @Test
    fun TC_VAL_09_userStatedNeedsQuote() {
        val r = reasons(Fx.pd(), op(OpType.UPDATE_STAKEHOLDER) { copy(targetId = "p1", canVeto = true, source = OpSource.USER_STATED) })
        assertEquals("quote_required", r[0])
    }

    @Test
    fun TC_VAL_10_tooManyOps() {
        val ops = (0 until 41).map { op(OpType.ADD_STAKEHOLDER) { copy(ref = "s$it", name = "S$it") } }
        val v = ChangeSetValidator.validate(Fx.pd(), ops)
        assertTrue(v.accepted.isEmpty())
        assertEquals(41, v.rejected.size)
        assertTrue(v.rejected.all { it.reason == "too_many_ops" })
    }

    @Test
    fun TC_VAL_11_blankRationale() {
        val r = reasons(Fx.pd(), op(OpType.UPDATE_STAKEHOLDER, rationale = "  ") { copy(targetId = "p1") })
        assertEquals("rationale_required", r[0])
    }

    @Test
    fun TC_VAL_12_matrixTooLarge() {
        val players = (1..4).map { Stakeholder(id = "s$it", name = "S$it") }
        val actions = players.flatMap { p -> (1..3).map { Action(id = "${p.id}a$it", stakeholderId = p.id, label = "A$it") } }
        val s = Scenario(title = "Big", stakeholders = players, actions = actions)
        val r = reasons(s, op(OpType.SET_PAYOFF) {
            copy(stakeholder = "s1", profile = players.map { ProfilePick(it.id, "${it.id}a1") },
                values = listOf(DimValue(PayoffDimension.SAFETY, 1.0), DimValue(PayoffDimension.COST, 1.0), DimValue(PayoffDimension.ETHICS, 1.0)))
        })
        assertEquals("matrix_too_large", r[0])
    }

    @Test
    fun TC_VAL_13_neverThrowsOnRandomOps() {
        val rnd = Random(13)
        val ids = listOf("p1", "p2", "c1", "d1", "c2", "d2", "m1", "r1", "r2", "nope", "")
        fun <T> maybe(v: () -> T): T? = if (rnd.nextBoolean()) v() else null
        fun id() = ids.random(rnd)
        val ops = (0 until 200).map {
            ChangeOp(
                type = OpType.entries.random(rnd),
                ref = maybe { listOf("r1", "r2", "BAD REF", "x", "ok_ref").random(rnd) },
                targetId = maybe(::id), stakeholder = maybe(::id),
                name = maybe { "N".repeat(rnd.nextInt(0, 100)) }, label = maybe { "L".repeat(rnd.nextInt(0, 140)) },
                stakeholderType = maybe { StakeholderType.entries.random(rnd) },
                gameRole = maybe { GameRole.entries.random(rnd) },
                authority = maybe { rnd.nextInt(-2, 13) }, influence = maybe { rnd.nextInt(-2, 13) },
                difficulty = maybe { rnd.nextInt(-1, 7) }, timeCostDays = maybe { rnd.nextInt(-5, 5) },
                legalStatus = maybe { LegalStatus.entries.random(rnd) },
                goals = maybe { List(rnd.nextInt(0, 8)) { "g" } },
                profile = maybe { List(rnd.nextInt(0, 4)) { ProfilePick(id(), id()) } },
                values = maybe { List(rnd.nextInt(0, 8)) { DimValue(PayoffDimension.entries.random(rnd), rnd.nextDouble(-3.0, 13.0)) } },
                constraintKind = maybe { OpConstraintKind.entries.random(rnd) },
                probability = maybe { rnd.nextDouble(-0.5, 1.5) },
                scenarioFields = maybe { ScenarioFieldsPatch(title = "t") },
                rationale = maybe { listOf("", "why", "x".repeat(600)).random(rnd) },
                source = maybe { OpSource.entries.random(rnd) }, quote = maybe { "q" }
            )
        }
        for (batch in ops.chunked(10)) {
            val v = ChangeSetValidator.validate(Fx.pdWithInfluencer(), batch)
            assertEquals(batch.size, v.accepted.size + v.rejected.size)
        }
        val all = ChangeSetValidator.validate(Fx.pdWithInfluencer(), ops)
        assertTrue(all.rejected.all { it.reason == "too_many_ops" })
    }

    @Test
    fun constraintNeedsLabelAndKind() {
        val r = reasons(Fx.pd(), op(OpType.ADD_CONSTRAINT) { copy(ref = "law", label = "Law") })
        assertEquals("constraint_incomplete", r[0])
    }

    @Test
    fun beliefNeedsProbability() {
        val r = reasons(Fx.pd(), op(OpType.ADD_BELIEF) { copy(ref = "b1", label = "P2 defects") })
        assertEquals("probability_required", r[0])
    }

    @Test
    fun unknownTargetAndStakeholder() {
        val r = reasons(
            Fx.pd(),
            op(OpType.REMOVE_ACTION) { copy(targetId = "zz") },
            op(OpType.ADD_ACTION) { copy(ref = "a1", stakeholder = "zz", label = "X") },
            op(OpType.UPDATE_STAKEHOLDER) { copy(targetId = "c1") }
        )
        assertEquals(mapOf(0 to "unknown_target", 1 to "unknown_stakeholder", 2 to "unknown_target"), r)
    }

    @Test
    fun nameRequired() {
        val r = reasons(Fx.pd(), op(OpType.ADD_STAKEHOLDER) { copy(ref = "a1", name = "x".repeat(81)) })
        assertEquals("name_required", r[0])
    }

    @Test
    fun labelRequired() {
        val r = reasons(Fx.pd(), op(OpType.ADD_ACTION) { copy(ref = "a1", stakeholder = "p1") })
        assertEquals("label_required", r[0])
    }

    @Test
    fun badRefFormat() {
        val r = reasons(Fx.pd(), op(OpType.ADD_STAKEHOLDER) { copy(ref = "Bad Ref", name = "X") })
        assertEquals("bad_ref", r[0])
    }

    @Test
    fun fourthPlayerRejected() {
        val three = Fx.pd().let { it.copy(stakeholders = it.stakeholders + Stakeholder(id = "p3", name = "P3")) }
        val r = reasons(three,
            op(OpType.ADD_STAKEHOLDER) { copy(ref = "p4", name = "P4", gameRole = GameRole.PLAYER) },
            op(OpType.ADD_STAKEHOLDER) { copy(ref = "m2", name = "Media 2") },                       // defaults to INFLUENCER: fine
            op(OpType.UPDATE_STAKEHOLDER) { copy(targetId = "m2", gameRole = GameRole.PLAYER) }      // promoting to PLAYER: also rejected
        )
        assertEquals(mapOf(0 to "too_many_players", 2 to "too_many_players"), r)
        val updateExisting = reasons(Fx.pdWithInfluencer().copy(stakeholders = three.stakeholders + Fx.pdWithInfluencer().stakeholders.last()),
            op(OpType.UPDATE_STAKEHOLDER) { copy(targetId = "m1", gameRole = GameRole.PLAYER) })
        assertEquals("too_many_players", updateExisting[0])
    }
}
