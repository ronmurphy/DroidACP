package com.droidacp.core

import com.droidacp.core.acp.BridgeCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeCommandTest {
    @Test
    fun plainPathsStayAsTheyAre() {
        assertEquals(
            "node bridge/acp-bridge.js --cwd /home/brad/Documents/DroidACP -- npx -y @agentclientprotocol/claude-agent-acp",
            BridgeCommand.forFolder("/home/brad/Documents/DroidACP"),
        )
        assertEquals("node bridge/acp-bridge.js --cwd . -- npx -y @agentclientprotocol/claude-agent-acp", BridgeCommand.forFolder("  "))
    }

    @Test
    fun specialCharactersAreQuoted() {
        assertEquals("'/home/brad/My Projects'", BridgeCommand.quote("/home/brad/My Projects"))
        assertEquals("'/tmp/it'\\''s'", BridgeCommand.quote("/tmp/it's"))
        assertEquals("'~/code'", BridgeCommand.quote("~/code"))
        assertEquals("'C:\\Users\\brad'", BridgeCommand.quote("C:\\Users\\brad"))
    }

    @Test
    fun quotedPathsSurviveARealShell() {
        // Let /bin/sh decide: echo the quoted word back and compare.
        for (path in listOf("/home/brad/My Projects", "/tmp/it's here", "/a/\$HOME/`x`", "/b/*?")) {
            val process = ProcessBuilder("sh", "-c", "printf %s " + BridgeCommand.quote(path)).start()
            assertEquals(path, process.inputStream.bufferedReader().readText())
        }
    }
}
