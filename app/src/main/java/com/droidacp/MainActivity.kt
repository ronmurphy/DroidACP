package com.droidacp

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
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
import com.droidacp.tablet.TabDeskLink
import com.droidacp.core.acp.OpenElsewhereChoice
import com.droidacp.core.chat.ChatBookmark
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.ImageAttachment
import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import com.droidacp.service.Background
import com.droidacp.service.Notifications
import com.droidacp.ui.AndroidImageDecoder
import com.droidacp.ui.AttachmentThumb
import com.droidacp.ui.ChatActions
import com.droidacp.ui.ChatScreen
import com.droidacp.ui.ChatsPanel
import com.droidacp.ui.FolderBrowse
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

    // The widget's mic button, when the chat is already on screen: what was heard goes in the message box.
    private val listen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !heard.isNullOrBlank()) draft.value = heard
        focusRequests.intValue++
    }

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
            attachments.clear()
            if (!app.reconnectLast()) return
        }
        intent.getStringExtra(EXTRA_DRAFT)?.let { draft.value = it }
        openChatRequests.intValue++
        if (intent.getBooleanExtra(QuickAskActivity.EXTRA_VOICE, false) && QuickAskActivity.canListen(this)) {
            try {
                listen.launch(QuickAskActivity.speechIntent("Ask ${app.controller.state.value.agentName}"))
                return
            } catch (e: ActivityNotFoundException) {
                // Fall back to typing.
            }
        }
        if (intent.getBooleanExtra(EXTRA_FOCUS_INPUT, false)) focusRequests.intValue++
    }

    private fun connect(profile: Profile, resume: String?, startNew: Boolean = false) {
        attachments.clear()
        app.profiles.lastProfileId = profile.id
        val cwd = if (resume != null) app.profiles.lastSessionCwd(profile.id) else null
        app.controller.connect(profile, resumeSessionId = resume, startNew = startNew, resumeCwd = cwd)
    }

    /**
     * The tablet's relay has to stay connected with the screen off: ask, with Android's own
     * dialog, to leave DroidACP out of battery optimisation (nothing if it already is).
     */
    private fun askToStayConnected() = Background.askToBeExempt(this)

    /** Folders are remembered per kind of machine: PlasmaLLM runs on the same kind of computer as a bridge. */
    private fun folderKind(profile: Profile?): ProfileKind? = when (profile?.kind) {
        ProfileKind.Bridge, ProfileKind.PlasmaLlm -> ProfileKind.Bridge
        ProfileKind.Local -> ProfileKind.Local
        else -> null
    }

    /** Remove a saved agent, disconnecting first if it's the one connected. */
    private fun deleteAgent(profile: Profile) {
        if (app.controller.state.value.profile?.id == profile.id) app.controller.disconnect()
        app.profiles.delete(profile.id)
        app.bookmarks.forgetAgent(profile.id)
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
        val bookmarks by app.bookmarks.all.collectAsStateWithLifecycle()
        val developer by app.view.developer.collectAsStateWithLifecycle()

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
                override fun newChat(cwd: String?) {
                    // Images picked for one chat never go to another.
                    attachments.clear()
                    rememberFolder(cwd)
                    controller.newSession(cwd)
                }

                override fun listChats(cwd: String?) = controller.refreshPastSessions(cwd)

                override fun openChat(sessionId: String, cwd: String?) {
                    attachments.clear()
                    rememberFolder(cwd)
                    controller.openPastSession(sessionId, cwd)
                }

                override suspend fun chatExists(sessionId: String, cwd: String) = controller.chatExists(sessionId, cwd)

                override fun toggleBookmark(sessionId: String, cwd: String, title: String?) {
                    val profileId = controller.state.value.profile?.id ?: return
                    app.bookmarks.toggle(profileId, ChatBookmark(sessionId, cwd, title, System.currentTimeMillis()))
                }

                override fun removeBookmark(sessionId: String) {
                    val profileId = controller.state.value.profile?.id ?: return
                    app.bookmarks.remove(profileId, sessionId)
                }

                override fun forgetFolder(path: String) {
                    folderKind(controller.state.value.profile)?.let { app.folders.forget(it, path) }
                }

                private fun rememberFolder(cwd: String?) {
                    val kind = folderKind(controller.state.value.profile) ?: return
                    if (!cwd.isNullOrBlank()) app.folders.remember(kind, cwd)
                }
                override fun authenticate(methodId: String) = controller.authenticate(methodId)
                override fun answerPermission(requestId: Long, optionId: String?) = controller.answerPermission(requestId, optionId)
                override fun answerOpenElsewhere(choice: OpenElsewhereChoice?) {
                    if (choice != null) attachments.clear()
                    controller.answerOpenElsewhere(choice)
                }
                override fun reconnect() = controller.reconnect()
                override fun disconnect() {
                    controller.disconnect()
                    screen = Screen.Profiles
                }

                override fun openLog() {
                    logReturnTo = Screen.Chat
                    screen = Screen.Log
                }

                override fun setDeveloperView(on: Boolean) = app.view.setDeveloper(on)

                override fun draftUsed() {
                    draft.value = null
                }

                override fun stopTablet() = controller.stopTablet()

                override fun holdTablet() = controller.holdTablet()

                override fun releaseTablet() = controller.releaseTablet()

                override fun halt() = controller.halt()

                override fun letTabletGoOn() {
                    controller.askTabletToGoOn()
                }

                override fun openTabDesk() {
                    TabDeskLink.openSettings(this@MainActivity)
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

            Screen.Chat -> {
                val profile = state.profile
                val kind = folderKind(profile)
                ChatScreen(
                    state = state,
                    actions = chatActions,
                    attachments = attachments.map { AttachmentThumb(it.id, it.thumb) },
                    focusRequest = focusRequests.intValue,
                    draft = draft.value,
                    chats = ChatsPanel(
                        bookmarks = profile?.let { bookmarks[it.id] }.orEmpty(),
                        recentFolders = when (kind) {
                            ProfileKind.Bridge -> bridgeFolderHistory
                            ProfileKind.Local -> localFolderHistory
                            else -> emptyList()
                        },
                        showFolders = kind != null,
                        // The bridge and PlasmaLLM both answer the folder picker's questions (PlasmaLLM without a token).
                        browse = profile?.takeIf { it.kind == ProfileKind.Bridge || it.isPlasmaLlm }?.let {
                            FolderBrowse(app.bridgeFolders, it.connectUrl, if (it.isPlasmaLlm) "" else it.token)
                        },
                    ),
                    developer = developer,
                )
            }

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
                // Asked once per visit, not on every change of the state while the form is open.
                tabDeskInstalled = remember(editingId) { TabDeskLink.isInstalled(this@MainActivity) },
                tablet = state.tablet?.takeIf { !copying && editingId != null && state.profile?.id == editingId },
                connectedHere = !copying && editingId != null && state.profile?.id == editingId && state.isConnected,
                laptopAllowsTablet = state.plasmaLlm?.tabletTools?.takeIf { !copying && state.profile?.id == editingId },
                onOpenTabDesk = { TabDeskLink.openSettings(this@MainActivity) },
                onTabletTurnedOn = ::askToStayConnected,
                onSave = { p ->
                    when {
                        p.kind == ProfileKind.Cloud -> {}
                        p.kind == ProfileKind.PlasmaLlm -> if (p.cwd.isNotBlank()) app.folders.remember(ProfileKind.Bridge, p.cwd)
                        else -> app.folders.remember(p.kind, p.cwd)
                    }
                    app.profiles.save(p)
                    // The agent in use takes its tablet and quiet settings at once.
                    controller.profileSaved(p)
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
