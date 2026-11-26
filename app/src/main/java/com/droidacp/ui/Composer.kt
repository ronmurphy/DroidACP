package com.droidacp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.UsageInfo
import kotlinx.coroutines.delay

/**
 * The message box at the bottom: the tablet bar, slash-command suggestions, then one rounded
 * box holding attachments, + (attach), the text, 🛑 while the agent works, and send or stop;
 * under it, a thin bar of the context used. Mode, model and effort are in the chat's settings
 * (the gear).
 */
@Composable
fun Composer(
    state: ChatState,
    actions: ChatActions,
    attachments: List<AttachmentThumb>,
    focusRequest: Int,
    draft: String? = null,
    onSent: () -> Unit,
    /** The bar's "Let it go on": the chat screen asks to confirm (its dialog serves the menu too). */
    onReleaseTablet: () -> Unit,
    /** The context bar was tapped: the chat's settings, at "Context". */
    onOpenContext: () -> Unit,
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
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            ) {
                TabletBar(
                    state,
                    onStop = actions::stopTablet,
                    onHold = actions::holdTablet,
                    onRelease = onReleaseTablet,
                    onOpenTabDesk = actions::openTabDesk,
                )
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

                MessageBox(
                    state = state,
                    actions = actions,
                    attachments = attachments,
                    field = field,
                    onFieldChange = { field = it },
                    canSend = canSend,
                    onSend = send,
                    focusRequester = focusRequester,
                )

                ContextBar(state.usage.takeIf { state.canChat }, onOpenContext)
            }
        }
    }
}

/** The rounded box: attachments above; +, the text, 🛑 while the agent works, and send or stop. */
@Composable
private fun MessageBox(
    state: ChatState,
    actions: ChatActions,
    attachments: List<AttachmentThumb>,
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
    focusRequester: FocusRequester,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = colors.surfaceContainerHigh,
        border = BorderStroke(if (focused) 1.5.dp else 1.dp, if (focused) colors.primary else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (attachments.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
                ) {
                    for (attachment in attachments) AttachmentThumbView(attachment) { actions.removeAttachment(attachment.id) }
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                if (state.capabilities.images) {
                    IconButton(onClick = actions::pickImage, enabled = state.canChat, modifier = Modifier.padding(bottom = 4.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "Attach image")
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }
                TextField(
                    value = field,
                    onValueChange = onFieldChange,
                    enabled = state.canChat,
                    placeholder = {
                        Text(
                            if (state.canChat) "Message ${state.agentName}…" else "Not connected",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    maxLines = 6,
                    interactionSource = interaction,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            // Ctrl+Enter sends (handy with a keyboard cover).
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed) {
                                onSend()
                                true
                            } else {
                                false
                            }
                        },
                )
                if (state.canHalt) {
                    HaltButton(actions::halt)
                    Spacer(Modifier.width(4.dp))
                }
                if (state.busy) {
                    FilledTonalIconButton(
                        onClick = actions::cancel,
                        modifier = Modifier.padding(bottom = 4.dp, end = 4.dp),
                    ) {
                        // A square: stop the agent's reply.
                        Box(
                            Modifier
                                .size(14.dp)
                                .background(MaterialTheme.colorScheme.onSecondaryContainer, RoundedCornerShape(3.dp))
                                .semantics { contentDescription = "Stop the agent's reply" },
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier.padding(bottom = 4.dp, end = 4.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

/** 🛑 while the agent works (plan D22): stop it, and put the tablet on hold, from any device. */
@Composable
private fun HaltButton(onHalt: () -> Unit) {
    FilledTonalIconButton(
        onClick = onHalt,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .padding(bottom = 4.dp)
            .semantics { contentDescription = "Halt: stop the agent and put the tablet on hold" },
    ) { Text("🛑", fontSize = 18.sp) }
}

@Composable
private fun AttachmentThumbView(attachment: AttachmentThumb, onRemove: () -> Unit) {
    Box {
        val image = attachment.image
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Attached image",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(56.dp)) {}
        }
        FilledTonalIconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(22.dp)
                .align(Alignment.TopEnd),
        ) { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(13.dp)) }
    }
}

// Fixed, not the theme's: its error colour is a pale pink in dark mode, which doesn't read as "full".
private val Amber = Color(0xFFE0A030)
private val Red = Color(0xFFE5484D)

/** The context bar's colour: the theme's, fading through amber to red as the context fills. */
@Composable
private fun contextColor(fraction: Float): Color {
    val primary = MaterialTheme.colorScheme.primary
    return when {
        fraction < 0.6f -> primary
        fraction < 0.8f -> lerp(primary, Amber, (fraction - 0.6f) / 0.2f)
        fraction < 0.95f -> lerp(Amber, Red, (fraction - 0.8f) / 0.15f)
        else -> Red
    }
}

/**
 * A thin bar under the message box: how much of the agent's context the chat uses. It fills,
 * and fades to red as it nears full (and the agent's compacting); after a compaction the next
 * usage the agent reports is smaller, and it drops back by itself. Tapped: the numbers.
 */
@Composable
private fun ContextBar(usage: UsageInfo?, onClick: () -> Unit) {
    if (usage == null || usage.size <= 0) return
    val fraction by animateFloatAsState(usage.fraction, label = "context")
    val color by animateColorAsState(contextColor(usage.fraction), label = "context color")
    val description = "Context: ${compactNumber(usage.used)} of ${compactNumber(usage.size)} tokens used"
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClickLabel = "Show the context", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 18.dp, vertical = 7.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}
