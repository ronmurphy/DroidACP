package com.droidacp.core.acp

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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

    /**
     * The listener hears everything here, in order, not on OkHttp's reading thread: that thread
     * also reads the other end's pongs. A long chat's replay could keep a slow tablet busy there
     * past the 20 s OkHttp waits for a pong, which then ended the connection ("sent ping but
     * didn't receive pong"), and the next one replayed the chat from the top again (2026-11-27).
     */
    private val inbox: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "acp-inbox").apply { isDaemon = true } }

    private fun deliver(event: () -> Unit) {
        try {
            inbox.execute(event)
        } catch (e: RejectedExecutionException) {
            // Closed: nothing more is heard.
        }
    }

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
                    deliver { listener.onOpen() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!finished.get()) deliver { listener.onMessage(text) }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!finished.get()) {
                        val text = bytes.utf8()
                        deliver { listener.onMessage(text) }
                    }
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

    // OkHttp closes the whole connection rather than queue more than 16 MiB, so the tablet's
    // relay waits for this to go down before passing on a big answer (plan D17).
    override val queuedBytes: Long get() = socket?.queueSize() ?: 0L

    override fun close() {
        socket?.close(1000, "client closed")
        finish("Disconnected", null)
    }

    /** The end, heard after every message that came before it; then the inbox's thread ends. */
    private fun finish(reason: String, error: Throwable?) {
        if (!finished.compareAndSet(false, true)) return
        deliver { listener.onClosed(reason, error) }
        inbox.shutdown()
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
