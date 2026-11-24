package com.droidacp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidacp.core.acp.BridgeFolderException
import com.droidacp.core.acp.BridgeFolders
import com.droidacp.core.acp.FolderListing
import kotlinx.coroutines.CancellationException

/**
 * A folder path you can type, or pick from the folders you used before (the arrow
 * on the right). Each remembered folder has an ✕ to remove it from the list.
 */
@Composable
fun FolderField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String,
    recent: List<String>,
    onForget: (String) -> Unit,
    placeholder: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val others = recent.filterNot { it == value.trim() }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = { Text(supportingText) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            trailingIcon = if (others.isEmpty()) {
                null
            } else {
                { IconButton(onClick = { open = true }) { Icon(Icons.Default.ArrowDropDown, contentDescription = "Folders used before") } }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open && others.isNotEmpty(), onDismissRequest = { open = false }) {
            others.forEach { path ->
                DropdownMenuItem(
                    text = { Text(path, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onValueChange(path)
                        open = false
                    },
                    trailingIcon = {
                        IconButton(onClick = { onForget(path) }) { Icon(Icons.Default.Close, contentDescription = "Remove from list", Modifier.size(18.dp)) }
                    },
                )
            }
        }
    }
}

/**
 * Browse the folders on the bridge's computer and pick one. Starts in [start] if
 * given, otherwise in the user's home folder there.
 */
@Composable
fun BridgeFolderDialog(
    folders: BridgeFolders,
    bridgeUrl: String,
    token: String,
    start: String?,
    onPick: (path: String, home: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var target by remember { mutableStateOf(start?.takeIf { it.isNotBlank() }) }
    var listing by remember { mutableStateOf<FolderListing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    // Runs again whenever `target` changes; leaving the dialog cancels a request in flight.
    LaunchedEffect(target) {
        loading = true
        error = null
        try {
            listing = folders.list(bridgeUrl, token, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeFolderException) {
            // A typed-in path that doesn't exist: show the home folder instead of a dead end.
            if (target != null && listing == null && "No such folder" in (e.message ?: "")) {
                target = null
                return@LaunchedEffect
            }
            error = e.message
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose the project folder") },
        text = {
            Column {
                val current = listing
                if (current != null) {
                    Text(current.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { target = current.parent }, enabled = current.parent != null && !loading) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                            Text("Up")
                        }
                        TextButton(onClick = { target = current.home }, enabled = current.path != current.home && !loading) {
                            Icon(Icons.Default.Home, contentDescription = null, Modifier.size(18.dp))
                            Text(" Home")
                        }
                    }
                    HorizontalDivider()
                }
                when {
                    loading && current == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    current != null && current.folders.isEmpty() -> Text("No folders in here.", modifier = Modifier.padding(top = 8.dp))
                    current != null -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(current.folders) { name ->
                            Text(
                                "📁  $name",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !loading) { target = current.child(name) }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                            )
                        }
                        if (current.truncated) item { Text("(Only the first 500 folders are shown.)", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { listing?.let { onPick(it.path, it.home) } }, enabled = listing != null && !loading) { Text("Use this folder") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
