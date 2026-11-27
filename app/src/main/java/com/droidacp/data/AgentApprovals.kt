package com.droidacp.data

import android.content.Context
import com.droidacp.core.acp.PlasmaAgent

/**
 * The agents the user has said PlasmaLLM may download and run on the laptop, once per agent and
 * version: a new tab with Codex 2.12.1 asks once, and asks again for 2.13.0. Only agents
 * PlasmaLLM downloads (npx, uvx) are asked about; Claude and the user's own aren't.
 */
class AgentApprovals(context: Context) {
    private val prefs = context.getSharedPreferences("agents", Context.MODE_PRIVATE)

    fun needsAsking(agent: PlasmaAgent): Boolean = agent.downloads && !prefs.getBoolean(key(agent), false)

    fun approve(agent: PlasmaAgent) {
        if (agent.downloads) prefs.edit().putBoolean(key(agent), true).apply()
    }

    private fun key(agent: PlasmaAgent) = "approved:${agent.id}@${agent.version.orEmpty()}"
}
