@file:OptIn(ExperimentalMaterial3Api::class)

package com.droidacp.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidacp.core.acp.BridgeCommand
import com.droidacp.core.acp.BridgeFolders
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import com.droidacp.data.StorageAccess

/** Home screen: your saved agents. Tap one to connect. */
@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    state: ChatState,
    hasLastSession: (Profile) -> Boolean,
    onConnect: (Profile) -> Unit,
    onResume: (Profile) -> Unit,
    onOpenChat: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Profile) -> Unit,
    onOpenLog: () -> Unit,
) {
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DroidACP") },
                actions = {
                    IconButton(onClick = { helpOpen = true }) { Icon(Icons.Default.Info, contentDescription = "How to set up") }
                    TextButton(onClick = onOpenLog) { Text("Log") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add agent") },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val side = ((maxWidth - 760.dp) / 2).coerceAtLeast(16.dp)
            if (profiles.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = side, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Talk to your coding agents from this tablet", style = MaterialTheme.typography.headlineSmall)
                    SetupHelp()
                    Button(onClick = onAdd) { Text("Add your first agent") }
                    Spacer(Modifier.height(72.dp))
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        val active = state.profile?.id == profile.id && state.status !is ConnectionStatus.Disconnected
                        ProfileCard(
                            profile = profile,
                            state = if (active) state else null,
                            hasLastSession = hasLastSession(profile),
                            onClick = { if (active && state.status !is ConnectionStatus.Failed) onOpenChat() else onConnect(profile) },
                            onResume = { onResume(profile) },
                            onEdit = { onEdit(profile) },
                        )
                    }
                }
            }
        }
    }
    if (helpOpen) {
        AlertDialog(
            onDismissRequest = { helpOpen = false },
            confirmButton = { TextButton(onClick = { helpOpen = false }) { Text("Got it") } },
            title = { Text("Setting up an agent") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { SetupHelp() } },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    state: ChatState?,
    hasLastSession: Boolean,
    onClick: () -> Unit,
    onResume: () -> Unit,
    onEdit: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(profile.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(profile.cwd, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state != null) {
                    Spacer(Modifier.height(4.dp))
                    val status = state.status
                    val (label, color) = when {
                        status is ConnectionStatus.Failed -> "Disconnected — tap to reconnect" to MaterialTheme.colorScheme.error
                        status is ConnectionStatus.Connecting -> "Connecting…" to MaterialTheme.colorScheme.primary
                        state.busy -> "● Working — tap to open" to MaterialTheme.colorScheme.primary
                        else -> "● Connected — tap to open" to UiColors.success
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (hasLastSession) {
                        DropdownMenuItem(text = { Text("Continue last session") }, onClick = { menuOpen = false; onResume() })
                    }
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                }
            }
        }
    }
}

