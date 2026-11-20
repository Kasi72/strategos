package com.drkkr.strategos.server.live

import com.drkkr.strategos.server.Fixtures
import com.drkkr.strategos.server.llm.ChatRequest
import com.drkkr.strategos.server.llm.ChatResult
import com.drkkr.strategos.server.llm.DeepSeekProvider
import com.drkkr.strategos.server.llm.LlmProvider
import com.drkkr.strategos.server.persistence.DomainType
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Live checks against DeepSeek. Skipped unless DEEPSEEK_API_KEY is set. Each message in LIVE_MESSAGES_FILE (one per line) is
 * one turn of one thread; every model call and reply is written to LIVE_OUT_FILE for reading (never logged).
 */
class LiveDeepSeekTest {
    @Test
    fun replayMessages() = runBlocking {
        val key = System.getenv("DEEPSEEK_API_KEY"); val inFile = System.getenv("LIVE_MESSAGES_FILE"); val outFile = System.getenv("LIVE_OUT_FILE")
        assumeTrue(!key.isNullOrBlank() && inFile != null && outFile != null)
        val out = File(outFile!!).apply { writeText("") }
        val real = DeepSeekProvider.create(key!!)
        val recording = object : LlmProvider {
            override val name = real.name; override val model = real.model
            override suspend fun chat(request: ChatRequest): ChatResult = real.chat(request).also {
                out.appendText("=== ${request.purpose} ===\n${(it as? ChatResult.Ok)?.text ?: it}\n\n")
            }
        }
        Fixtures.container(recording).use { app ->
            val domain = System.getenv("LIVE_DOMAIN")?.let { DomainType.valueOf(it) } ?: DomainType.GENERAL
            val p = app.service.createProject("Live", domain)
            val t = app.service.createThread(p.id, "Live")!!
            File(inFile!!).readLines().filter { it.isNotBlank() }.forEach { msg ->
                val r = app.service.sendMessage(t.id, msg)
                out.appendText("##### TURN ${r.turnIndex} intent=${r.intent} coachUsed=${r.coachUsed}\n> $msg\n\n${r.reply}\n\n")
            }
        }
    }
}
