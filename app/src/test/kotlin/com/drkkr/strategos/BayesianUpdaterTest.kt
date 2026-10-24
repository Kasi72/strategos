package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.*
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.model.Scenario
import org.junit.Assert.*
import org.junit.Test

/** Phase F.6: the learned Bayesian priors, their nudges, and their effect on which option the engine recommends. */
class BayesianUpdaterTest {

    // ── pure math ──────────────────────────────────────────────────────────
    @Test fun repairThatSoftensNudgesSoftensUp() {
        val (softens, boundary, escalates) = BayesianUpdater.update(0.60, 0.55, 0.75, "repair", "softened")
        assertEquals(0.68, softens, 1e-9); assertEquals(0.55, boundary, 1e-9); assertEquals(0.75, escalates, 1e-9)
    }

    @Test fun repairThatIsIgnoredNudgesSoftensDownAndClampsAtTheFloor() {
        var (softens, boundary, escalates) = Triple(0.20, 0.55, 0.75)
        repeat(3) { val r = BayesianUpdater.update(softens, boundary, escalates, "repair", "withdrew_or_escalated"); softens = r.first; boundary = r.second; escalates = r.third }
        assertEquals(0.15, softens, 1e-9)           // floor, not negative
    }

    @Test fun softensNeverExceedsItsCeiling() {
        var softens = 0.85
        repeat(5) { softens = BayesianUpdater.update(softens, 0.55, 0.75, "repair", "softened").first }
        assertEquals(0.90, softens, 1e-9)
    }

    @Test fun boundaryRespectedNudgesBoundaryUpNotSoftens() {
        val (softens, boundary, _) = BayesianUpdater.update(0.60, 0.55, 0.75, "boundary", "softened")
        assertEquals(0.60, softens, 1e-9); assertEquals(0.63, boundary, 1e-9)
    }

    @Test fun reactiveThatEscalatesNudgesEscalatesUpAndClampsAtItsOwnCeiling() {
        var escalates = 0.90
        repeat(3) { escalates = BayesianUpdater.update(0.60, 0.55, escalates, "reactive", "withdrew_or_escalated").third }
        assertEquals(0.95, escalates, 1e-9)
    }

    @Test fun noneOrUnknownValuesLeaveThePriorsUnchanged() {
        assertEquals(Triple(0.60, 0.55, 0.75), BayesianUpdater.update(0.60, 0.55, 0.75, "none", "softened"))
        assertEquals(Triple(0.60, 0.55, 0.75), BayesianUpdater.update(0.60, 0.55, 0.75, "repair", "none"))
        assertEquals(Triple(0.60, 0.55, 0.75), BayesianUpdater.update(0.60, 0.55, 0.75, null, null))
        assertEquals(Triple(0.60, 0.55, 0.75), BayesianUpdater.update(0.60, 0.55, 0.75, "repair", "stayed_neutral"))
    }

