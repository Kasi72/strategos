package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.PlainResult
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainResultTest {
    private val banned = listOf("player", "influencer", "stakeholder", "payoff", "matrix", "equilibrium", "nash", "dominant",
        "framework", "scenario", "ai-inferred", "executive", "confidence", "score", "%")

    @Test
    fun resultIsPlainAndComplete() {
        val s = Fx.loanFamily().let { f -> f.copy(payoffs = f.payoffs.map { it.copy(factStatus = com.drkkr.strategos.domain.model.FactStatus.AI_INFERRED) }) }
        val pr = AnalysisPipeline().run(s)
        val text = PlainResult.build(s, pr, listOf("Brother: makes a choice that matters here", "Your options: Co-sign, Give a fixed amount, Wait"))
        println(text)
        assertTrue(text.startsWith("What I understood"))
        assertTrue(text.contains("Here's what I see"))
        assertTrue(text.contains("Options to consider"))
        assertTrue(text.contains("1. "))
        assertTrue(text.contains("my own guesses"))
        assertTrue(text.contains("Other options you have: Co-sign, Give a fixed amount."))
        assertTrue(text.contains("Wife can stop this"))
        assertTrue(text.endsWith(PlainResult.FOOTER))
        val lower = text.substringAfter("Here's what I see").lowercase()
        banned.forEach { assertFalse("'$it' in: $lower", lower.contains(it)) }
    }

    @Test
    fun blockedAnalysisSaysWhatIsMissing() {
        val pr = AnalysisPipeline().run(com.drkkr.strategos.domain.model.Scenario(title = "empty"))
        val text = PlainResult.build(com.drkkr.strategos.domain.model.Scenario(title = "empty"), pr, emptyList())
        assertTrue(text.startsWith("I've saved what you told me"))
    }
}
