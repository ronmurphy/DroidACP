package com.droidacp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidacp.core.chat.ChatState
import com.droidacp.core.tablet.TabletStatus

/** What the tablet bar says, and which button it has. */
private data class TabletBarText(val text: String, val button: Button = Button.None, val active: Boolean = false) {
    enum class Button { None, Stop, OpenTabDesk }
}

/**
 * The bar above the message box while "Let the agent use this tablet" is on (plan D9, D11):
 * the agent using the tablet, with Stop; stopped or paused until the next message; or what
 * keeps it from working, with a way to TabDesk's settings. Nothing while all is quiet.
 */
private fun tabletBarText(state: ChatState): TabletBarText? {
    val tablet = state.tablet ?: return null
    if (!state.isConnected) return null
    val status = tablet.status
    val open = TabletBarText.Button.OpenTabDesk
    when (status.reach) {
        TabletStatus.Reach.Off, TabletStatus.Reach.Connecting -> return null
        TabletStatus.Reach.Connected -> {}
        else -> return TabletBarText(status.unavailable!!.replaceFirstChar { it.uppercase() } + ".")
    }
    if (!status.approved) {
        return when {
            !status.enabled || status.reason == "switched_off" -> TabletBarText("Agents are switched off in TabDesk.", open)
            status.approval == "lapsed" -> TabletBarText("TabDesk's approval of DroidACP ran out (it lasts 7 days).", open)
            status.approval == "certificate_changed" ->
                TabletBarText("TabDesk approved another copy of DroidACP (a debug build counts as another app). Approve this one.", open)
            status.reason == "revoked" -> TabletBarText("DroidACP's approval was revoked in TabDesk.", open)
            else -> TabletBarText("TabDesk hasn't approved DroidACP yet. Approve it in TabDesk → Customize → Agent.", open)
        }
    }
    if (!tablet.offered) return TabletBarText("This PlasmaLLM can't use a tablet yet. Update PlasmaLLM on the computer.")
    return when {
        tablet.paused -> TabletBarText("Paused because you touched the screen. Send a message to let the agent go on.")
        tablet.stopped -> TabletBarText("Stopped. The agent can use the tablet again when you send the next message.")
        tablet.inUse -> TabletBarText("The agent is using this tablet", TabletBarText.Button.Stop, active = true)
        state.plasmaLlm?.tabletTools == false ->
            TabletBarText("The computer doesn't let the agent use a tablet: PlasmaLLM's setting “Let the agent use a connected tablet” is off.")
        else -> null
    }
}

@Composable
fun TabletBar(state: ChatState, onStop: () -> Unit, onOpenTabDesk: () -> Unit) {
    val bar = tabletBarText(state) ?: return
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (bar.active) colors.tertiaryContainer else colors.secondaryContainer,
        contentColor = if (bar.active) colors.onTertiaryContainer else colors.onSecondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                bar.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
            )
            when (bar.button) {
                TabletBarText.Button.Stop -> TextButton(onClick = onStop) { Text("Stop", color = colors.error) }
                TabletBarText.Button.OpenTabDesk -> TextButton(onClick = onOpenTabDesk) { Text("Open TabDesk") }
                TabletBarText.Button.None -> {}
            }
        }
    }
}
