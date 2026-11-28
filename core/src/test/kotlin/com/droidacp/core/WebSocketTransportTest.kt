package com.droidacp.core

import com.droidacp.core.acp.TransportListener
import com.droidacp.core.acp.WebSocketTransport
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The transport's own thread for the listener: a slow one (the tablet, replaying a long chat)
 * mustn't stop OkHttp reading the other end's pongs, and still hears everything in order.
 */
class WebSocketTransportTest {
    private val server = MockWebServer()

    @AfterTest
    fun cleanUp() = server.shutdown()

    /** A listener that takes [perMessageMs] with each message, as a slow device does. */
    private class Slow(private val perMessageMs: Long) : TransportListener {
        val heard = CopyOnWriteArrayList<String>()
        val closed = CompletableFuture<String>()
        val closedAfter = CompletableFuture<Int>()

        override fun onOpen() {}

        override fun onMessage(text: String) {
            Thread.sleep(perMessageMs)
            heard += text
        }

        override fun onClosed(reason: String, error: Throwable?) {
            closedAfter.complete(heard.size)
            closed.complete(reason)
        }
    }

    private fun serve(onOpen: (WebSocket) -> Unit): String {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }))
        server.start()
        return server.url("/").toString().replaceFirst("http", "ws")
    }

    private fun waitUntil(timeoutMs: Long, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check() && System.currentTimeMillis() < end) Thread.sleep(20)
    }

    @Test
    fun aSlowListenerDoesntMissThePongs() {
        val url = serve { ws -> repeat(4) { ws.send("""{"n":$it}""") } }
        // Pings every 200 ms; each message takes 500 ms to handle: 2 s, ten pings. On OkHttp's
        // reading thread, the first pong went unread and OkHttp ended the connection.
        val client = OkHttpClient.Builder().pingInterval(200, TimeUnit.MILLISECONDS).build()
        val listener = Slow(perMessageMs = 500)
        val transport = WebSocketTransport(client, url, null, listener)
        transport.connect()

        waitUntil(5_000) { listener.heard.size == 4 || listener.closed.isDone }
        assertFalse(listener.closed.isDone, "closed: ${listener.closed.getNow(null)}")
        assertEquals((0..3).map { """{"n":$it}""" }, listener.heard)

        transport.close()
        assertEquals("Disconnected", listener.closed.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun theEndIsHeardAfterEveryMessageBeforeIt() {
        val url = serve { ws ->
            repeat(3) { ws.send("""{"n":$it}""") }
            ws.close(1000, "bye")
        }
        val listener = Slow(perMessageMs = 150)
        WebSocketTransport(OkHttpClient(), url, null, listener).connect()

        assertEquals("bye", listener.closed.get(5, TimeUnit.SECONDS))
        assertEquals(3, listener.closedAfter.get(), "every message came before the end")
        assertTrue(listener.heard.size == 3)
    }
}
