package com.droidacp

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidacp.core.acp.ConfigOption
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.ImageAttachment
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import com.droidacp.service.Notifications
import com.droidacp.ui.AndroidImageDecoder
import com.droidacp.ui.AttachmentThumb
import com.droidacp.ui.ChatActions
import com.droidacp.ui.ChatScreen
import com.droidacp.ui.DroidAcpTheme
import com.droidacp.ui.ImagePreparer
import com.droidacp.ui.LocalImageDecoder
import com.droidacp.ui.LogScreen
import com.droidacp.ui.PreparedImage
import com.droidacp.ui.ProfileEditScreen
import com.droidacp.ui.ProfilesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    private val app: DroidAcpApp get() = DroidAcpApp.get(this)

    // Bumped by intents from the widget/notifications; the UI reacts to the change.
    private val openChatRequests = mutableIntStateOf(0)
    private val focusRequests = mutableIntStateOf(0)

    // A message that couldn't be sent from a notification, waiting to go back in the message box.
    private val draft = mutableStateOf<String?>(null)

    // Images picked for the next message.
    private val attachments = mutableStateListOf<PreparedImage>()
    private val nextAttachmentId = AtomicLong(1)

    private val pickImages = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris -> addImages(uris) }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        maybeAskForNotifications()

        setContent {
            DroidAcpTheme {
                CompositionLocalProvider(LocalImageDecoder provides AndroidImageDecoder) {
                    AppRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Widget and notification taps open the chat; "Ask" also focuses the text box. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)) return
        val status = app.controller.state.value.status
        if (status is ConnectionStatus.Disconnected || status is ConnectionStatus.Failed) {
            // Nothing connected: reconnect to the agent used last time, if any.
            val last = app.profiles.get(app.profiles.lastProfileId) ?: return
            connect(last, resume = app.profiles.lastSessionId(last.id))
        }
        intent.getStringExtra(EXTRA_DRAFT)?.let { draft.value = it }
        openChatRequests.intValue++
        if (intent.getBooleanExtra(EXTRA_FOCUS_INPUT, false)) focusRequests.intValue++
    }

    private fun connect(profile: Profile, resume: String?, startNew: Boolean = false) {
        attachments.clear()
        app.profiles.lastProfileId = profile.id
        app.controller.connect(profile, resumeSessionId = resume, startNew = startNew)
    }

    /** Remove a saved agent, disconnecting first if it's the one connected. */
    private fun deleteAgent(profile: Profile) {
        if (app.controller.state.value.profile?.id == profile.id) app.controller.disconnect()
        app.profiles.delete(profile.id)
    }

    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Notifications.canNotify(this)) return
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotifications", false)) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        app.appScope.launch(Dispatchers.IO) {
            val prepared = uris.mapNotNull { ImagePreparer.prepare(this@MainActivity, it, nextAttachmentId.getAndIncrement()) }
            withContext(Dispatchers.Main) { attachments += prepared }
        }
    }

    private enum class Screen { Profiles, Chat, Edit, Log }

    @Composable
    private fun AppRoot() {
        val controller = app.controller
        val state by controller.state.collectAsStateWithLifecycle()
        val profiles by app.profiles.profiles.collectAsStateWithLifecycle()
        val log by controller.log.collectAsStateWithLifecycle()
        val bridgeFolderHistory by app.folders.recent(ProfileKind.Bridge).collectAsStateWithLifecycle()
        val localFolderHistory by app.folders.recent(ProfileKind.Local).collectAsStateWithLifecycle()

        val startOnChat = state.status == ConnectionStatus.Connecting || state.status == ConnectionStatus.Connected
        var screen by rememberSaveable { mutableStateOf(if (startOnChat) Screen.Chat else Screen.Profiles) }
        var logReturnTo by rememberSaveable { mutableStateOf(Screen.Profiles) }
        var editingId by rememberSaveable { mutableStateOf<String?>(null) }
        // "Copy and edit": the edit screen starts from a copy of editingId's agent and saves a new one.
        var copying by rememberSaveable { mutableStateOf(false) }

        val openChat = openChatRequests.intValue
        LaunchedEffect(openChat) { if (openChat > 0) screen = Screen.Chat }

        BackHandler(enabled = screen != Screen.Profiles) {
            screen = if (screen == Screen.Log) logReturnTo else Screen.Profiles
        }

        val chatActions = remember {
            object : ChatActions {
                override fun back() {
                    screen = Screen.Profiles
                }

                override fun send(text: String): Boolean {
                    val images = attachments.map { ImageAttachment(it.mimeType, it.base64) }
                    val sent = controller.sendPrompt(text, images)
                    if (sent) attachments.clear()
                    return sent
                }

                override fun cancel() = controller.cancelTurn()

                override fun pickImage() {
                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }

                override fun removeAttachment(id: Long) {
                    attachments.removeAll { it.id == id }
                }

                override fun setMode(modeId: String) = controller.setMode(modeId)
                override fun setConfig(option: ConfigOption, value: Any) = controller.setConfigOption(option, value)
                override fun newSession() = controller.newSession()
                override fun showHistory() = controller.refreshPastSessions()
                override fun openSession(sessionId: String) = controller.openPastSession(sessionId)
                override fun authenticate(methodId: String) = controller.authenticate(methodId)
                override fun answerPermission(requestId: Long, optionId: String?) = controller.answerPermission(requestId, optionId)
                override fun reconnect() = controller.reconnect()
                override fun disconnect() {
                    controller.disconnect()
                    screen = Screen.Profiles
                }

                override fun openLog() {
                    logReturnTo = Screen.Chat
                    screen = Screen.Log
                }

                override fun draftUsed() {
                    draft.value = null
                }
            }
        }

        when (screen) {
            Screen.Profiles -> ProfilesScreen(
                profiles = profiles,
                state = state,
                hasLastSession = { p -> app.profiles.lastSessionId(p.id) != null },
                onConnect = { p ->
                    connect(p, resume = null)
                    screen = Screen.Chat
                },
                onStartNew = { p ->
                    connect(p, resume = null, startNew = true)
                    screen = Screen.Chat
                },
                onResume = { p ->
                    connect(p, resume = app.profiles.lastSessionId(p.id))
                    screen = Screen.Chat
                },
                onOpenChat = { screen = Screen.Chat },
                onAdd = {
                    editingId = null
                    copying = false
                    screen = Screen.Edit
                },
                onEdit = { p ->
                    editingId = p.id
                    copying = false
                    screen = Screen.Edit
                },
                onCopy = { p ->
                    editingId = p.id
                    copying = true
                    screen = Screen.Edit
                },
                onDelete = ::deleteAgent,
                onOpenLog = {
                    logReturnTo = Screen.Profiles
                    screen = Screen.Log
                },
            )

            Screen.Chat -> ChatScreen(
                state = state,
                actions = chatActions,
                attachments = attachments.map { AttachmentThumb(it.id, it.thumb) },
                focusRequest = focusRequests.intValue,
                draft = draft.value,
            )

            Screen.Edit -> ProfileEditScreen(
                initial = app.profiles.get(editingId),
                copy = copying,
                takenNames = profiles.map { it.name },
                connected = !copying && editingId != null && state.profile?.id == editingId && state.status != ConnectionStatus.Disconnected,
                recentFolders = { kind ->
                    when (kind) {
                        // PlasmaLLM runs on the same kind of computer as a bridge: share its folders.
                        ProfileKind.Bridge, ProfileKind.PlasmaLlm -> bridgeFolderHistory
                        ProfileKind.Local -> localFolderHistory
                        ProfileKind.Cloud -> emptyList()
                    }
                },
                onForgetFolder = { kind, path -> app.folders.forget(if (kind == ProfileKind.PlasmaLlm) ProfileKind.Bridge else kind, path) },
                bridgeFolders = app.bridgeFolders,
                onSave = { p ->
                    when {
                        p.kind == ProfileKind.Cloud -> {}
                        p.kind == ProfileKind.PlasmaLlm -> if (p.cwd.isNotBlank()) app.folders.remember(ProfileKind.Bridge, p.cwd)
                        else -> app.folders.remember(p.kind, p.cwd)
                    }
                    app.profiles.save(p)
                    screen = Screen.Profiles
                },
                onDelete = { p ->
                    deleteAgent(p)
                    screen = Screen.Profiles
                },
                onBack = { screen = Screen.Profiles },
            )

            Screen.Log -> LogScreen(
                entries = log,
                onBack = { screen = logReturnTo },
                onClear = controller::clearLog,
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "com.droidacp.extra.OPEN_CHAT"
        const val EXTRA_FOCUS_INPUT = "com.droidacp.extra.FOCUS_INPUT"
        const val EXTRA_DRAFT = "com.droidacp.extra.DRAFT"
    }
}
