package com.droidacp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidacp.core.chat.TabletUse
import com.droidacp.core.tablet.TabletStatus

/**
 * TabDesk's state for the settings (plan D2), in plain words, and whether TabDesk's page
 * can help. [tablet] is null when this agent isn't the one connected, or doesn't use the
 * tablet yet (the switch was turned on here and isn't saved).
 */
private fun tabletState(tablet: TabletUse?, connectedHere: Boolean, laptopAllows: Boolean?): Pair<String, Boolean> {
    if (tablet == null) {
        val first = "The first time, TabDesk asks you to approve DroidACP in Customize → Agent."
        return (if (connectedHere) "Save to connect to TabDesk. $first" else "Shown while this agent is connected. $first") to false
    }
    val status = tablet.status
    val words = when {
        status.reach == TabletStatus.Reach.Connecting || status.reach == TabletStatus.Reach.Off -> "Connecting to TabDesk…" to false
        !status.connected -> (status.unavailable!!.replaceFirstChar { it.uppercase() } + ".") to false
        else -> when (status.refusal) {
            null -> "TabDesk: Approved." to false
            TabletStatus.Refusal.SwitchedOff -> "TabDesk: agents are switched off." to true
            TabletStatus.Refusal.Lapsed -> "TabDesk: Approval ran out (it lasts 7 days)." to true
            TabletStatus.Refusal.CertificateChanged ->
                "TabDesk approved another copy of DroidACP (a debug build counts as another app)." to true
            TabletStatus.Refusal.Revoked -> "TabDesk: Approval revoked." to true
            TabletStatus.Refusal.NotApproved -> "TabDesk: Not approved yet." to true
        }
    }
    val laptop = when {
        laptopAllows == false -> " Turned off on the laptop (PlasmaLLM's “Let the agent use a connected tablet”)."
        !tablet.offered -> " This PlasmaLLM can't use a tablet yet: update it on the computer."
        else -> ""
    }
    return (words.first + laptop) to words.second
}

/**
 * "Let the agent use this tablet (through TabDesk)" on a PlasmaLLM agent, shown only where
 * TabDesk is installed, with TabDesk's state and "Quiet on this device" under it.
 */
@Composable
fun TabletSettings(
    useTablet: Boolean,
    onUseTablet: (Boolean) -> Unit,
    quietHere: Boolean,
    onQuietHere: (Boolean) -> Unit,
    tablet: TabletUse?,
    connectedHere: Boolean,
    laptopAllows: Boolean?,
    onOpenTabDesk: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SwitchRow(
                title = "Let the agent use this tablet (through TabDesk)",
                detail = "The agent in PlasmaLLM's chat can read the apps you allow in TabDesk, within TabDesk's limits. " +
                    "This device only passes its calls on; TabDesk decides what's allowed.",
                checked = useTablet,
                onChange = onUseTablet,
            )
            if (useTablet) {
                val (state, needsTabDesk) = tabletState(tablet, connectedHere, laptopAllows)
                Text(state, style = MaterialTheme.typography.bodyMedium)
                if (needsTabDesk) {
                    OutlinedButton(onClick = onOpenTabDesk) { Text("Open TabDesk → Customize → Agent") }
                }
                Text(
                    "Claude Code fixes a chat's tools when the chat starts, so once the laptop's setting is on, the tablet " +
                        "works in chats started from then on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(
                    title = "Quiet on this device",
                    detail = "No “finished”, permission or problem notifications from this agent here: your phone has them.",
                    checked = quietHere,
                    onChange = onQuietHere,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
