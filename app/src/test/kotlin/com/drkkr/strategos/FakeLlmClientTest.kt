package com.drkkr.strategos

import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.*
import org.junit.Test

class FakeLlmClientTest {
    private fun req() = LlmRequest("m", buildJsonArray { }, null)

    @Test
    fun returnsScriptedResultsInOrderAndRecordsRequests() = runBlocking {
        val fake = FakeLlmClient()
            .enqueue(FakeLlmClient.toolCall("get_scenario", "{}", "c9"))
            .enqueue(FakeLlmClient.textReply("done"))
        val first = fake.create(req()) as LlmResult.Ok
        assertEquals("get_scenario", first.output.functionCalls().single().name)
        assertEquals("done", (fake.create(req()) as LlmResult.Ok).output.assistantText())
        assertEquals(2, fake.requests.size)
    }

    @Test
    fun errorHelpers() = runBlocking {
        val fake = FakeLlmClient().enqueue(FakeLlmClient.error401()).enqueue(FakeLlmClient.error429(3)).enqueue(FakeLlmClient.networkDown())
        assertTrue(fake.create(req()) is LlmResult.Unauthorized)
        assertEquals(LlmResult.RateLimited(3), fake.create(req()))
        assertEquals(LlmResult.Network("offline"), fake.create(req()))
    }
}
