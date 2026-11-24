package com.droidacp.core.agent

import com.droidacp.core.acp.Transport
import com.droidacp.core.acp.TransportListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** An ACP agent that lives inside this app: it is handed JSON-RPC messages and sends its own back. */
interface InProcessAgent {
    fun receive(text: String)
    fun close()
}

/**
 * Connects the app to an agent in the same process.
 *
 * It behaves like a network socket without the network: each direction is a queue
 * (a coroutine [Channel]) with one coroutine delivering messages in order. Using
 * queues rather than calling the other side directly means a sender never waits for
 * the receiver, exactly as with a real connection.
 *
 * @param createAgent makes the agent, given the scope its work runs in and the
 *                    function it sends messages to the app with.
 */
open class InProcessTransport(
    private val listener: TransportListener,
    createAgent: (scope: CoroutineScope, send: (String) -> Boolean) -> InProcessAgent,
) : Transport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toAgent = Channel<String>(Channel.UNLIMITED)
    private val toClient = Channel<String>(Channel.UNLIMITED)
    private val finished = AtomicBoolean(false)
    private val agent = createAgent(scope) { text -> toClient.trySend(text).isSuccess }

    override fun connect() {
        scope.launch {
            listener.onOpen()
            for (text in toClient) listener.onMessage(text)
        }
        scope.launch {
            for (text in toAgent) agent.receive(text)
        }
    }

    override fun send(text: String): Boolean = !finished.get() && toAgent.trySend(text).isSuccess

    override fun close() {
        if (!finished.compareAndSet(false, true)) return
        agent.close()
        scope.cancel() // stops whatever the agent is in the middle of
        listener.onClosed("Disconnected", null)
    }
}
