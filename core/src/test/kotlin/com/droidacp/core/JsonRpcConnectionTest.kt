package com.droidacp.core

import com.droidacp.core.rpc.ConnectionClosedException
import com.droidacp.core.rpc.JsonRpcConnection
import com.droidacp.core.rpc.RpcException
import com.droidacp.core.rpc.long
import com.droidacp.core.rpc.obj
import com.droidacp.core.rpc.str
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonRpcConnectionTest {

    private fun parse(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun requestIsMatchedWithItsResponse() = runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val rpc = JsonRpcConnection(this, { sent += it; true }, { _, _ -> JsonPrimitive(0) }, { _, _ -> })

        val result = async { rpc.request("ping", buildJsonObject { put("x", 1) }) }
        while (sent.isEmpty()) yield()
        val outgoing = parse(sent.single())
        assertEquals("2.0", outgoing.str("jsonrpc"))
        assertEquals("ping", outgoing.str("method"))

        rpc.receive("""{"jsonrpc":"2.0","id":${outgoing.long("id")},"result":{"pong":true}}""")
        assertEquals("""{"pong":true}""", result.await().toString())
    }

    @Test
    fun errorResponsesBecomeRpcExceptions() = runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val rpc = JsonRpcConnection(this, { sent += it; true }, { _, _ -> JsonPrimitive(0) }, { _, _ -> })
        val result = async { runCatching { rpc.request("session/new") } }
        while (sent.isEmpty()) yield()
        val id = parse(sent.single()).long("id")
        rpc.receive("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"Authentication required"}}""")
        val error = result.await().exceptionOrNull() as RpcException
        assertEquals(-32000, error.code)
        assertEquals("Authentication required", error.message)
    }

    @Test
    fun incomingRequestsAreAnsweredWithTheSameId() = runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val rpc = JsonRpcConnection(
            this,
            { sent += it; true },
            { method, _ -> buildJsonObject { put("echo", method) } },
            { _, _ -> },
        )
        rpc.receive("""{"jsonrpc":"2.0","id":"abc-1","method":"session/request_permission","params":{}}""")
        while (sent.isEmpty()) delay(1)
        val reply = parse(sent.single())
        assertEquals("abc-1", reply.str("id"))
        assertEquals("session/request_permission", reply.obj("result")?.str("echo"))
    }

    @Test
    fun handlerErrorsAreSentBack() = runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val rpc = JsonRpcConnection(this, { sent += it; true }, { _, _ -> throw RpcException(-32601, "nope") }, { _, _ -> })
        rpc.receive("""{"jsonrpc":"2.0","id":7,"method":"fs/read_text_file","params":{}}""")
        while (sent.isEmpty()) delay(1)
        val reply = parse(sent.single())
        assertEquals(7L, reply.long("id"))
        assertEquals(-32601L, reply.obj("error")?.long("code"))
    }

    @Test
    fun notificationsArriveInOrder() {
        val seen = mutableListOf<String>()
        runBlocking {
            val rpc = JsonRpcConnection(this, { true }, { _, _ -> JsonPrimitive(0) }, { _, params -> seen += (params as JsonObject).str("n")!! })
            for (n in 1..50) rpc.receive("""{"jsonrpc":"2.0","method":"session/update","params":{"n":"$n"}}""")
        }
        assertEquals((1..50).map { it.toString() }, seen)
    }

    @Test
    fun newlineSeparatedMessagesInOneFrameAreSplit() {
        val seen = mutableListOf<String>()
        runBlocking {
            val rpc = JsonRpcConnection(this, { true }, { _, _ -> JsonPrimitive(0) }, { method, _ -> seen += method })
            rpc.receive("{\"jsonrpc\":\"2.0\",\"method\":\"a\"}\n{\"jsonrpc\":\"2.0\",\"method\":\"b\"}\n")
        }
        assertEquals(listOf("a", "b"), seen)
    }

    @Test
    fun closeFailsPendingRequests() = runBlocking {
        val rpc = JsonRpcConnection(this, { true }, { _, _ -> JsonPrimitive(0) }, { _, _ -> })
        val result = async { runCatching { rpc.request("session/prompt") } }
        yield()
        rpc.close("bye")
        assertTrue(result.await().exceptionOrNull() is ConnectionClosedException)
        assertFailsWith<ConnectionClosedException> { rpc.request("again") }
        Unit
    }
}
