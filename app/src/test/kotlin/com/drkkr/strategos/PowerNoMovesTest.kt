package com.drkkr.strategos

import com.drkkr.strategos.domain.human.CoerciveControl
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.HumanVocab
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.human.power.NoMovesLines
import com.drkkr.strategos.domain.human.power.NoMovesReason
import com.drkkr.strategos.domain.human.power.PowerPlan
import com.drkkr.strategos.domain.human.power.PowerPlanner
import com.drkkr.strategos.domain.human.power.PowerRead
import com.drkkr.strategos.domain.human.power.StrategicCatalog
import com.drkkr.strategos.domain.human.power.StrategicTactic
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * pm6.1: the app never shows an explanation that a real code path didn't produce. Every Power plan with no moves carries exactly one
 * [NoMovesReason], the card shows only that reason's fixed line, and no default or fallback "why" line exists.
 */
class PowerNoMovesTest {
    private fun hc(s: Scenario, f: (HumanContext) -> HumanContext) = s.copy(humanContext = f(s.humanContext ?: HumanContext()))
    private fun plan(s: Scenario, tactics: List<StrategicTactic> = StrategicCatalog.tactics): PowerPlan =
        PowerPlanner.plan(s, RelationshipDomains.of(s.humanContext?.relationship, null, s.negotiationContext), true, false, false, 50.0, 0.0, tactics = tactics)

    /** Every story x every relationship x every vulnerable value x coercion or not x full / weak / no tactics. */
    private fun sweep(): List<Pair<String, Scenario>> {
        val bases = listOf("vendor" to Fx.pmVendor(), "cofounder" to Fx.pmCofounder(), "manager" to Fx.pmManager(), "probed" to Fx.pmManager(probes = true),
            "spouse" to Fx.pmSpouse(), "loan" to Fx.hlLoan(), "empty" to Scenario(id = "e", title = "e"))
        return bases.flatMap { (n, s) ->
            (HumanVocab.RELATIONSHIP + listOf<String?>(null)).flatMap { rel ->
                (HumanVocab.VULNERABLE + listOf<String?>(null)).flatMap { v ->
                    listOf(emptyList(), listOf("isolates_you"), listOf("hot_cold", "probes_you")).map { tags ->
                        "$n/$rel/$v/$tags" to hc(s) { it.copy(relationship = rel, theirVulnerable = v, theirTactics = tags) }
                    }
                }
            }
        }
    }
    private val tacticSets = listOf(
        "all" to StrategicCatalog.tactics,
        "small" to StrategicCatalog.tactics.map { it.copy(gain = it.gain * 0.1) },
        "tiny" to StrategicCatalog.tactics.map { it.copy(gain = 0.001, exposureLoss = 1.0) },
        "none" to emptyList()
    )

    @Test fun everyPlanWithPowerModeOnAndNoMovesHasAReasonAndALine() {
        var checked = 0
        for ((name, s) in sweep()) for ((tn, t) in tacticSets) {
            val p = plan(s, t)
            if (p.cardMoves.isEmpty()) {
                assertNotNull("$name/$tn", p.noMoves)
                val read = PowerRead.build(s, p)
                assertEquals("$name/$tn", p.noMoves, read.noMoves)
                assertNotNull("$name/$tn", read.noMovesLine)
                checked++
            } else assertEquals("$name/$tn: moves and a reason", null, p.noMoves)
        }
        assertTrue(checked > 1000)
    }

    @Test fun theNothingToGainFallbackNoLongerExists() {
        val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        val hits = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { it.readText().contains("nothing to gain") }.toList()
        assertTrue(hits.toString(), hits.isEmpty())
        // a card with no moves and no reason has no line at all
        assertEquals(null, PowerRead.build(Fx.pmVendor(), PowerPlan()).noMovesLine)
        assertEquals(null, NoMovesLines.of(null, null, "Karthik"))
    }

    @Test fun theSpouseReadAsAnElderInCareStillShowsTheCloseNote() {
        val s = hc(Fx.pmSpouse()) { it.copy(theirVulnerable = "elder_in_care") }
        val p = plan(s)
        assertTrue(p.cardMoves.isEmpty() && p.deliveryMoves.isEmpty())
        assertEquals(NoMovesReason.CLOSE_RELATIONSHIP, p.noMoves)
        assertEquals(PowerPlanner.CLOSE_DOMAIN_NOTE, PowerRead.build(s, p).noMovesLine)
        // a coercive pattern in a close relationship: the close note too
        val c = hc(Fx.pmSpouse()) { it.copy(theirTactics = listOf("hot_cold")) }
        assertEquals(NoMovesReason.CLOSE_RELATIONSHIP, plan(c).noMoves)
        // every close relationship, with every vulnerable value
        for (rel in HumanVocab.RELATIONSHIP) for (v in HumanVocab.VULNERABLE) {
            val r = hc(Fx.pmVendor()) { it.copy(relationship = rel, theirVulnerable = v) }
            if (RelationshipDomains.of(rel, null, r.negotiationContext).isClose) assertEquals("$rel/$v", NoMovesReason.CLOSE_RELATIONSHIP, plan(r).noMoves)
        }
    }

