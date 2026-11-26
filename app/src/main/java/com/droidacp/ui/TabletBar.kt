package com.droidacp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.droidacp.core.chat.ChatState
import com.droidacp.core.tablet.TabletStatus

/** What the tablet bar says, its buttons, and how it looks. */
private data class TabletBarText(val text: String, val buttons: List<Button> = emptyList(), val look: Look = Look.Quiet) {
    constructor(text: String, button: Button, look: Look = Look.Quiet) : this(text, listOf(button), look)

    enum class Button { Stop, Hold, Release, OpenTabDesk }
    enum class Look { Quiet, Active, Held }
}

/**
 * The bar above the message box while "Let the agent use this tablet" is on (plan D9, D11,
 * D20): on hold until the user lets it go on; the agent using the tablet, with Stop and 🛑;
 * stopped or paused until the next message; or what keeps it from working, with a way to
 * TabDesk's settings. Nothing while all is quiet (🛑 is in the chat's menu then).
 */
private fun tabletBarText(state: ChatState): TabletBarText? {
    val tablet = state.tablet ?: return null
    if (!state.isConnected) return null
    // First: whatever TabDesk says, nothing gets through until the user lets it.
    if (tablet.held) {
        return TabletBarText("🛑 On hold. The agent can't use this tablet until you let it.", TabletBarText.Button.Release, TabletBarText.Look.Held)
    }
    val status = tablet.status
    val open = TabletBarText.Button.OpenTabDesk
    when (status.reach) {
        TabletStatus.Reach.Off, TabletStatus.Reach.Connecting -> return null
        TabletStatus.Reach.Connected -> {}
        else -> return TabletBarText(status.unavailable!!.replaceFirstChar { it.uppercase() } + ".")
    }
    when (status.refusal) {
        null -> {}
        TabletStatus.Refusal.SwitchedOff -> return TabletBarText("Agents are switched off in TabDesk.", open)
        TabletStatus.Refusal.Lapsed -> return TabletBarText("TabDesk's approval of DroidACP ran out (it lasts 7 days).", open)
        TabletStatus.Refusal.CertificateChanged ->
            return TabletBarText("TabDesk approved another copy of DroidACP (a debug build counts as another app). Approve this one.", open)
        TabletStatus.Refusal.Revoked -> return TabletBarText("DroidACP's approval was revoked in TabDesk.", open)
        TabletStatus.Refusal.NotApproved ->
            return TabletBarText("TabDesk hasn't approved DroidACP yet. Approve it in TabDesk → Customize → Agent.", open)
    }
    if (!tablet.offered) return TabletBarText("This PlasmaLLM can't use a tablet yet. Update PlasmaLLM on the computer.")
    val hold = TabletBarText.Button.Hold
    return when {
        tablet.paused -> TabletBarText("Paused because you touched the screen. Send a message to let the agent go on.", hold)
        tablet.stopped -> TabletBarText("Stopped. The agent can use the tablet again when you send the next message.", hold)
        tablet.inUse -> TabletBarText(
            "The agent is using this tablet",
            listOf(TabletBarText.Button.Stop, hold),
            TabletBarText.Look.Active,
        )
        state.plasmaLlm?.tabletTools == false ->
            TabletBarText("The computer doesn't let the agent use a tablet: PlasmaLLM's setting “Let the agent use a connected tablet” is off.")
        else -> null
    }
}

@Composable
fun TabletBar(
    state: ChatState,
    onStop: () -> Unit,
    onHold: () -> Unit,
    /** "Let it go on": the caller asks to confirm first ([ReleaseTabletDialog]). */
    onRelease: () -> Unit,
    onOpenTabDesk: () -> Unit,
) {
    val bar = tabletBarText(state) ?: return
    val colors = MaterialTheme.colorScheme
    Surface(
        color = when (bar.look) {
            TabletBarText.Look.Held -> colors.errorContainer
            TabletBarText.Look.Active -> colors.tertiaryContainer
            TabletBarText.Look.Quiet -> colors.secondaryContainer
        },
        contentColor = when (bar.look) {
            TabletBarText.Look.Held -> colors.onErrorContainer
            TabletBarText.Look.Active -> colors.onTertiaryContainer
            TabletBarText.Look.Quiet -> colors.onSecondaryContainer
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                bar.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
            )
            for (button in bar.buttons) {
                when (button) {
                    TabletBarText.Button.Stop -> TextButton(onClick = onStop) { Text("Stop", color = colors.error) }
                    TabletBarText.Button.Hold -> HoldButton(onHold)
                    TabletBarText.Button.Release -> TextButton(onClick = onRelease) { Text("Let it go on") }
                    TabletBarText.Button.OpenTabDesk -> TextButton(onClick = onOpenTabDesk) { Text("Open TabDesk") }
                }
            }
        }
    }
}

/** 🛑: Stop, and the agent stays off this tablet until the user lets it go on. */
@Composable
private fun HoldButton(onHold: () -> Unit) {
    TextButton(
        onClick = onHold,
        modifier = Modifier.semantics { contentDescription = "Hold: stop, and keep the agent off this tablet until you let it go on" },
    ) { Text("🛑 Hold", color = MaterialTheme.colorScheme.error) }
}

/**
 * Asked before "Let the tablet go on" is sent from another device (plan D23): the message lifts
 * a hold on the tablet, so a stray tap on the menu shouldn't be enough.
 */
@Composable
fun LetTabletGoOnDialog(onSend: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Let the agent use the tablet again?") },
        text = {
            Text(
                "This sends “Let the tablet go on” to the chat. If the tablet is on hold (🛑), it lets the agent " +
                    "read the screen of the apps you allowed in TabDesk, take screenshots of them, and open them again.",
            )
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Let it go on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep on hold") } },
    )
}

/** Asked before a hold is lifted, from the bar or the chat's menu: a tap on it shouldn't be enough. */
@Composable
fun ReleaseTabletDialog(onRelease: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Let the agent use this tablet again?") },
        text = {
            Text(
                "It can then read the screen of the apps you allowed in TabDesk, take screenshots of them, and open them, " +
                    "until you stop it or put it on hold again.",
            )
        },
        confirmButton = { TextButton(onClick = onRelease) { Text("Let it go on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep on hold") } },
    )
}
