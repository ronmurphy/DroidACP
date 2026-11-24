package com.droidacp.core.acp

/** What a [Transport] tells its owner. Calls may arrive on any thread. */
interface TransportListener {
    fun onOpen()
    fun onMessage(text: String)

    /** Called exactly once, whether we closed, the other side closed, or something failed. */
    fun onClosed(reason: String, error: Throwable?)
}

/**
 * A two-way pipe that carries JSON-RPC messages, one whole message per call.
 *
 * ACP doesn't care how the messages travel. DroidACP has two transports:
 *
 * - [WebSocketTransport]: to `acp-bridge.js` on another machine (or in Termux).
 * - `LocalTransport` (in the `agent` package): to the agent built into this app.
 *   No network involved — the messages are passed along in memory.
 */
interface Transport {
    /** Start connecting. The listener hears `onOpen` when messages can flow. */
    fun connect()

    /** Queue a message. Returns false if the transport is closed. */
    fun send(text: String): Boolean

    fun close()
}
