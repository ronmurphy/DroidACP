package com.droidacp.core.agent

import com.droidacp.core.rpc.bool
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.objects
import com.droidacp.core.rpc.str
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
 * Checks the one class that uses the Anthropic SDK, without calling Anthropic: a tiny
 * HTTP server on this machine plays the API, replying with the same kind of
 * server-sent event stream the real one sends.
 */
class AnthropicModelClientTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    }
    private var status = 200
    private var responseBody = ""
    private var requestBody: JsonObject? = null
    private var requestHeaders: Map<String, String> = emptyMap()
    private var requestPath = ""
    private var stallAfterBody = false

    init {
        server.createContext("/") { exchange ->
            requestPath = exchange.requestURI.path
            requestHeaders = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            requestBody = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()) as JsonObject
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", if (status == 200) "text/event-stream" else "application/json")
            if (stallAfterBody) {
                // Send what we have, then leave the response open, like a reply still being generated.
                exchange.sendResponseHeaders(status, 0)
                exchange.responseBody.write(bytes)
                exchange.responseBody.flush()
                try {
                    Thread.sleep(30_000)
                } catch (e: InterruptedException) {
                    // the test is over
                }
                exchange.close()
            } else {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun client() = AnthropicModelClient("sk-test", baseUrl = "http://127.0.0.1:${server.address.port}")

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun sse(vararg events: String) = events.joinToString("") { data ->
        "event: ${json(data).str("type")}\ndata: $data\n\n"
    }

    /** A conversation that already contains a tool round trip, to check it survives the SDK unchanged. */
    private val history = listOf(
        json("""{"role":"user","content":[{"type":"text","text":"Fix the greeting"}]}"""),
        json(
            """{"role":"assistant","content":[
                {"type":"thinking","thinking":"Look first.","signature":"sig-abc"},
                {"type":"tool_use","id":"toolu_0","name":"Glob","input":{"pattern":"*.txt"}}]}""",
        ),
        json("""{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_0","content":"a.txt"}]}"""),
    )

    private fun request(model: String = "claude-opus-5-5", web: Boolean = true) =
        ModelRequest(model, "high", "You are a test.", Toolbox.definitions, history, web)

    @Test
    fun streamsAReplyAndBuildsTheRequestTheApiExpects() = runBlocking {
        responseBody = sse(
            """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":5,"cache_creation_input_tokens":2}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Read it."}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-xyz"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Reading "}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"the file."}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"Read","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"file_path\":"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"a.txt\"}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":40}}""",
            """{"type":"message_stop"}""",
        )
        val events = mutableListOf<ModelEvent>()
        val reply = client().use { it.send(request()) { e -> events += e } }

        // What the app sees while it streams.
        assertEquals(
            listOf(
                ModelEvent.Thinking("Read it."),
                ModelEvent.Text("Reading "),
                ModelEvent.Text("the file."),
                ModelEvent.ToolStarted("toolu_1", "Read", server = false),
            ),
            events,
        )

        // The finished message, in the form it will be sent back as history.
        assertEquals("tool_use", reply.stopReason)
        assertEquals("assistant", reply.message.str("role"))
        val blocks = reply.message.objects("content")
        assertEquals(listOf("thinking", "text", "tool_use"), blocks.map { it.str("type") })
        assertEquals("Read it.", blocks[0].str("thinking"))
        assertEquals("sig-xyz", blocks[0].str("signature"), "the signature must survive, or the API rejects the history")
        assertEquals("Reading the file.", blocks[1].str("text"))
        assertEquals(json("""{"file_path":"a.txt"}"""), blocks[2].obj("input"))
        assertEquals(10L + 5 + 2 + 40, reply.contextTokens)

        // The request the SDK actually sent.
        val body = requestBody!!
        assertEquals("/v1/messages", requestPath)
        // How the key travels is the SDK's business; it only has to arrive.
        assertTrue(requestHeaders.any { (name, value) -> name in setOf("x-api-key", "authorization") && "sk-test" in value }, "headers: $requestHeaders")
        assertTrue("server-side-fallback-2026-07-01" in requestHeaders.getValue("anthropic-beta"))
        assertEquals("claude-opus-5-5", body.str("model"))
        assertEquals(true, body.bool("stream"))
        assertEquals(64_000L, body.long("max_tokens"))
        assertEquals(json("""{"type":"adaptive","display":"summarized"}"""), body.obj("thinking"))
        assertEquals("high", body.obj("output_config")?.str("effort"))
        assertEquals("ephemeral", body.obj("cache_control")?.str("type"))
        assertEquals("default", body.str("fallbacks"))
        assertEquals("You are a test.", body.str("system"))
        assertEquals(JsonArray(history), body["messages"], "history must reach the API exactly as we stored it")

        val tools = body.objects("tools")
        assertEquals(Toolbox.all.map { it.name } + listOf("web_search", "web_fetch"), tools.map { it.str("name") })
        assertEquals(true, tools[0].bool("eager_input_streaming"))
        assertEquals(Toolbox.all[0].inputSchema, tools[0].obj("input_schema"))
        assertEquals(listOf("web_search_20260209", "web_fetch_20260209"), tools.takeLast(2).map { it.str("type") })
    }

    @Test
    fun olderWebToolsAndNoFallbackForHaikuAndNoWebToolsWhenOff() = runBlocking {
        responseBody = sse(
            """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":3,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}""",
            """{"type":"message_stop"}""",
        )
        val reply = client().use { it.send(request("claude-haiku-5-5")) { } }
        assertEquals("end_turn", reply.stopReason)
        assertEquals(null, requestBody!!["fallbacks"])
        assertTrue("server-side-fallback" !in (requestHeaders["anthropic-beta"] ?: ""))
        assertEquals(listOf("web_search_20250305", "web_fetch_20250910"), requestBody!!.objects("tools").takeLast(2).map { it.str("type") })

        client().use { it.send(request(web = false)) { } }
        assertEquals(Toolbox.all.map { it.name }, requestBody!!.objects("tools").map { it.str("name") })
    }

    @Test
    fun cancellingStopsARequestThatIsStillStreaming() = runBlocking {
        stallAfterBody = true
        responseBody = sse(
            """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":3,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Once upon"}}""",
        )
        val firstText = CompletableDeferred<Unit>()
        val client = client()
        val job = launch(Dispatchers.Default) { client.send(request()) { if (it is ModelEvent.Text) firstText.complete(Unit) } }
        withTimeout(10_000) { firstText.await() }

        val started = System.nanoTime()
        withTimeout(5_000) { job.cancelAndJoin() }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(job.isCancelled)
        assertTrue(millis < 3_000, "cancelling took $millis ms")
        client.close()
    }

    @Test
    fun apiErrorsBecomeReadableMessages() {
        status = 401
        responseBody = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        val auth = assertFailsWith<ModelException> { runBlocking { client().use { it.send(request()) { } } } }
        assertTrue(auth.authProblem)
        assertTrue("API key" in auth.message!!)

        status = 400
        responseBody = """{"type":"error","error":{"type":"invalid_request_error","message":"messages: bad thing"}}"""
        val bad = assertFailsWith<ModelException> { runBlocking { client().use { it.send(request()) { } } } }
        assertEquals("Claude API error 400: messages: bad thing", bad.message)
    }
}
