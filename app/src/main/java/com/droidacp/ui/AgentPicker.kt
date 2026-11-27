package com.droidacp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidacp.core.acp.PlasmaAgent
import com.droidacp.core.chat.ChatState

/**
 * "New tab with…": the agents a new PlasmaLLM tab can run (protocol 15), in PlasmaLLM's order:
 * Claude and the agents being made to work there, then the user's own, then the rest of the
 * ACP Registry. One that can't start now is greyed out, saying why. One PlasmaLLM downloads
 * asks first, once per version ([ChatActions.mustAskFor]). [folder]: where the tab opens
 * (blank: the folder open on the computer). [onStarted] runs once a tab is asked for.
 */
@Composable
fun AgentPicker(state: ChatState, folder: String, actions: ChatActions, onStarted: () -> Unit, onDismiss: () -> Unit) {
    var asking by remember { mutableStateOf<PlasmaAgent?>(null) }

    fun start(agent: PlasmaAgent) {
        actions.newAgentTab(agent, folder.ifBlank { null })
        onStarted()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New tab with…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val where = folder.ifBlank { null }?.let { "in ${shortFolder(it)}" } ?: "in the folder open there"
                Text(
                    "A new tab on the computer, $where. This chat goes on in its own tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val agents = state.plasmaAgents
                val failed = state.agentsError
                when {
                    agents == null && failed != null -> Text(failed, color = MaterialTheme.colorScheme.error)
                    agents == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    agents.isEmpty() -> Text("PlasmaLLM lists no agents.")
                    else -> LazyColumn(Modifier.heightIn(max = 440.dp)) {
                        itemsIndexed(agents, key = { _, a -> a.id }) { i, agent ->
                            val group = groupOf(agent)
                            if (i > 0 && group != groupOf(agents[i - 1])) {
                                Text(
                                    if (group == Group.Own) "Your own" else "More from the ACP Registry",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                                )
                            }
                            AgentRow(agent) { if (actions.mustAskFor(agent)) asking = agent else start(agent) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    asking?.let { agent ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Start ${agent.nameAndVersion}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val from = when (agent.kind) {
                        "npx" -> " from npm"
                        "uvx" -> " from PyPI"
                        else -> ""
                    }
                    Text("PlasmaLLM downloads ${agent.name}$from and runs it on the computer, in a new tab. The first start can take a minute.")
                    if (!agent.tested) {
                        Text("It hasn't been tried with PlasmaLLM yet: signing in, PlasmaLLM's tools or reopening its chats may not work. Signing in happens on the computer.")
                    }
                    Text(
                        "Asked once for each version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    start(agent)
                }) { Text("Download and start") }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
}

/** PlasmaLLM's order: the default and featured agents, the user's own, then the rest of the registry. */
private enum class Group { First, Own, Registry }

private fun groupOf(agent: PlasmaAgent): Group = when {
    agent.isDefault || agent.featured -> Group.First
    agent.kind == "command" -> Group.Own
    else -> Group.Registry
}

@Composable
private fun AgentRow(agent: PlasmaAgent, onPick: () -> Unit) {
    val notes = buildList {
        if (!agent.ready) {
            add(agent.why ?: "It can't start now.")
        } else {
            if (agent.downloads) add("Downloaded on its first start")
            if (!agent.tested && !agent.isDefault) add("Not tried with PlasmaLLM yet")
        }
    }
    ListItem(
        leadingContent = { Initial(agent.name) },
        headlineContent = {
            Text(
                if (agent.isDefault) "${agent.nameAndVersion} (default)" else agent.nameAndVersion,
                fontWeight = if (agent.isDefault) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                agent.description?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (notes.isNotEmpty()) {
                    Text(
                        notes.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .alpha(if (agent.ready) 1f else 0.5f)
            .clickable(enabled = agent.ready, onClickLabel = "Open a new tab with ${agent.name}", onClick = onPick),
    )
}

/** The agent's first letter in a circle; PlasmaLLM's icons are SVGs, which DroidACP doesn't draw. */
@Composable
private fun Initial(name: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
