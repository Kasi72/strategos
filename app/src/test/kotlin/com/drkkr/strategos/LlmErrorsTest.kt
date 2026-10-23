package com.drkkr.strategos

import com.drkkr.strategos.llm.LlmErrors
import com.drkkr.strategos.llm.LlmResult
import org.junit.Assert.*
import org.junit.Test

class LlmErrorsTest {
    @Test fun creditExhaustionIsReportedAsNotEnoughCredits() {
        assertEquals("Not enough credits.", LlmErrors.userMessage(LlmResult.RateLimited(60)))
        assertEquals("Not enough credits.", LlmErrors.userMessage(LlmResult.RateLimited(null)))
        assertEquals("Not enough credits.", LlmErrors.userMessage(LlmResult.HttpError(402, null)))
        assertEquals("Not enough credits.", LlmErrors.userMessage(LlmResult.HttpError(400, "insufficient_quota")))
    }

    @Test fun otherFailuresKeepTheirOwnMessage() {
        assertFalse(LlmErrors.isCreditProblem(LlmResult.Unauthorized(401)))
        assertFalse(LlmErrors.isCreditProblem(LlmResult.Network("offline")))
        assertFalse(LlmErrors.isCreditProblem(LlmResult.HttpError(500, null)))
        assertTrue(LlmErrors.userMessage(LlmResult.Unauthorized(401)).contains("key"))
        assertTrue(LlmErrors.userMessage(LlmResult.Network("offline")).contains("offline"))
    }

    @Test fun messagesNeverContainContent() {
        assertFalse(LlmErrors.userMessage(LlmResult.HttpError(400, "model_not_found")).contains("model_not_found"))
    }
}
