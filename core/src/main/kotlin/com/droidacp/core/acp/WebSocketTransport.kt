package com.droidacp.core.acp

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ACP over WebSocket: every text frame carries exactly one JSON-RPC message.
 *
 * The URL can be `ws://` (plain, fine on your LAN/Tailscale) or `wss://` (TLS). If a
 * token is set it is sent as `Authorization: Bearer <token>`, which is what
 * `bridge/acp-bridge.js` checks.
 */
class WebSocketTransport(
    private val client: OkHttpClient,
    private val url: String,
    private val token: String?,
    private val listener: TransportListener,
    /** What to say when the other end answers HTTP 401 or 403. */
    private val refused: String = "The bridge rejected the token",
) : Transport {
    private var socket: WebSocket? = null
    private val finished = AtomicBoolean(false)

    override fun connect() {
        val request = try {
            Request.Builder().url(url).apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer ${token.trim()}")
            }.build()
        } catch (e: IllegalArgumentException) {
            finish("Invalid URL: $url", ConnectionRefusedException("Invalid URL: $url", e))
            return
        }

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!finished.get()) listener.onMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!finished.get()) listener.onMessage(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    finish(describeClose(code, reason), null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finish(describeClose(code, reason), null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    val reason = when (code) {
                        null -> t.message ?: t.javaClass.simpleName
                        401, 403 -> "$refused (HTTP $code)"
                        else -> "HTTP $code ${response.message}".trim()
                    }
                    // Refused (a 4xx): connecting again would be refused again.
                    finish(reason, if (code != null && code in 400..499) ConnectionRefusedException(reason, t) else t)
                }
            },
        )
    }

    /** Queue a message. Returns false if the socket is closed or its buffer is full. */
    override fun send(text: String): Boolean = socket?.send(text) ?: false

    override fun close() {
        socket?.close(1000, "client closed")
        finish("Disconnected", null)
    }

    private fun finish(reason: String, error: Throwable?) {
        if (finished.compareAndSet(false, true)) listener.onClosed(reason, error)
    }

    private fun describeClose(code: Int, reason: String): String = when {
        reason.isNotBlank() -> reason
        code == 1000 -> "Connection closed"
        else -> "Connection closed (code $code)"
    }

    companion object {
        /** One shared HTTP client. Pings keep NATs open and detect dead connections. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
