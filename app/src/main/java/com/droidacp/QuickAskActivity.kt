package com.droidacp

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidacp.core.chat.ChatState
import com.droidacp.core.chat.ConnectionStatus
import com.droidacp.core.chat.ProfileKind
import com.droidacp.service.Notifications
import com.droidacp.ui.DroidAcpTheme

/**
 * The widget's Ask: a small box over the home screen for one message, typed or spoken,
 * without opening the whole app. It goes to the chat open now; the reply shows in the widget.
 */
class QuickAskActivity : ComponentActivity() {
    private val app: DroidAcpApp get() = DroidAcpApp.get(this)

    // Bumped to put the cursor in the text box (and bring the keyboard up).
    private val focusRequests = mutableIntStateOf(0)

    // What the speech recognizer heard, for the text box to pick up.
    private val heard = mutableStateOf<String?>(null)
    private var listening = false
    private val listen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        listening = false
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !text.isNullOrBlank()) heard.value = text
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && app.isChatOnScreen) {
            useTheApp(intent)
            return
        }
        if (savedInstanceState == null) start(intent)
        setContent { DroidAcpTheme { QuickAskBox() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (app.isChatOnScreen) useTheApp(intent) else start(intent)
    }

    /**
     * The chat is already on screen (a floating window, or the other half of split screen),
     * and could cover this box: some tablets keep floating windows above full-screen ones.
     * Bring that window to the front with its message box ready instead.
     */
    private fun useTheApp(intent: Intent) {
        val voice = intent.getBooleanExtra(EXTRA_VOICE, false)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                .putExtra(MainActivity.EXTRA_FOCUS_INPUT, true)
                .putExtra(EXTRA_VOICE, voice),
        )
        finish()
    }

    // A box like this goes away when you leave it, like a dialog (but not while the recognizer listens).
    override fun onStop() {
        super.onStop()
        if (!listening && !isChangingConfigurations) finish()
    }

    private fun start(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_VOICE, false) && canListen(this)) {
            listen()
        } else {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            focusRequests.intValue++
        }
    }

    private fun listen() {
        try {
            listening = true
            listen.launch(speechIntent("Ask ${app.controller.state.value.agentName}"))
        } catch (e: ActivityNotFoundException) {
            listening = false
            Toast.makeText(this, "No voice input on this device", Toast.LENGTH_SHORT).show()
        }
    }

    /** Send to the chat open now. Null when it went, otherwise why not (the box stays open with the text). */
    private fun send(state: ChatState, text: String): String? {
        val sessionId = state.sessionId ?: return "${state.agentName} isn't ready for a message yet."
        val refused = app.controller.sendReply(sessionId, text)
        if (refused == null) finish()
        return refused
    }

    /** Carry on in the app, with what you typed in its message box. */
    private fun openApp(text: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                .putExtra(MainActivity.EXTRA_FOCUS_INPUT, true)
                .apply { if (text.isNotBlank()) putExtra(MainActivity.EXTRA_DRAFT, text) },
        )
        finish()
    }

    @Composable
    private fun QuickAskBox() {
        val state by app.controller.state.collectAsStateWithLifecycle()
        var text by rememberSaveable { mutableStateOf("") }
        var problem by remember { mutableStateOf<String?>(null) }
        val name = state.agentName
        val who = if (state.profile?.kind == ProfileKind.PlasmaLlm) "PlasmaLLM" else name
        val connecting = state.status == ConnectionStatus.Connecting
        val showField = connecting || state.isConnected
        val wait = when {
            !showField -> null
            connecting -> "Connecting…"
            state.authRequired -> "$name needs you to sign in. Open DroidACP to do that."
            !state.canChat -> "Opening the chat…"
            state.busy -> "$who is working on a message. You can send yours when it's done."
            else -> null
        }

        val focus = remember { FocusRequester() }
        val focusRequest = focusRequests.intValue
        LaunchedEffect(focusRequest, showField) {
            if (focusRequest > 0 && showField) runCatching { focus.requestFocus() }
        }
        val heardText = heard.value
        LaunchedEffect(heardText) {
            if (heardText != null) {
                text = if (text.isBlank()) heardText else "${text.trimEnd()} $heardText"
                problem = null
                heard.value = null
            }
        }

        // Tapping outside the box closes it.
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f))
                .pointerInput(Unit) { detectTapGestures { finish() } }
                .systemBarsPadding()
                .imePadding()
                .padding(16.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val where = state.sessionTitle ?: state.folder
                            if (where != null && state.profile != null) {
                                Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.padding(4.dp))
                        Text(Notifications.statusText(state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }

                    if (showField) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                problem = null
                            },
                            placeholder = { Text("Message $name") },
                            minLines = 2,
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            trailingIcon = if (canListen(this@QuickAskActivity)) {
                                {
                                    IconButton(onClick = ::listen) {
                                        Icon(painterResource(R.drawable.ic_mic), contentDescription = "Speak your message")
                                    }
                                }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus),
                        )
                        val note = problem ?: wait
                        if (note != null) {
                            Text(
                                note,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { openApp(text) }) { Text("Open app") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = ::finish) { Text("Cancel") }
                            Button(
                                onClick = { problem = send(state, text) },
                                enabled = state.canChat && !state.busy && text.isNotBlank(),
                            ) { Text("Send") }
                        }
                    } else {
                        val failed = state.status as? ConnectionStatus.Failed
                        val last = app.profiles.get(app.profiles.lastProfileId)
                        Text(
                            when {
                                failed != null -> "Couldn't connect: ${failed.reason}"
                                last != null -> "DroidACP isn't connected to ${last.name}."
                                else -> "DroidACP isn't connected to an agent yet."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { openApp(text) }) { Text("Open app") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = ::finish) { Text("Cancel") }
                            if (last != null) {
                                Button(onClick = {
                                    app.reconnectLast()
                                    focusRequests.intValue++
                                }) { Text("Connect") }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_VOICE = "com.droidacp.extra.VOICE"

        /** Opens the box from the widget; with [voice] it starts listening straight away. */
        fun open(context: Context, voice: Boolean): PendingIntent {
            val intent = Intent(context, QuickAskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_VOICE, voice)
            return PendingIntent.getActivity(context, if (voice) 27 else 26, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Asks Android's speech recognizer for one message; the text comes back in [RecognizerIntent.EXTRA_RESULTS]. */
        fun speechIntent(prompt: String): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

        @Volatile
        private var listenable: Boolean? = null

        /** Whether this device has speech input (cheap tablets often don't). */
        fun canListen(context: Context): Boolean =
            listenable ?: context.packageManager
                .queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
                .isNotEmpty()
                .also { listenable = it }
    }
}
