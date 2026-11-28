@file:OptIn(ExperimentalMaterial3Api::class)

package com.droidacp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidacp.core.acp.ConfigOption
import com.droidacp.core.acp.ToolCall
import com.droidacp.core.acp.ToolContent
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.PendingPermission
import com.droidacp.core.chat.ProfileKind
import kotlinx.coroutines.flow.first

/**
 * The agent asks before doing something (editing a file, running a command...).
 * ACP gives us the choices — typically allow once / always / reject — and we must
 * answer with one of them, or "cancelled".
 */
@Composable
fun PermissionDialog(permission: PendingPermission, call: ToolCall?, onAnswer: (String?) -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 640.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${kindIcon(permission.kind)}  Allow the agent to ${kindVerb(permission.kind)}?",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(permission.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)

                if (call != null && (call.content.isNotEmpty() || call.rawInput != null || call.locations.isNotEmpty())) {
                    Column(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Each path once: a diff shows its file's name, so its path goes here, above it.
                        val diffs = call.content.filterIsInstance<ToolContent.Diff>()
                        val paths = (call.locations.map { it.path } + diffs.map { it.path }).distinct()
                        for (path in paths) {
                            Text(path, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                        }
                        val input = call.rawInput
                        // Wrapped, not scrolled sideways: you should see the whole change before allowing it.
                        if (input != null && diffs.isEmpty()) ToolInputView(input, call.kind, wrap = true, whole = true)
                        for (content in call.content) {
                            if (content is ToolContent.Diff) DiffView(content, wrap = true, showPath = false, whole = true) else ToolContentView(content, wrap = true, whole = true)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in permission.options) {
                        val modifier = Modifier.fillMaxWidth()
                        when (option.kind) {
                            "allow_once" -> Button(onClick = { onAnswer(option.optionId) }, modifier = modifier) { Text(option.name) }
                            "allow_always" -> FilledTonalButton(onClick = { onAnswer(option.optionId) }, modifier = modifier) { Text(option.name) }
                            else -> OutlinedButton(onClick = { onAnswer(option.optionId) }, modifier = modifier) { Text(option.name) }
                        }
                    }
                    TextButton(onClick = { onAnswer(null) }, modifier = Modifier.align(Alignment.End)) { Text("Cancel the turn") }
                }
            }
        }
    }
}

@Composable
fun SessionSettingsSheet(
    state: ChatState,
    actions: ChatActions,
    /** The developer view or the standard one ([com.droidacp.core.chat.ChatView]). */
    developer: Boolean,
    /** Opened from the context bar: scrolled to "Context", near the end. */
    atContext: Boolean = false,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val scroll = rememberScrollState()
        if (atContext) {
            // Once there's somewhere to scroll; with a short list it all shows anyway.
            LaunchedEffect(Unit) { scroll.animateScrollTo(snapshotFlow { scroll.maxValue }.first { it > 0 }) }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Session settings", style = MaterialTheme.typography.titleLarge)

            SectionTitle("View")
            ChoiceRow("Standard", "The conversation and its pictures", selected = !developer) { actions.setDeveloperView(false) }
            ChoiceRow("Developer", "Also the agent's tool calls, thinking and plans", selected = developer) { actions.setDeveloperView(true) }
            Text("For every agent on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (state.modes.isNotEmpty() && !state.hasModeConfigOption) {
                SectionTitle("Mode")
                for (mode in state.modes) {
                    ChoiceRow(mode.name, mode.description, selected = mode.id == state.currentModeId) { actions.setMode(mode.id) }
                }
            }

            for (option in state.configOptions) {
                when (option) {
                    is ConfigOption.Select -> {
                        SectionTitle(option.name)
                        val description = option.description
                        if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
                        var lastGroup: String? = null
                        for (choice in option.choices) {
                            val group = choice.group
                            if (group != null && group != lastGroup) {
                                Text(group, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                                lastGroup = group
                            }
                            ChoiceRow(choice.name, choice.description, selected = choice.value == option.currentValue) {
                                actions.setConfig(option, choice.value)
                            }
                        }
                    }
                    is ConfigOption.Toggle -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(option.name, style = MaterialTheme.typography.titleSmall)
                            val description = option.description
                            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = option.currentValue, onCheckedChange = { actions.setConfig(option, it) })
                    }
                }
            }

            val usage = state.usage
            if (usage != null) {
                SectionTitle("Context")
                Text("${compactNumber(usage.used)} of ${compactNumber(usage.size)} tokens used")
                LinearProgressIndicator(progress = { usage.fraction }, modifier = Modifier.fillMaxWidth())
                val cost = usage.costAmount
                if (cost != null) Text("Cost so far: ${String.format("%.4f", cost)} ${usage.costCurrency ?: ""}", style = MaterialTheme.typography.bodySmall)
            }

            SectionTitle("About this connection")
            SelectionContainer {
                Column {
                    InfoLine("Agent", listOfNotNull(state.agentName.takeIf { state.agent != null }, state.agentVersion).joinToString(" "))
                    val plasma = state.plasmaLlm
                    InfoLine(if (state.profile?.kind == ProfileKind.Bridge) "Bridge" else "Runs", state.profile?.address ?: "")
                    if (plasma != null) InfoLine("PlasmaLLM", listOfNotNull(plasma.version, "companion protocol ${plasma.protocol}").joinToString(", "))
                    InfoLine("Folder", state.folder ?: "")
                    InfoLine("Session", state.sessionId ?: "")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.width(1.dp))
    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun ChoiceRow(name: String, description: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