    // ── applyToScenario / learningNote ───────────────────────────────────────
    private fun twoPlayerScenario(): Scenario {
        val base = Scenario(title = "t", description = "A relationship")
        val json = """{"people":[{"name":"You","role":"player"},{"name":"Husband","role":"player"}],
            "options":[{"who":"You","do":"Say it gently","posture":"repair"},{"who":"Husband","do":"Open up","posture":"receptive"}],
            "dims":["WELLBEING"],"outcomes":[{"when":["Say it gently","Open up"],"for_you":[6],"for_them":[6]}]}"""
        val ops = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(json)!!, base).ops, base)
        val v = ChangeSetValidator.validate(base, ops)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(base, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    @Test fun applyToScenarioStoresTheNewPriorsOnTheCounterpartOnly() {
        val s = twoPlayerScenario()
        val updated = BayesianUpdater.applyToScenario(s, "repair", "withdrew_or_escalated")
        val husband = updated.stakeholders.first { it.name == "Husband" }
        val you = updated.stakeholders.first { it.name == "You" }
        assertEquals(0.50, husband.probSoftensOnRepair!!, 1e-9)
        assertNull(you.probSoftensOnRepair)                       // never written onto the user's own record
    }

    @Test fun applyToScenarioIsANoOpWithoutASecondPlayer() {
        val s = Scenario(title = "t").copy(stakeholders = emptyList())
        assertSame(s, BayesianUpdater.applyToScenario(s, "repair", "softened"))
    }

    @Test fun learningNoteIsNullUntilSomethingHasMovedAndNamesTheStrongestPattern() {
        val s = twoPlayerScenario()
        assertNull(BayesianUpdater.learningNote(s))
        val lowered = s.copy(stakeholders = s.stakeholders.map { if (it.name == "Husband") it.copy(probSoftensOnRepair = 0.30) else it })
        assertTrue(BayesianUpdater.learningNote(lowered)!!.contains("has not been softening"))
        val raised = s.copy(stakeholders = s.stakeholders.map { if (it.name == "Husband") it.copy(probSoftensOnRepair = 0.80) else it })
        assertTrue(BayesianUpdater.learningNote(raised)!!.contains("softens when reassured"))
    }

    // ── end to end: the learned prior actually moves the engine's pick ───────
    private val calm = "Tell him what you see and ask what would help"
    private val firm = "Hold your line and pause for a day"

    private fun tiedCalmVsFirmSketch(): String {
        fun opt(who: String, label: String, posture: String) = """{"who":"$who","do":"$label","posture":"$posture"}"""
        fun row(a: String, b: String) = """{"when":["$a","$b"],"for_you":[6,6,6],"for_them":[6,6,6]}"""
        return """{"people":[{"name":"You","role":"player"},{"name":"Husband","role":"player"}],
            "options":[${opt("You", calm, "repair")},${opt("You", firm, "boundary")},${opt("Husband", "Open up", "receptive")},${opt("Husband", "Shut down", "defensive")}],
            "dims":["WELLBEING","RELATIONSHIPS","LONG_TERM_VALUE"],
            "outcomes":[${row(calm, "Open up")},${row(calm, "Shut down")},${row(firm, "Open up")},${row(firm, "Shut down")}]}"""
    }

    private fun build(json: String, base: Scenario = Scenario(title = "t", description = "Reconnect with my husband")): Scenario {
        val ops = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(json)!!, base).ops, base)
        val v = ChangeSetValidator.validate(base, ops)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(base, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    private fun yourScoreFor(s: Scenario, label: String): Double {
        val you = s.stakeholders.first { it.name == "You" }.id
        val actionId = s.actions.first { it.label == label }.id
        return s.payoffs.first { it.stakeholderId == you && it.actionProfile[you] == actionId }.payoff.values.values.first()
    }

    /**
     * "calm" (repair) and "firm" (boundary) start tied in the model's own raw numbers. At the default priors the
     * repair bonus (+0.4) already edges out the boundary bonus (+0.2), so "calm" scores higher than "firm" — this
     * is the mechanism Part B §6 asks for, read straight from the saved payoffs rather than the full multi-framework
     * pathway ranking (which weighs other things besides this one score and is out of scope for this unit test).
     */
    // The adjustment is spread over the 3 dims (not applied to each in full), so the overall swing is ~d, not 3*d.
    private fun expected(prior: Double) = 6.0 + (prior - 0.50) * 4.0 / 3.0

    @Test fun threeConsecutiveUnreciprocatedRepairTurnsFlipWhichOptionScoresHigherForTheUser() {
        val s1 = build(tiedCalmVsFirmSketch())
        assertTrue("calm should lead at the default priors", yourScoreFor(s1, calm) > yourScoreFor(s1, firm))
        assertEquals(expected(0.60), yourScoreFor(s1, calm), 1e-6); assertEquals(expected(0.55), yourScoreFor(s1, firm), 1e-6)

        // Three turns in a row where the user repaired and the husband withdrew or escalated anyway.
        var s = s1
        repeat(3) { s = BayesianUpdater.applyToScenario(s, "repair", "withdrew_or_escalated") }
        val husband = s.stakeholders.first { it.name == "Husband" }
        assertEquals(0.30, husband.probSoftensOnRepair!!, 1e-9)       // 0.60 -> 0.50 -> 0.40 -> 0.30

        // The same raw situation reported again now goes through toOps with the learned (low) prior already on `s`.
        val s2 = build(tiedCalmVsFirmSketch(), base = s)
        assertTrue("firm should now lead: repair kept failing", yourScoreFor(s2, firm) > yourScoreFor(s2, calm))
        assertEquals(expected(0.30), yourScoreFor(s2, calm), 1e-6); assertEquals(expected(0.55), yourScoreFor(s2, firm), 1e-6)

        val d = dirOf(s2)
        assertNotNull(d.bayesianLearningNote)
        assertTrue(d.bayesianLearningNote!!.contains("has not been softening"))
        assertTrue(d.toJson()["bayesian_learning_note"].toString().contains("has not been softening"))
    }

    private fun dirOf(s: Scenario): EngineDirective {
        val view = EngineDirectiveBuilder.analysisView(s, 50.0, 55.0)
        return EngineDirectiveBuilder.build(view, AnalysisPipeline().run(view), null)
    }
}
