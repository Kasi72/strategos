package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.DimValue
import com.drkkr.strategos.domain.agent.OpConstraintKind
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpSource
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.agent.ProfilePick
import com.drkkr.strategos.domain.agent.ScenarioFieldsPatch
import com.drkkr.strategos.domain.model.ConstraintAssessment
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSetApplierTest {

    private val userTexts = listOf("My regulator can shut us down overnight, honestly.")

    private fun acceptAll(ops: List<ChangeOp>) = ops.indices.map { OpDecision(it, Decision.ACCEPT) }

    /** Validate then apply with the given decisions (default: accept all). */
    private fun run(base: Scenario, ops: List<ChangeOp>, decisions: List<OpDecision> = acceptAll(ops), texts: List<String> = userTexts) =
        ChangeSetApplier.apply(base, ops, decisions, ChangeSetValidator.validate(base, ops).refIds, texts, "test")

    private val addReg = ChangeOp(OpType.ADD_STAKEHOLDER, ref = "reg", name = "Regulator", rationale = "They license the site", source = OpSource.AI_INFERRED)

    @Test
    fun TC_APPLY_01_aiInferredDefaults() {
        val r = run(Fx.pd(), listOf(addReg))
        val reg = r.scenario.stakeholders.single { it.name == "Regulator" }
        assertEquals(FactStatus.AI_INFERRED, reg.factStatus)
        assertEquals("They license the site", reg.provenanceNote)
        assertEquals(GameRole.INFLUENCER, reg.gameRole)
        assertEquals(1, r.applied)
    }

    @Test
    fun TC_APPLY_02_verifiedQuoteBecomesUserEstimate() {
        val op = addReg.copy(source = OpSource.USER_STATED, quote = "regulator can shut us down")
        val reg = run(Fx.pd(), listOf(op)).scenario.stakeholders.single { it.name == "Regulator" }
        assertEquals(FactStatus.USER_ESTIMATE, reg.factStatus)
        assertTrue(reg.provenanceNote.contains("regulator can shut us down"))
    }

    @Test
    fun TC_APPLY_03_unverifiedQuoteStaysAiInferred() {
        val op = addReg.copy(source = OpSource.USER_STATED, quote = "the regulator loves us")
        val reg = run(Fx.pd(), listOf(op)).scenario.stakeholders.single { it.name == "Regulator" }
        assertEquals(FactStatus.AI_INFERRED, reg.factStatus)
        assertFalse(reg.provenanceNote.contains("loves us"))
    }

    @Test
    fun TC_APPLY_04_editUsesEditedValues() {
        val edited = addReg.copy(name = "State regulator", canVeto = true)
        val r = run(Fx.pd(), listOf(addReg), listOf(OpDecision(0, Decision.EDIT, edited)))
        val reg = r.scenario.stakeholders.single { it.id !in setOf("p1", "p2") }
        assertEquals("State regulator", reg.name)
        assertTrue(reg.canVeto)
        assertEquals(FactStatus.USER_ESTIMATE, reg.factStatus)
    }

    @Test
    fun TC_APPLY_05_aiUpdateDowngradesConfirmed() {
        val base = Fx.pd().let { s -> s.copy(stakeholders = s.stakeholders.map { if (it.id == "p2") it.copy(factStatus = FactStatus.CONFIRMED) else it }) }
        val op = ChangeOp(OpType.UPDATE_STAKEHOLDER, targetId = "p2", canExit = true, rationale = "They can walk away")
        val p2 = run(base, listOf(op)).scenario.stakeholders.single { it.id == "p2" }
        assertEquals(FactStatus.AI_INFERRED, p2.factStatus)
        assertTrue(p2.canExit)
    }

    @Test
    fun TC_APPLY_06_removeStakeholderCascades() {
        val base = Fx.pd().let { s ->
            s.copy(
                stakeholders = s.stakeholders.map { if (it.id == "p1") it.copy(coalitionsWith = listOf("p2"), trustRelationships = listOf("p2")) else it },
                constraintAssessments = listOf(ConstraintAssessment(constraintId = "k", actionId = "d2"), ConstraintAssessment(constraintId = "k", actionId = "c1"))
            )
        }
        val s = run(base, listOf(ChangeOp(OpType.REMOVE_STAKEHOLDER, targetId = "p2", rationale = "Not involved"))).scenario
        assertEquals(listOf("p1"), s.stakeholders.map { it.id })
        assertEquals(setOf("c1", "d1"), s.actions.map { it.id }.toSet())
        assertTrue(s.payoffs.none { it.stakeholderId == "p2" || "p2" in it.actionProfile.keys })
        assertTrue(s.stakeholders.single().coalitionsWith.isEmpty())
        assertTrue(s.stakeholders.single().trustRelationships.isEmpty())
        assertEquals(listOf("c1"), s.constraintAssessments.map { it.actionId })
    }

    @Test
    fun TC_APPLY_07_payoffUpsert() {
        val base = Fx.loanFamily()
        val op = ChangeOp(
            OpType.SET_PAYOFF, stakeholder = "you",
            profile = listOf(ProfilePick("you", "wait"), ProfilePick("bro", "refuse")),
            values = listOf(DimValue(PayoffDimension.WELLBEING, 9.0), DimValue(PayoffDimension.RELATIONSHIPS, 1.0), DimValue(PayoffDimension.COST, 0.0)),
            rationale = "Waiting costs nothing"
        )
        val s = run(base, listOf(op)).scenario
        assertEquals(base.payoffs.size, s.payoffs.size)
        val entry = s.payoffs.single { it.stakeholderId == "you" && it.actionProfile == mapOf("you" to "wait", "bro" to "refuse") }
        assertEquals(9.0, entry.payoff.values.getValue(PayoffDimension.WELLBEING), 0.0)
        assertEquals(FactStatus.AI_INFERRED, entry.factStatus)
    }

    @Test
    fun TC_APPLY_08_dependsOnSkipped() {
        val action = ChangeOp(OpType.ADD_ACTION, ref = "inspect", stakeholder = "reg", label = "Inspect", rationale = "r")
        val r = run(Fx.pd(), listOf(addReg, action), listOf(OpDecision(0, Decision.REJECT), OpDecision(1, Decision.ACCEPT)))
        assertEquals(0, r.applied)
        assertTrue(r.skipped.contains(1 to "depends_on_skipped"))
        assertEquals(4, r.scenario.actions.size)
    }

    @Test
    fun TC_APPLY_09_baseMovedSinceProposal() {
        val proposedOn = Fx.pd()
        val ops = listOf(
            ChangeOp(OpType.UPDATE_ACTION, targetId = "d2", label = "Betray", rationale = "r"),
            ChangeOp(OpType.UPDATE_ACTION, targetId = "c1", label = "Cooperate fully", rationale = "r")
        )
        val refIds = ChangeSetValidator.validate(proposedOn, ops).refIds
        val moved = proposedOn.copy(actions = proposedOn.actions.filterNot { it.id == "d2" })
        val r = ChangeSetApplier.apply(moved, ops, acceptAll(ops), refIds, userTexts, "test")
        assertEquals(1, r.applied)
        assertEquals(listOf(0 to "unknown_target"), r.skipped)
        assertEquals("Cooperate fully", r.scenario.actions.single { it.id == "c1" }.label)
    }

    @Test
    fun TC_APPLY_10_oneChangeRecord() {
        val base = Fx.pd()
        val r = run(base, listOf(addReg, ChangeOp(OpType.SET_SCENARIO_FIELDS, scenarioFields = ScenarioFieldsPatch(stakes = "Jobs"), rationale = "r")))
        val added = r.scenario.changeHistory - base.changeHistory.toSet()
        assertEquals(1, added.size)
        assertEquals("strategos-counsel", added.single().changedBy)
        assertEquals(base.version, added.single().previousVersion)
        assertEquals("Applied AI change set: test (2 of 2)", added.single().summary)
        assertEquals("Jobs", r.scenario.stakes)
    }

    @Test
    fun TC_APPLY_11_neverConfirmed() {
        val base = Fx.loanFamily()
        val payoffOp = ChangeOp(
            OpType.SET_PAYOFF, stakeholder = "bro", profile = listOf(ProfilePick("you", "give"), ProfilePick("bro", "accept")),
            values = listOf(DimValue(PayoffDimension.WELLBEING, 5.0), DimValue(PayoffDimension.RELATIONSHIPS, 5.0), DimValue(PayoffDimension.COST, 5.0)),
            rationale = "r"
        )
        val variants = listOf(
            ChangeOp(OpType.ADD_STAKEHOLDER, ref = "x1", name = "Bank", rationale = "r"),
            ChangeOp(OpType.UPDATE_STAKEHOLDER, targetId = "wife", influence = 9, rationale = "r"),
            ChangeOp(OpType.ADD_ACTION, ref = "x2", stakeholder = "you", label = "Lend", rationale = "r"),
            ChangeOp(OpType.UPDATE_ACTION, targetId = "wait", reversible = false, rationale = "r"),
            payoffOp,
            ChangeOp(OpType.ADD_CONSTRAINT, ref = "x3", label = "No debt", constraintKind = OpConstraintKind.RESOURCE, rationale = "r"),
            ChangeOp(OpType.ADD_BELIEF, ref = "x4", label = "Brother repays", probability = 0.3, rationale = "r"),
            ChangeOp(OpType.ADD_ASSUMPTION, ref = "x5", label = "Income is stable", rationale = "r")
        )
        val confirmedBase = base.copy(
            stakeholders = base.stakeholders.map { it.copy(factStatus = FactStatus.CONFIRMED) },
            actions = base.actions.map { it.copy(factStatus = FactStatus.CONFIRMED) },
            payoffs = base.payoffs.map { it.copy(factStatus = FactStatus.CONFIRMED) }
        )
        val texts = listOf("Income is stable and my brother repays sometimes")
        for (op in variants) for (source in listOf(null, OpSource.AI_INFERRED, OpSource.USER_STATED)) for (d in Decision.entries) {
            val o = op.copy(source = source, quote = if (source == OpSource.USER_STATED) "Income is stable" else null)
            val decision = OpDecision(0, d, if (d == Decision.EDIT) o else null)
            val r = run(confirmedBase, listOf(o), listOf(decision), texts)
            if (d == Decision.REJECT) {
                assertEquals(0, r.applied)
                continue
            }
            val s = r.scenario
            val touched = (s.stakeholders.map { it.id to it.factStatus } + s.actions.map { it.id to it.factStatus })
                .filter { (id, _) -> id !in confirmedBase.stakeholders.map { it.id } + confirmedBase.actions.map { it.id } ||
                    id == o.targetId }
            val newPayoffs = s.payoffs - confirmedBase.payoffs.toSet()
            val all = touched.map { it.second } + newPayoffs.map { it.factStatus } +
                (s.constraints - confirmedBase.constraints.toSet()).map { it.factStatus } +
                (s.beliefs - confirmedBase.beliefs.toSet()).map { it.status } +
                (s.assumptionLog - confirmedBase.assumptionLog.toSet()).map { it.status }
            assertTrue("$d ${o.type} $source produced CONFIRMED", FactStatus.CONFIRMED !in all)
            assertEquals("$d ${o.type} $source", 1, r.applied)
        }
    }

    @Test
    fun refsResolveToProposedIds() {
        val ops = listOf(addReg, ChangeOp(OpType.ADD_ACTION, ref = "inspect", stakeholder = "reg", label = "Inspect", rationale = "r"))
        val refIds = ChangeSetValidator.validate(Fx.pd(), ops).refIds
        val s = ChangeSetApplier.apply(Fx.pd(), ops, acceptAll(ops), refIds, userTexts, "t").scenario
        assertEquals(refIds["reg"], s.actions.single { it.id == refIds["inspect"] }.stakeholderId)
        assertNotEquals(refIds["reg"], refIds["inspect"])
    }
}
