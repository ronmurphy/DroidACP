package com.droidacp.core.cloud

import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.objects
import com.droidacp.core.rpc.str
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks the SDK-backed [AnthropicCloudApi] without calling Anthropic: a small HTTP
 * server on this machine records what the SDK sends and plays back canned replies.
 */
class AnthropicCloudApiTest {
    private class Recorded(val method: String, val path: String, val query: String?, val body: JsonObject?, val beta: String?)

    private val requests = mutableListOf<Recorded>()
    private val replies = HashMap<String, Pair<Int, String>>() // "METHOD /path" -> (status, body)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        createContext("/") { exchange ->
            val text = exchange.requestBody.readBytes().decodeToString()
            val path = exchange.requestURI.path
            requests += Recorded(
                exchange.requestMethod, path, exchange.requestURI.rawQuery,
                if (text.isBlank()) null else Json.parseToJsonElement(text) as JsonObject,
                exchange.requestHeaders.getFirst("anthropic-beta"),
            )
            val (status, body) = replies["${exchange.requestMethod} $path"] ?: (404 to """{"type":"error","error":{"type":"not_found_error","message":"no such thing"}}""")
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", if (path.endsWith("/stream")) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun api() = AnthropicCloudApi("sk-test", baseUrl = "http://127.0.0.1:${server.address.port}")

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private val toolset = Json.parseToJsonElement(
        """[{"type":"agent_toolset_20260401",
             "default_config":{"enabled":true,"permission_policy":{"type":"auto"}},
             "configs":[{"name":"bash","permission_policy":{"type":"always_ask"}},{"name":"web_fetch","enabled":false},{"name":"web_search","enabled":false}]}]""",
    ) as JsonArray

    @Test
    fun setupAndSessionRequestsHaveTheDocumentedShape() = runBlocking {
        replies["POST /v1/agents"] = 200 to """{"id":"agent_1","type":"agent","name":"DroidACP","version":1}"""
        replies["POST /v1/environments"] = 200 to """{"id":"env_1","type":"environment","name":"DroidACP"}"""
        replies["POST /v1/sessions"] = 200 to """{"id":"sesn_1","type":"session","status":"idle"}"""
        replies["POST /v1/sessions/sesn_1"] = 200 to """{"id":"sesn_1","type":"session","status":"idle"}"""
        replies["POST /v1/sessions/sesn_1/events"] = 200 to """{"data":[]}"""
        val api = api()

        assertEquals("agent_1", api.createAgent("DroidACP", "claude-opus-5-5", "Be brief.", toolset))
        requests.last().let { r ->
            assertEquals("claude-opus-5-5", r.body!!.str("model"))
            assertEquals("Be brief.", r.body.str("system"))
            assertEquals(toolset, r.body["tools"], "the toolset must reach the API exactly as written")
            assertTrue("managed-agents-2026-04-01" in r.beta.orEmpty(), "beta header: ${r.beta}")
        }

        val envConfig = json("""{"type":"cloud","networking":{"type":"unrestricted"}}""")
        assertEquals("env_1", api.createEnvironment("DroidACP", envConfig))
        assertEquals(envConfig, requests.last().body!!.obj("config"))

        val agent = json("""{"type":"agent_with_overrides","id":"agent_1","model":{"id":"claude-sonnet-5-5","effort":"low"},"tools":$toolset}""")
        val repo = json("""{"type":"github_repository","url":"https://github.com/me/app","authorization_token":"ghp_x"}""")
        assertEquals("sesn_1", api.createSession(agent, "env_1", "A title", listOf(repo)))
        requests.last().let { r ->
            assertEquals(agent, r.body!!.obj("agent"))
            assertEquals("env_1", r.body.str("environment_id"))
            assertEquals("A title", r.body.str("title"))
            assertEquals(listOf(repo), r.body.objects("resources"))
        }

        api.updateSession("sesn_1", title = "New title")
        assertEquals(json("""{"title":"New title"}"""), requests.last().body)
        api.updateSession("sesn_1", tools = toolset)
        assertEquals(toolset, requests.last().body!!.obj("agent")!!["tools"])

        val events = listOf(
            """{"type":"user.message","content":[{"type":"text","text":"hi"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}]}""",
            """{"type":"user.interrupt"}""",
            """{"type":"user.tool_confirmation","tool_use_id":"sevt_9","result":"deny","deny_message":"no"}""",
        )
        for (event in events) {
            api.send("sesn_1", json(event))
            assertEquals(listOf(json(event)), requests.last().body!!.objects("events"))
        }
        assertEquals("/v1/sessions/sesn_1/events", requests.last().path)
        api.close()
    }

    @Test
    fun readsSessionsHistoryAndTheLiveStream() = runBlocking {
        replies["GET /v1/sessions"] = 200 to
            """{"data":[{"id":"sesn_1","type":"session","title":"Fix it","status":"idle","created_at":"2026-11-24T10:00:00Z","updated_at":"2026-11-24T11:00:00Z"}],"next_page":null}"""
        val toolUse = """{"type":"agent.tool_use","id":"sevt_2","name":"bash","input":{"command":"ls"},"evaluated_permission":"ask","processed_at":"2026-11-24T10:00:01Z"}"""
        val idle = """{"type":"session.status_idle","id":"sevt_3","stop_reason":{"type":"requires_action","event_ids":["sevt_2"]},"processed_at":"2026-11-24T10:00:02Z"}"""
        val future = """{"type":"some.future_event","id":"sevt_4","anything":{"goes":true}}"""
        replies["GET /v1/sessions/sesn_1/events"] = 200 to """{"data":[$toolUse,$idle,$future],"next_page":null}"""
        val delta = """{"type":"event_delta","event_id":"sevt_5","delta":{"type":"content_delta","index":0,"content":{"type":"text","text":"Hel"}}}"""
        val message = """{"type":"agent.message","id":"sevt_5","content":[{"type":"text","text":"Hello"}],"processed_at":"2026-11-24T10:00:03Z"}"""
        replies["GET /v1/sessions/sesn_1/events/stream"] = 200 to listOf(delta, message, idle).joinToString("") { "event: ${json(it).str("type")}\ndata: $it\n\n" }
        val api = api()

        val sessions = api.listSessions("agent_1")
        assertEquals(listOf("sesn_1" to "Fix it"), sessions.map { it.id to it.title })
        assertTrue(sessions[0].updatedAt.startsWith("2026-11-24T11:00"))
        assertTrue("agent_id=agent_1" in requests.last().query.orEmpty(), "query: ${requests.last().query}")

        // Events come back as the same JSON the API sent, including kinds the SDK has never heard of.
        assertEquals(listOf(json(toolUse), json(idle), json(future)), api.listEvents("sesn_1"))

        val live = withTimeout(10_000) { api.stream("sesn_1").take(4).toList() }
        assertEquals(listOf(json("""{"type":"stream.open"}"""), json(delta), json(message), json(idle)), live)
        assertTrue("event_deltas" in requests.last().query.orEmpty() && "agent.message" in requests.last().query.orEmpty(), "query: ${requests.last().query}")
        api.close()
    }

    @Test
    fun missingObjectsAreReportedAsNotFound() {
        val error = assertFailsWith<CloudException> { runBlocking { api().createSession(json("""{"type":"agent","id":"agent_gone"}"""), "env_1", "t", emptyList()) } }
        assertTrue(error.notFound)
    }
}