    @Test fun anEmptyCandidateListIsNothingApplies_neverAnExposureReason() {
        val exposure = setOf(NoMovesReason.EXPOSED, NoMovesReason.CONTINUES, NoMovesReason.SMALL)
        // the manager (q .45: once EXPOSED), the cofounder (continuing: once CONTINUES) and the vendor (once SMALL)
        for (s in listOf(Fx.pmVendor(), Fx.pmCofounder(), Fx.pmManager(), Fx.pmManager(probes = true))) {
            val p = plan(s, tactics = emptyList())
            assertEquals(s.id, NoMovesReason.NOTHING_APPLIES, p.noMoves)
            assertEquals(NoMovesLines.NOTHING_APPLIES, PowerRead.build(s, p).noMovesLine)
        }
        // no user option to carry a move: also nothing applies
        val noOptions = Fx.pmManager().copy(actions = emptyList())
        assertEquals(NoMovesReason.NOTHING_APPLIES, plan(noOptions).noMoves)
        // and across the whole sweep, an exposure reason only ever comes with candidates
        for ((name, s) in sweep()) assertFalse(name, plan(s, tactics = emptyList()).noMoves in exposure)
    }

    @Test fun safetyReasonsSayWhatWasReadSoAMisreadIsVisible() {
        val v = hc(Fx.pmVendor()) { it.copy(theirVulnerable = "in_crisis") }
        val p = plan(v)
        assertEquals(NoMovesReason.THEY_ARE_VULNERABLE, p.noMoves)
        val line = PowerRead.build(v, p).noMovesLine!!
        assertTrue(line, line.startsWith("Hardball is off: ") && line.endsWith(" was read as in crisis."))
        assertEquals("Hardball is off: Karthik was read as an elder in care.", NoMovesLines.of(NoMovesReason.THEY_ARE_VULNERABLE, "elder_in_care", "Karthik"))
        for (x in HumanVocab.VULNERABLE - "none") assertFalse(x, NoMovesLines.plain(x).contains('_'))
        val c = hc(Fx.pmVendor()) { it.copy(theirTactics = listOf("keeps_you_dependent")) }
        val pc = plan(c)
        assertEquals(NoMovesReason.COERCION, pc.noMoves)
        assertTrue(PowerRead.build(c, pc).noMovesLine!!.contains("was read as keeping you dependent on them"))
        for (t in CoerciveControl.TAGS) assertFalse(t, NoMovesLines.plain(t).contains('_'))
        // a vulnerable reading wins over a coercive one, and both come before "nothing applies"
        val both = hc(Fx.pmVendor()) { it.copy(theirVulnerable = "unwell", theirTactics = listOf("isolates_you")) }
        assertEquals(NoMovesReason.THEY_ARE_VULNERABLE, plan(both, tactics = emptyList()).noMoves)
    }

    /** Extraction fixture (rule 20): "his parents need him nearby" is about the people he looks after, so their_vulnerable stays none. */
    @Test fun extractionFixture_hisParentsNeedHimNearby_isNotVulnerable() {
        val text = javaClass.classLoader!!.getResource("human/spouseParentsNearby.json")!!.readText()
        val fx = kotlinx.serialization.json.Json.parseToJsonElement(text).let { it as kotlinx.serialization.json.JsonObject }
        assertTrue(fx["messages"].toString().contains("his parents need him nearby"))
        val sketch = com.drkkr.strategos.domain.agent.SketchParser.parse(fx["sketch"].toString())!!
        assertTrue("their_vulnerable=${sketch.theirVulnerable}", sketch.theirVulnerable == null || sketch.theirVulnerable == "none")
        val ctx = com.drkkr.strategos.domain.agent.SketchHuman.context(sketch)
        assertTrue(ctx?.theirVulnerable == null || ctx.theirVulnerable == "none")
        // the prompt says so in rule 20
        assertTrue(com.drkkr.strategos.domain.agent.ArgonPrompts.EXTRACTION_PROMPT.contains("their_vulnerable: none|child|elder_in_care|unwell|in_crisis (them, not who they care for)"))
    }

    /** Audit fix: the law chip had two fallbacks (the raw source as a label; "No note for this one yet." in the sheet). */
    @Test fun everyLawLabelTheCardCanShowComesFromARealLawOrChapter_withItsNote() {
        val sources = StrategicCatalog.tactics.map { it.tactic.source } +
            (HumanVocab.THEIR_TACTICS + HumanVocab.POWER_THEIR_TACTICS).mapNotNull { com.drkkr.strategos.domain.human.ManipulationRadar.describe(it)?.source }
        for (src in sources) {
            val label = PowerRead.lawLabel(src)
            assertTrue("no label for $src", label.isNotBlank())
            assertTrue("no note for $label", com.drkkr.strategos.domain.human.power.LawNotes.forLabel(label).isNotEmpty())
        }
        assertEquals("", PowerRead.lawLabel("a book with no law or chapter"))
        val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        assertFalse(main.walkTopDown().filter { it.isFile && it.extension == "kt" }.any { it.readText().contains("No note for this one yet") })
    }

    @Test fun exposureReasonsOnlyWhenMovesAppliedButScoredLow() {
        val small = StrategicCatalog.tactics.map { it.copy(gain = it.gain * 0.1) }
        val p = plan(Fx.pmManager(), tactics = small)
        assertEquals(NoMovesReason.EXPOSED, p.noMoves)
        assertNotNull(p.q)
    }
}