@Composable
fun SetupHelp() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("There are three kinds of agent:", style = MaterialTheme.typography.bodyMedium)
        Text("On this device", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "A coding agent built into DroidACP. It calls Claude with your own Anthropic API key and works on a " +
                "folder on this device: reading, editing and searching files, and running shell commands. " +
                "Nothing else to install. Tap Add agent and choose \"On this device\".",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Cloud", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "The same idea, but the agent works in a Linux container hosted by Anthropic, with a real shell, git and " +
                "package managers, and can clone a GitHub repository. Also needs only your Anthropic API key.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("On a computer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "Any agent that speaks the Agent Client Protocol (ACP) — Claude Code, Codex, Gemini CLI and others — " +
                "running on a computer. You start a small bridge next to the agent and connect to it from here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("1. On the computer (or in Termux), with Node.js installed, from the DroidACP folder:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        SelectionContainer {
            CodeBlock("node bridge/acp-bridge.js --cwd ~/myproject -- npx -y @agentclientprotocol/claude-agent-acp", "shell")
        }
        Text(
            "Get the DroidACP folder with `git clone https://github.com/ronmurphy/DroidACP`. --cwd is the project " +
                "the agent works on. The bridge prints addresses like ws://192.168.1.20:8765 and a token. " +
                "With Tailscale, use the 100.x address. Sign in to the agent on the computer first (for Claude Code, run `claude` once).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("2. Here, tap Add agent and enter that address, the token, and the project folder.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(
            "Other agents: replace the part after -- with e.g. `npx -y @agentclientprotocol/codex-acp` or `gemini --acp`. " +
                "To try the app without an agent, use `node bridge/mock-agent.js`.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Add or edit a saved agent. */
@Composable
fun ProfileEditScreen(
    initial: Profile?,
    recentFolders: (ProfileKind) -> List<String>,
    onForgetFolder: (ProfileKind, String) -> Unit,
    bridgeFolders: BridgeFolders,
    onSave: (Profile) -> Unit,
    onDelete: (Profile) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // New agents start as "on this device"; it needs the least setup.
    // (Kept as the kind's id, a String, because that is something rememberSaveable can save.)
    var kindId by rememberSaveable { mutableStateOf((initial?.kind ?: ProfileKind.Local).id) }
    val kind = ProfileKind.fromId(kindId)
    val local = kind == ProfileKind.Local
    val cloud = kind == ProfileKind.Cloud
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var url by rememberSaveable { mutableStateOf(initial?.url?.ifEmpty { "ws://" } ?: "ws://") }
    var token by rememberSaveable { mutableStateOf(initial?.token ?: "") }
    var apiKey by rememberSaveable { mutableStateOf(initial?.apiKey ?: "") }
    // Each kind keeps its own folder text, so switching kinds doesn't lose what you typed.
    // A new agent starts in the folder used most recently, so you rarely have to type it.
    var bridgeCwd by rememberSaveable {
        mutableStateOf(initial?.takeIf { it.kind == ProfileKind.Bridge }?.cwd ?: recentFolders(ProfileKind.Bridge).firstOrNull() ?: "")
    }
    var localCwd by rememberSaveable {
        mutableStateOf(initial?.takeIf { it.isLocal }?.cwd ?: recentFolders(ProfileKind.Local).firstOrNull() ?: StorageAccess.defaultFolder())
    }
    var browsing by remember { mutableStateOf(false) }
    var repoUrl by rememberSaveable { mutableStateOf(initial?.repoUrl ?: "") }
    var repoToken by rememberSaveable { mutableStateOf(initial?.repoToken ?: "") }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var triedSave by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // File access is granted outside the app (in Settings), so re-check when we come back.
    var storageGranted by remember { mutableStateOf(StorageAccess.isGranted(context)) }
    val openSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        storageGranted = StorageAccess.isGranted(context)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        storageGranted = granted
    }

    val base = initial ?: Profile(name = "", cwd = "")
    val draft = when (kind) {
        ProfileKind.Local -> base.copy(
            name = name.trim(), kind = kind, url = "", token = "", apiKey = apiKey.trim(),
            cwd = localCwd.trim().trimEnd('/').ifEmpty { "/" }, repoUrl = "", repoToken = "",
        )
        ProfileKind.Cloud -> base.copy(
            name = name.trim(), kind = kind, url = "", token = "", apiKey = apiKey.trim(),
            cwd = "/workspace", repoUrl = repoUrl.trim(), repoToken = repoToken.trim(),
        )
        ProfileKind.Bridge -> base.copy(
            name = name.trim(), kind = kind, url = url.trim(), token = token.trim(), apiKey = "",
            cwd = bridgeCwd.trim(), repoUrl = "", repoToken = "",
        )
    }
    val problems = draft.validate()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = { Text(if (initial == null) "Add agent" else "Edit agent") },
                actions = {
                    if (initial != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = local, onClick = { kindId = ProfileKind.Local.id }, label = { Text("On this device") })
                    FilterChip(selected = cloud, onClick = { kindId = ProfileKind.Cloud.id }, label = { Text("Cloud") })
                    FilterChip(selected = kind == ProfileKind.Bridge, onClick = { kindId = ProfileKind.Bridge.id }, label = { Text("On a computer (bridge)") })
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text(if (local) "Claude on this tablet" else if (cloud) "Claude in the cloud" else "Claude on my desktop") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (local || cloud) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("Anthropic API key") },
                        placeholder = { Text("sk-ant-…") },
                        supportingText = { Text("Create one at console.anthropic.com. It is stored encrypted on this device and only sent to Anthropic. Usage is billed to your account.") },
                        singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        trailingIcon = { TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide" else "Show") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (cloud) {
                    OutlinedTextField(
                        value = repoUrl,
                        onValueChange = { repoUrl = it },
                        label = { Text("GitHub repository (optional)") },
                        placeholder = { Text("https://github.com/you/project") },
                        supportingText = { Text("Cloned into the agent's container at the start of each session. Leave empty for an empty workspace.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = repoToken,
                        onValueChange = { repoToken = it },
                        label = { Text("GitHub access token") },
                        supportingText = { Text("Needed with a repository. A fine-grained token with Contents: Read lets the agent clone; Read and write also lets it push.") },
                        singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (local) {
                    FolderField(
                        value = localCwd,
                        onValueChange = { localCwd = it },
                        label = "Project folder on this device",
                        supportingText = "Absolute path. The agent reads, edits and runs commands here. It is created if it doesn't exist.",
                        recent = recentFolders(ProfileKind.Local),
                        onForget = { onForgetFolder(ProfileKind.Local, it) },
                    )
                    if (!storageGranted && StorageAccess.isNeededFor(context, localCwd.trim())) {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("DroidACP needs file access to work in this folder.", style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        openSettings.launch(StorageAccess.settingsIntent(context))
                                    } else {
                                        askPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    }
                                }) { Text("Allow file access") }
                            }
                        }
                    }
                }
                if (kind == ProfileKind.Bridge) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Bridge address") },
                        placeholder = { Text("ws://192.168.1.20:8765") },
                        supportingText = { Text("Printed by acp-bridge when it starts. Use wss:// for TLS.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Token") },
                        supportingText = { Text("Also printed by acp-bridge. Leave empty only if it runs with --no-auth.") },
                        singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        trailingIcon = { TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide" else "Show") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FolderField(
                        value = bridgeCwd,
                        onValueChange = { bridgeCwd = it },
                        label = "Project folder on the agent's computer",
                        placeholder = "/home/me/projects/my-app",
                        supportingText = "Absolute path. The agent reads and edits files here. Type it, pick one you used before, or browse.",
                        recent = recentFolders(ProfileKind.Bridge),
                        onForget = { onForgetFolder(ProfileKind.Bridge, it) },
                    )
                    val canBrowse = url.trim().let { it.startsWith("ws://") || it.startsWith("wss://") } && url.trim().length > 6
                    OutlinedButton(onClick = { browsing = true }, enabled = canBrowse) {
                        Text(if (canBrowse) "Browse the computer's folders…" else "Browse (enter the address first)")
                    }
                    BridgeSetupCard(folder = bridgeCwd.trim())
                }
                if (triedSave) {
                    for (problem in problems) Text("• $problem", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onBack) { Text("Cancel") }
                    Button(onClick = {
                        triedSave = true
                        if (problems.isEmpty()) onSave(draft)
                    }) { Text("Save") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (local) {
                        "The agent has no git, compilers or package managers unless this device provides them: Android's " +
                            "own shell only has basic commands. Pick the model, effort and permission mode from the chat screen."
                    } else if (cloud) {
                        "The agent runs in a Linux container hosted by Anthropic (Claude Managed Agents, a beta service), with a " +
                            "real shell, git and package managers. Each session gets a fresh container; the conversation is kept " +
                            "on Anthropic's side. Billed to your API key: tokens plus a small charge for container time."
                    } else {
                        "Tip: Tailscale or a VPN lets you reach a computer at home from anywhere. " +
                            "Running the bridge in Termux on this tablet works too — use ws://127.0.0.1:8765."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (browsing) {
        BridgeFolderDialog(
            folders = bridgeFolders,
            bridgeUrl = url,
            token = token,
            start = bridgeCwd.trim().ifEmpty { null },
            onPick = { path, _ ->
                bridgeCwd = path
                browsing = false
            },
            onDismiss = { browsing = false },
        )
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${initial.name}?") },
            text = { Text("This only removes the saved agent from this device. Files and saved conversations are kept.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(initial) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Bridge mode needs a few things outside this app: the bridge running on the
 * computer, and usually Tailscale (to reach it) and Termux (to start it over SSH).
 * This card gathers the shortcuts in one place.
 */
@Composable
private fun BridgeSetupCard(folder: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val command = BridgeCommand.forFolder(folder)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Start the bridge on the computer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Run this in the DroidACP folder on the computer: over SSH from Termux, or at the computer itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer { CodeBlock(command, "shell") }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(command))
                    android.widget.Toast.makeText(context, "Command copied", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Copy command") }
                for (app in HelperApp.entries) {
                    // Checked when the screen opens: "Get" becomes "Open" next time after installing.
                    val installed = remember(app) { app.isInstalled(context) }
                    OutlinedButton(onClick = { app.open(context) }) { Text(if (installed) "Open ${app.label}" else "Get ${app.label}") }
                }
            }
        }
    }
}
