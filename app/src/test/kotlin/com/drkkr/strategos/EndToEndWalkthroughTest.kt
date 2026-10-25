package com.drkkr.strategos

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.domain.framework.ModuleRunStatus
import com.drkkr.strategos.nlp.ScenarioParser
import com.drkkr.strategos.ui.StrategosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Exercises the app end to end against the real ViewModel + engine and writes a
 * human-readable walkthrough to build/e2e-walkthrough.txt. Not a unit assertion
 * suite — a living trace of how a scenario flows from text to decision report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EndToEndWalkthroughTest {

    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val out = StringBuilder()
    private fun line(s: String = "") { out.appendLine(s) }

    @Test
    fun walkthrough_textToDecisionReport() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = StrategosViewModel(app)

        line("================ STRATEGOS END-TO-END WALKTHROUGH ================")
        line()

        // ── STAGE 1: free-text entry (the real user input path) ───────────────
        val rawText = """
            Our hospital's psychiatry department head and the clinical director
            disagree on whether to adopt a new involuntary-hold protocol. The
            department head wants to pilot it; the clinical director fears legal
            and ethical fallout and could veto. We need to rebuild trust and
            decide over the next two quarters.
        """.trimIndent()
        line("STAGE 1 — FREE-TEXT INPUT (ScenarioParser)")
        line("User typed:")
        line(rawText.prependIndent("  "))
        val parsed = ScenarioParser.parse(rawText)
        val draft = parsed.draft
        line()
        line("Parsed draft scenario:")
        line("  title            : ${draft.title}")
        line("  stakeholders(${draft.stakeholders.size}) : ${draft.stakeholders.joinToString { it.name }}")
        line("  actions(${draft.actions.size})     : ${draft.actions.joinToString { it.label }}")
        line("  clarifying Qs    : ${parsed.clarificationQuestions.size}")
        parsed.clarificationQuestions.take(3).forEach { line("     - $it") }
        line()
        line("  (NLP produces a DRAFT — real use then enriches payoffs, beliefs,")
        line("   evidence and constraints before analysis. For a complete run we")
        line("   load a fully-specified scenario below.)")
        line()

        // ── STAGE 2: a complete scenario (template) ───────────────────────────
        vm.loadTemplate()
        val scenario = vm.state.value.activeScenario!!
        line("STAGE 2 — COMPLETE SCENARIO LOADED")
        line("  title        : ${scenario.title}")
        line("  stakeholders : ${scenario.stakeholders.size}  (${scenario.stakeholders.joinToString { it.name }})")
        line("  actions      : ${scenario.actions.size}")
        line("  payoffs      : ${scenario.payoffs.size} entries")
        line("  constraints  : ${scenario.constraints.size}")
        line("  beliefs      : ${scenario.beliefs.size}")
        line("  evidence     : ${scenario.evidenceSources.size} sources")
        line()

        // ── STAGE 3: run the analysis pipeline ────────────────────────────────
        line("STAGE 3 — ANALYSIS PIPELINE (vm.analyze())")
        vm.analyze()
        // analyze() completes on a background (IO) continuation after saving a
        // snapshot, so the report lands slightly after the call returns.
        var waited = 0
        while (vm.state.value.report == null && waited < 500) { Thread.sleep(10); waited++ }
        val st = vm.state.value

        val plan = st.analysisPlan
        line("  Framework selection plan:")
        line("     modules selected to run : ${plan?.selected()?.size ?: 0}")

        val runs = st.moduleRunResults
        val byStatus = runs.groupingBy { it.status }.eachCount()
        line("  Module run results (${runs.size} total):")
        ModuleRunStatus.values().forEach { s ->
            val n = byStatus[s] ?: 0
            if (n > 0) line("     ${s.name.padEnd(28)} : $n")
        }
        val succeeded = runs.filter { it.status == ModuleRunStatus.SUCCESS }
        line("  Modules that produced results:")
        succeeded.take(12).forEach { line("     ✓ ${it.result?.moduleName}") }
        line()

        // ── STAGE 4: the decision report ──────────────────────────────────────
        val report = st.report
        assertNotNull("pipeline must produce a report", report)
        report!!
        line("STAGE 4 — DECISION REPORT")
        line("  Executive status   : ${report.executiveStatus}")
        line("  Overall confidence : ${"%.0f".format(report.overallConfidence * 100)}%")
        report.confidenceProfile?.let {
            line("  Confidence profile : evidence=${pct(it.evidenceQuality)} model=${pct(it.modelCompleteness)} " +
                "robustness=${pct(it.analyticalRobustness)} readiness=${pct(it.implementationReadiness)} -> ${it.label()}")
        }
        line("  Matrix coverage    : ${report.matrixCoverage}")
        line()
        line("  Decision pathways (${report.pathways.size}):")
        report.pathways.take(6).forEach { p ->
            val tag = when {
                p.excluded -> "EXCLUDED"; p.fragile -> "FRAGILE"
                p.deferReason != null -> "DEFERRED"; else -> p.label
            }
            line("     [$tag] ${p.action}  (strength ${pct(p.strength)})")
            line("            ${p.rationale.take(110)}")
        }
        line()
        line("  Top risks (${report.riskRegister.size}):")
        report.riskRegister.take(3).forEach { r ->
            line("     • ${r.failureMode}  [${r.likelihood}/${r.impact}]  mitigate: ${r.mitigation.take(60)}")
        }
        line()
        line("  Lens recommendations (${report.combinedRecommendations.size}):")
        report.combinedRecommendations.take(4).forEach { rec ->
            line("     • ${rec.lensLabel}: ${rec.action.take(70)}")
        }
        line()
        line("  Value-of-information (${report.voiItems.size} missing facts):")
        report.voiItems.take(3).forEach { v ->
            line("     ? ${v.missingFact}  (${v.percentageScenariosChanged}% impact)")
        }
        line()
        line("================ END OF WALKTHROUGH ================")

        val file = File("build/e2e-walkthrough.txt")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
    }

    private fun pct(d: Double) = "${(d * 100).toInt()}%"
}
