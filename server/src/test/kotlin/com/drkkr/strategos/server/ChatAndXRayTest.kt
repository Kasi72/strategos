package com.drkkr.strategos.server

import com.drkkr.strategos.server.service.StrategosService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The web UI's chat list and X-ray sheet. */
class ChatAndXRayTest {
    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun `a chat starts from its first message and the X-ray matches the engine's pick`() {
        val app = Fixtures.container(ScriptedLlm(listOf(Fixtures.personal())))
        testApplication {
            application { strategos(app) }
            val first = json(client.post("/api/chats") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"Maya and I fought last night and she went quiet. Part of me wants to keep texting her."}""")
            }.bodyAsText()).jsonObject
            val pid = first["projectId"]!!.jsonPrimitive.content
            val pick = first["directive"]!!.jsonObject["moveToImplement"]!!.jsonPrimitive.content

            val chats = json(client.get("/api/chats").bodyAsText()).jsonArray
            assertEquals(1, chats.size)
            assertEquals("titled from the first sentence", "Maya and I fought last night and she went quiet", chats.single().jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(1, chats.single().jsonObject["turns"]!!.jsonPrimitive.int)

            val x = json(client.get("/api/projects/$pid/xray").bodyAsText()).jsonObject
            assertTrue(x["ready"]!!.jsonPrimitive.boolean)
            val rows = x["rows"]!!.jsonArray.map { it.jsonObject }
            val best = rows[x["bestIndex"]!!.jsonPrimitive.int]
            assertEquals("the sheet's ring is on the chat's move", pick, best["label"]!!.jsonPrimitive.content)
            assertEquals(pick, x["engineMove"]!!.jsonPrimitive.content)
            assertEquals("Maya", x["arena"]!!.jsonObject["them"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertTrue(rows.all { it["well"]!!.jsonPrimitive.int in 0..100 && it["bad"]!!.jsonPrimitive.int in 0..100 })

            val kept = json(client.post("/api/projects/$pid/xray/keep") { contentType(ContentType.Application.Json); setBody("""{"odds":0.8}""") }.bodyAsText()).jsonObject
            assertEquals("the kept odds are the new coach odds", 0.8, kept["coachOdds"]!!.jsonPrimitive.double, 1e-9)
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/projects/$pid/xray/keep") { contentType(ContentType.Application.Json); setBody("""{"odds":2}""") }.status)
        }
        app.close()
    }

    @Test
    fun `a project with no moves yet asks for more instead of drawing a board`() {
        val app = Fixtures.container(ScriptedLlm(listOf(Fixtures.personal())))
        val p = app.service.createProject("Empty")
        testApplication {
            application { strategos(app) }
            val x = json(client.get("/api/projects/${p.id}/xray").bodyAsText()).jsonObject
            assertFalse(x["ready"]!!.jsonPrimitive.boolean)
            assertTrue(x["notEnough"]!!.jsonPrimitive.content.isNotBlank())
        }
        app.close()
    }

    @Test
    fun `chat titles are the first sentence, cut on a word`() {
        assertEquals("Should I take the job in Pune?", StrategosService.chatTitle("Should I take the job in Pune? My wife isn't sure."))
        val long = StrategosService.chatTitle("My manager keeps assigning me weekend work without asking and I am starting to resent it a lot")
        assertTrue(long, long.length <= 49 && long.endsWith("…") && !long.dropLast(1).endsWith(" "))
    }
}
