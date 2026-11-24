package com.droidacp.core.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A JSON-RPC error, either received from the other side or thrown by a local handler. */
class RpcException(val code: Int, message: String, val data: JsonElement? = null) : Exception(message)

/** The connection went away before a response arrived. */
class ConnectionClosedException(message: String, cause: Throwable? = null) : Exception(message, cause)

object RpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val REQUEST_CANCELLED = -32800
    const val AUTH_REQUIRED = -32000
    const val RESOURCE_NOT_FOUND = -32002
}

/**
 * A symmetric JSON-RPC 2.0 peer.
 *
 * ACP is bidirectional: the client sends requests to the agent (`session/prompt`)
 * *and* the agent sends requests to the client (`session/request_permission`).
 * This class handles both directions over any transport that can send and receive
 * text messages:
 *
 * - [request] sends a request with a fresh numeric id and suspends until the
 *   matching response arrives.
 * - [notify] sends a notification (no id, no response).
 * - [receive] must be called with every incoming message. Responses complete the
 *   waiting [request]; requests are passed to [onRequest] and its result (or
 *   thrown [RpcException]) is sent back; notifications go to [onNotification].
 *
 * Notifications are delivered synchronously, in order, on the caller's thread. This
 * matters for ACP: the stream of `session/update` notifications must be applied in
 * exactly the order the agent sent them.
 */
class JsonRpcConnection(
    private val scope: CoroutineScope,
    private val sendText: (String) -> Boolean,
    private val onRequest: suspend (method: String, params: JsonElement?) -> JsonElement,
    private val onNotification: (method: String, params: JsonElement?) -> Unit,
    private val log: ((outgoing: Boolean, text: String) -> Unit)? = null,
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    @Volatile
    private var closedReason: String? = null

    suspend fun request(method: String, params: JsonElement = JsonObject(emptyMap())): JsonElement {
        closedReason?.let { throw ConnectionClosedException(it) }
        val id = nextId.getAndIncrement()
        val response = CompletableDeferred<JsonElement>()
        pending[id] = response
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        if (!write(message)) {
            pending.remove(id)
            throw ConnectionClosedException("Not connected")
        }
        try {
            return response.await()
        } finally {
            pending.remove(id)
        }
    }

    fun notify(method: String, params: JsonElement = JsonObject(emptyMap())) {
        write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            },
        )
    }

    /** Feed one incoming WebSocket text frame. */
    fun receive(text: String) {
        val whole = parseOrNull(text)
        if (whole != null) {
            dispatch(whole)
            return
        }
        // Not a single JSON value: maybe several newline-separated messages.
        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.size <= 1) {
            sendError(JsonNull, RpcErrorCodes.PARSE_ERROR, "Parse error")
            return
        }
        for (line in lines) {
            val element = parseOrNull(line)
            if (element == null) sendError(JsonNull, RpcErrorCodes.PARSE_ERROR, "Parse error") else dispatch(element)
        }
    }

    /** Fail every request that is still waiting. Further requests fail immediately. */
    fun close(reason: String) {
        if (closedReason != null) return
        closedReason = reason
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(ConnectionClosedException(reason)) }
    }

    private fun parseOrNull(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun dispatch(element: JsonElement) {
        log?.invoke(false, element.toString())
        val message = element as? JsonObject
        if (message == null) {
            // ACP does not use JSON-RPC batches (arrays).
            sendError(JsonNull, RpcErrorCodes.INVALID_REQUEST, "Expected a JSON object")
            return
        }
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        val id = message["id"]
        val hasId = id != null && id !is JsonNull

        when {
            method != null && !hasId -> {
                try {
                    onNotification(method, message["params"])
                } catch (e: Exception) {
                    log?.invoke(false, "!! notification handler failed for $method: $e")
                }
            }

            method != null -> {
                val requestId: JsonElement = id ?: JsonNull
                scope.launch {
                    val reply = try {
                        val result = onRequest(method, message["params"])
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", requestId)
                            put("result", result)
                        }
                    } catch (e: RpcException) {
                        errorMessage(requestId, e.code, e.message ?: "Error", e.data)
                    } catch (e: CancellationException) {
                        // Our side is shutting down; tell the agent the request was cancelled.
                        write(errorMessage(requestId, RpcErrorCodes.REQUEST_CANCELLED, "Request cancelled", null))
                        throw e
                    } catch (e: Exception) {
                        errorMessage(requestId, RpcErrorCodes.INTERNAL_ERROR, e.message ?: e.toString(), null)
                    }
                    write(reply)
                }
            }

            hasId -> {
                val key = (id as? JsonPrimitive)?.longOrNull ?: return
                val waiter = pending.remove(key) ?: return
                val error = message["error"] as? JsonObject
                if (error != null) {
                    waiter.completeExceptionally(
                        RpcException(
                            code = error.long("code")?.toInt() ?: RpcErrorCodes.INTERNAL_ERROR,
                            message = error.str("message") ?: "Unknown error",
                            data = error["data"],
                        ),
                    )
                } else {
                    waiter.complete(message["result"] ?: JsonNull)
                }
            }

            else -> sendError(JsonNull, RpcErrorCodes.INVALID_REQUEST, "Invalid request")
        }
    }

    private fun sendError(id: JsonElement, code: Int, message: String) {
        write(errorMessage(id, code, message, null))
    }

    private fun errorMessage(id: JsonElement, code: Int, message: String, data: JsonElement?): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                if (data != null) put("data", data)
            },
        )
    }

    private fun write(message: JsonObject): Boolean {
        if (closedReason != null) return false
        val text = message.toString()
        log?.invoke(true, text)
        return sendText(text)
    }
}
