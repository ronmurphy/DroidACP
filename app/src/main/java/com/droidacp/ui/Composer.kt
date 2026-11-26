package com.droidacp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidacp.core.acp.ConfigOption
import com.droidacp.core.chat.ChatState
import kotlinx.coroutines.delay

/**
 * The message box at the bottom: slash-command suggestions, image attachments,
 * the text field, send/stop, and quick pickers for mode and model.
 */
@Composable
fun Composer(
    state: ChatState,
    actions: ChatActions,
    attachments: List<AttachmentThumb>,
    focusRequest: Int,
    draft: String? = null,
    onSent: () -> Unit,
) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }

    // A reply from a notification that couldn't be sent: put it back here so it isn't lost.
    LaunchedEffect(draft) {
        if (draft != null) {
            val text = if (field.text.isBlank()) draft else field.text.trimEnd() + "\n\n" + draft
            field = TextFieldValue(text, TextRange(text.length))
            actions.draftUsed()
        }
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // The widget's "Ask" button bumps focusRequest so we open with the keyboard up.
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            delay(200)
            try {
                focusRequester.requestFocus()
                keyboard?.show()
            } catch (e: IllegalStateException) {
                // Text field not on screen yet; nothing to focus.
            }
        }
    }

    val canSend = state.canChat && !state.busy && (field.text.isNotBlank() || attachments.isNotEmpty())
    val send = {
        if (canSend && actions.send(field.text.trim())) {
            field = TextFieldValue("")
            onSent()
        }
    }

    // "/rev" → suggest commands starting with "rev".
    val text = field.text
    val slashQuery = if (text.startsWith("/") && ' ' !in text && '\n' !in text) text.drop(1) else null
    val suggestions = if (slashQuery != null) {
        state.commands.filter { it.name.startsWith(slashQuery, ignoreCase = true) }.take(6)
    } else {
        emptyList()
    }

    Surface(tonalElevation = 3.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 860.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                TabletBar(state, onStop = actions::stopTablet, onOpenTabDesk = actions::openTabDesk)
                if (suggestions.isNotEmpty()) {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        for (command in suggestions) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val newText = "/${command.name} "
                                        field = TextFieldValue(newText, selection = TextRange(newText.length))
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text("/${command.name}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                val hint = command.inputHint
                                Text(
                                    command.description + if (hint != null) "  ·  $hint" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }

                if (attachments.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        for (attachment in attachments) {
                            Box {
                                val image = attachment.image
                                if (image != null) {
                                    Image(
                                        bitmap = image,
                                        contentDescription = "Attached image",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                    )
                                } else {
                                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(64.dp)) {}
                                }
                                FilledTonalIconButton(
                                    onClick = { actions.removeAttachment(attachment.id) },
                                    modifier = Modifier
                                        .size(24.dp)
                                        .align(Alignment.TopEnd),
                                ) { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp)) }
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.Bottom) {
                    if (state.capabilities.images) {
                        IconButton(onClick = actions::pickImage, enabled = state.canChat) {
                            Icon(Icons.Default.Add, contentDescription = "Attach image")
                        }
                    }
                    OutlinedTextField(
                        value = field,
                        onValueChange = { field = it },
                        enabled = state.canChat,
                        placeholder = { Text(if (state.canChat) "Message ${state.agentName}…" else "Not connected") },
                        maxLines = 6,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                // Ctrl+Enter sends (handy with a keyboard cover).
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed) {
                                    send()
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                    Spacer(Modifier.width(8.dp))
                    if (state.busy) {
                        FilledTonalIconButton(onClick = actions::cancel, modifier = Modifier.padding(bottom = 4.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Stop")
                        }
                    } else {
                        FilledIconButton(onClick = { send() }, enabled = canSend, modifier = Modifier.padding(bottom = 4.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }

                QuickPickers(state, actions)
            }
        }
    }
}

/** Mode / model / reasoning pickers as small chips under the text field. */
@Composable
private fun QuickPickers(state: ChatState, actions: ChatActions) {
    val quickOptions = state.configOptions.filterIsInstance<ConfigOption.Select>()
        .filter { it.category == "mode" || it.category == "model" || it.category == "thought_level" }
    val showModes = state.modes.isNotEmpty() && !state.hasModeConfigOption
    val usage = state.usage
    if (!state.canChat || (!showModes && quickOptions.isEmpty() && usage == null)) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 2.dp),
    ) {
        if (showModes) {
            PickerChip(
                label = state.currentModeName ?: "Mode",
                choices = state.modes.map { it.id to it.name },
                selected = state.currentModeId,
                onPick = actions::setMode,
            )
        }
        for (option in quickOptions) {
            PickerChip(
                label = option.currentName,
                choices = option.choices.map { it.value to it.name },
                selected = option.currentValue,
                onPick = { value -> actions.setConfig(option, value) },
            )
        }
        if (usage != null && usage.size > 0) {
            Text(
                "${compactNumber(usage.used)} / ${compactNumber(usage.size)} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PickerChip(label: String, choices: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("$label ▾", maxLines = 1) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((id, name) in choices) {
                DropdownMenuItem(
                    text = { Text(if (id == selected) "✓  $name" else "     $name") },
                    onClick = {
                        open = false
                        if (id != selected) onPick(id)
                    },
                )
            }
        }
    }
}
