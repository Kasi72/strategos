package com.drkkr.strategos

import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.templates.*
import org.junit.Assert.*
import org.junit.Test

class AnalysisPipelineTest {

    /** executiveStatus | pathway labels with action labels | candidate action labels. Ids are random per template, so labels stand in for ids. */
    private fun digest(s: Scenario): String {
        val pr = AnalysisPipeline().run(s)
        val report = pr.report!!
        fun label(id: String?) = s.actions.firstOrNull { it.id == id }?.label ?: "-"
        return "${report.executiveStatus}|" +
            report.pathways.joinToString(";") { "${it.label}=${label(it.actionId)}" } + "|" +
            report.candidates.joinToString(";") { label(it.actionId) }
    }

    // Expected values captured from the pre-refactor StrategosViewModel.analyze() logic.
    @Test fun TC_PIPE_01_crisis() = assertEquals(
        "HOLD_FOR_VERIFICATION|Recommended=Issue 48-hour strike notice|Issue 48-hour strike notice",
        digest(CrisisManagementTemplate.create()))

    @Test fun TC_PIPE_01_healthcare() = assertEquals(
        "HOLD_FOR_EVIDENCE|Recommended=Convene formal review committee|" +
            "Convene formal review committee;Cooperate fully;Support department resolution;Monitor and report",
        digest(InstitutionalHealthcareTemplate.create()))

    @Test fun TC_PIPE_01_procurement() = assertEquals(
        "HOLD_FOR_VERIFICATION|Recommended=Award on lowest price|Award on lowest price",
        digest(ProcurementTemplate.create()))

    @Test fun TC_PIPE_01_resource() = assertEquals(
        "HOLD_FOR_VERIFICATION|Recommended=Submit standard budget request|Submit standard budget request",
        digest(ResourceAllocationTemplate.create()))

    @Test
    fun TC_PIPE_02_noStakeholdersBlocksWithoutException() {
        val pr = AnalysisPipeline().run(Scenario(title = "Empty"))
        assertNull(pr.report)
        assertTrue(pr.validity.blocksAnalysis)
        assertNull(pr.plan)
        assertTrue(pr.moduleRuns.isEmpty() && pr.results.isEmpty())
    }
}
