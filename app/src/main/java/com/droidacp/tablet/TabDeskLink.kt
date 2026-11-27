package com.droidacp.tablet

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import com.droidacp.core.acp.PlasmaLlm
import com.droidacp.core.rpc.RpcErrorCodes
import com.droidacp.core.rpc.RpcException
import com.droidacp.core.tablet.PausedOnTablet
import com.droidacp.core.tablet.TabletLink
import com.droidacp.core.tablet.TabletRelay
import com.droidacp.core.tablet.TabletStatus
import dev.tabdesk.agent.IAgentCallback
import dev.tabdesk.agent.IAgentService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * DroidACP's connection to TabDesk's agent service, as TabDesk's `docs/agent-control.md`
 * (Part 1) describes it: bind `dev.tabdesk.action.AGENT`, check `version()` is 1,
 * `connect()` the callback, then `send` MCP messages and take their answers as `reply` (up
 * to 32 Ki characters) or `replyStream` (a pipe, read to its end).
 *
 * The two-way calls (`version`, `status`, `connect`) and the binding run on one worker
 * thread, never the main thread; `send`, `cancel`, `stop` and `resume` are one-way.
 */
class TabDeskLink(context: Context) : TabletLink {
    private val context = context.applicationContext
    private val _status = MutableStateFlow(TabletStatus())
    override val status: StateFlow<TabletStatus> = _status.asStateFlow()

    /** Binding, the two-way calls, and the state they change. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "TabDesk") }

    /** Reading answers that come as a pipe, which can take a moment. */
    private val readers = Executors.newCachedThreadPool { Thread(it, "TabDesk answer") }

    private var wanted = false // worker thread only
    private var connection: ServiceConnection? = null // worker thread only

    @Volatile
    private var service: IAgentService? = null

    /** Calls sent and not yet answered, by Binder id. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    // AIDL's strings may be null: the parameters say so, or Kotlin's own null check would throw
    // before the body runs, and a one-way call would lose the update without a trace.
    private val callback = object : IAgentCallback.Stub() {
        override fun reply(id: String?, json: String?) {
            if (id == null) return
            val waiter = pending.remove(id) ?: return
            if (json == null) waiter.completeExceptionally(RpcException(PlasmaLlm.TABLET_FAILED, "TabDesk's answer was empty")) else waiter.complete(json)
        }

        override fun onControlState(state: String?, reason: String?) {
            if (state == null) return
            _status.update { it.copy(control = state, reason = reason.orEmpty()) }
            // A Stop (here or on TabDesk) or a pause (the user touched the screen) drops the
            // running calls, and TabDesk won't answer them.
            when (state) {
                TabletStatus.STOPPED -> failAll(RpcErrorCodes.REQUEST_CANCELLED, "Stopped on the tablet")
                TabletStatus.PAUSED -> failAll(PausedOnTablet(reason.orEmpty()))
            }
            // The approval, the switch or the name may have changed with it.
            worker.execute { readStatus() }
        }

        override fun replyStream(id: String?, pipe: ParcelFileDescriptor?) {
            if (pipe == null) {
                if (id != null) pending.remove(id)?.completeExceptionally(RpcException(PlasmaLlm.TABLET_FAILED, "TabDesk's answer was empty"))
                return
            }
            val waiter = id?.let { pending[it] }
            if (waiter == null) {
                closeQuietly(pipe)
                return
            }
            readers.execute {
                val text = try {
                    ParcelFileDescriptor.AutoCloseInputStream(pipe).use { readAll(it) }
                } catch (e: IOException) {
                    // A pipe that broke. An answer cut short is caught later: its JSON won't parse.
                    RpcException(PlasmaLlm.TABLET_FAILED, "TabDesk's answer stopped early")
                } catch (e: RpcException) {
                    e
                }
                pending.remove(id, waiter)
                if (text is RpcException) waiter.completeExceptionally(text) else waiter.complete(text as String)
            }
        }
    }

    override fun setWanted(wanted: Boolean) {
        worker.execute {
            // Wanted already, but the last try found no TabDesk, or one without the agent service
            // or of another version: try again (each connection to PlasmaLLM asks).
            if (this.wanted == wanted && !(wanted && couldNotBind())) return@execute
            this.wanted = wanted
            if (wanted) rebind() else unbind()
        }
    }

    override suspend fun send(id: String, message: String): String {
        val s = service
        val now = _status.value
        if (s == null || !now.connected) throw RpcException(PlasmaLlm.TABLET_UNAVAILABLE, now.unavailable ?: "DroidACP isn't connected to TabDesk")
        val answer = CompletableDeferred<String>()
        if (pending.putIfAbsent(id, answer) != null) throw RpcException(RpcErrorCodes.INVALID_PARAMS, "A call with that id is already running")
        try {
            try {
                s.send(id, message)
            } catch (e: RemoteException) {
                throw RpcException(PlasmaLlm.TABLET_UNAVAILABLE, "TabDesk can't be reached")
            }
            return answer.await()
        } catch (e: CancellationException) {
            // Still running at TabDesk: drop it there. No answer comes for it afterwards.
            if (pending.remove(id, answer)) runCatching { s.cancel(id) }
            throw e
        } finally {
            pending.remove(id, answer)
        }
    }

    override fun stop() {
        val s = service ?: return
        try {
            s.stop()
        } catch (e: RemoteException) {
            Log.w(TAG, "stop() didn't reach TabDesk", e)
        }
        failAll(RpcErrorCodes.REQUEST_CANCELLED, "Stopped by the user")
    }

    override fun resume() {
        try {
            service?.resume()
        } catch (e: RemoteException) {
            Log.w(TAG, "resume() didn't reach TabDesk", e)
        }
    }

    // --- worker thread ---

    private fun couldNotBind() = _status.value.reach in CAN_BIND_AGAIN

    /** Drop a binding that didn't work, if any, and bind. */
    private fun rebind() {
        connection?.let { unbindQuietly(it) }
        connection = null
        service = null
        bind()
    }

    /**
     * TabDesk installed or updated while wanted, after a bind that failed: bind again. (A bound
     * service whose process restarts for an update comes back by itself.)
     */
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart != PACKAGE) return
            worker.execute { if (wanted && couldNotBind()) rebind() }
        }
    }
    private var watchingPackages = false

    private fun watchPackages(on: Boolean) {
        if (on == watchingPackages) return
        watchingPackages = on
        if (on) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(context, packageChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            runCatching { context.unregisterReceiver(packageChanges) }
        }
    }

    private fun bind() {
        watchPackages(true)
        if (!isInstalled(context)) {
            _status.value = TabletStatus(TabletStatus.Reach.NotInstalled)
            return
        }
        _status.value = TabletStatus(TabletStatus.Reach.Connecting)
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                worker.execute { if (connection === this) attach(IAgentService.Stub.asInterface(binder)) }
            }

            // TabDesk's process went away; Android binds again by itself once it's back.
            override fun onServiceDisconnected(name: ComponentName) {
                worker.execute { if (connection === this) lost() }
            }

            // This binding can't come back: make a new one.
            override fun onBindingDied(name: ComponentName) {
                worker.execute {
                    if (connection !== this) return@execute
                    lost()
                    unbindQuietly(this)
                    connection = null
                    if (wanted) bind()
                }
            }

            override fun onNullBinding(name: ComponentName) {
                worker.execute { if (connection === this) _status.value = TabletStatus(TabletStatus.Reach.NoService) }
            }
        }
        connection = c
        val bound = try {
            context.bindService(Intent(ACTION_AGENT).setPackage(PACKAGE), c, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        if (!bound) {
            unbindQuietly(c)
            connection = null
            // Installed, but no agent service to bind: a TabDesk from before it.
            _status.value = TabletStatus(TabletStatus.Reach.NoService)
        }
    }

    private fun attach(s: IAgentService) {
        try {
            val version = s.version()
            if (version != INTERFACE_VERSION) {
                Log.w(TAG, "TabDesk's agent interface is version $version; DroidACP knows $INTERFACE_VERSION")
                service = null
                _status.value = TabletStatus(TabletStatus.Reach.Incompatible)
                return
            }
            // Its first onControlState comes straight after.
            s.connect(callback)
            service = s
            _status.update { it.copy(reach = TabletStatus.Reach.Connected) }
            readStatus()
        } catch (e: RemoteException) {
            lost()
        }
    }

    /** TabDesk's state for DroidACP. The control state comes from the callbacks once they've said it. */
    private fun readStatus() {
        val s = service ?: return
        val json = try {
            JSONObject(s.status())
        } catch (e: RemoteException) {
            return
        } catch (e: JSONException) {
            Log.w(TAG, "TabDesk's status() wasn't JSON", e)
            return
        }
        _status.update {
            if (!it.connected) {
                it
            } else {
                it.copy(
                    enabled = json.optBoolean("enabled", false),
                    approval = json.optString("approval").ifEmpty { null },
                    helper = json.optString("helper").ifEmpty { null },
                    name = json.optString("tablet").ifBlank { null },
                    control = it.control ?: json.optString("control").ifEmpty { null },
                )
            }
        }
    }

    private fun lost() {
        service = null
        _status.value = TabletStatus(TabletStatus.Reach.Lost)
        failAll(PlasmaLlm.TABLET_FAILED, "TabDesk stopped during the call")
    }

    private fun unbind() {
        watchPackages(false)
        connection?.let { unbindQuietly(it) }
        connection = null
        service = null
        _status.value = TabletStatus()
        failAll(PlasmaLlm.TABLET_UNAVAILABLE, "DroidACP let go of TabDesk")
    }

    private fun unbindQuietly(c: ServiceConnection) {
        try {
            context.unbindService(c)
        } catch (e: IllegalArgumentException) {
            // Never bound.
        }
    }

    /** Every call still waiting fails with [e] (TabDesk dropped them). */
    private fun failAll(e: Exception) {
        for (id in pending.keys.toList()) pending.remove(id)?.completeExceptionally(e)
    }

    private fun failAll(code: Int, message: String) {
        // One by one: a call sent meanwhile is either failed here or left to run, never dropped.
        for (id in pending.keys.toList()) pending.remove(id)?.completeExceptionally(RpcException(code, message))
    }

    /** The whole pipe as UTF-8. Past [MAX_ANSWER_BYTES] it stops and throws: the answer can't be passed on. */
    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_ANSWER_BYTES) {
                throw RpcException(PlasmaLlm.TABLET_FAILED, "TabDesk's answer is too big to pass on (over ${MAX_ANSWER_BYTES / (1024 * 1024)} MiB)")
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun closeQuietly(pipe: ParcelFileDescriptor) {
        try {
            pipe.close()
        } catch (e: IOException) {
            // Nothing to do.
        }
    }

    companion object {
        private const val TAG = "TabDeskLink"
        const val PACKAGE = "dev.tabdesk"
        private const val ACTION_AGENT = "dev.tabdesk.action.AGENT"
        private const val ACTION_SETTINGS = "dev.tabdesk.action.AGENT_SETTINGS"
        private const val INTERFACE_VERSION = 1

        /** Reading stops past the relay's own limit, which keeps answers under the WebSocket's. */
        private const val MAX_ANSWER_BYTES = TabletRelay.MAX_ANSWER_CHARS

        /** Binding again can help after these: TabDesk installed or updated since. */
        private val CAN_BIND_AGAIN = setOf(TabletStatus.Reach.NotInstalled, TabletStatus.Reach.NoService, TabletStatus.Reach.Incompatible)

        fun isInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

        /** TabDesk → Customize → Agent, where DroidACP is approved. Opening it approves nothing. */
        fun openSettings(context: Context): Boolean = try {
            context.startActivity(Intent(ACTION_SETTINGS).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        }

        /**
         * This install's id for its tablet, `droidacp:<uuid>`: the same for as long as DroidACP
         * is installed, so declaring again keeps PlasmaLLM's registration.
         */
        fun serverId(context: Context): String {
            val prefs = context.getSharedPreferences("tablet", Context.MODE_PRIVATE)
            prefs.getString("serverId", null)?.let { return it }
            val id = "droidacp:" + UUID.randomUUID()
            prefs.edit().putString("serverId", id).apply()
            return id
        }

        /** 🛑 The tablet is on hold (plan D20), as last saved. */
        fun isHeld(context: Context): Boolean =
            context.getSharedPreferences("tablet", Context.MODE_PRIVATE).getBoolean("held", false)

        /** Saves the hold at once (not later, as apply() would), so it outlasts the app being killed. */
        fun saveHeld(context: Context, held: Boolean) {
            // A tiny file, on purpose on the tap's own thread: the hold is on disk before anything else runs.
            val saved = context.getSharedPreferences("tablet", Context.MODE_PRIVATE).edit().putBoolean("held", held).commit()
            if (!saved) Log.w(TAG, "The hold ($held) couldn't be saved; it lasts only until DroidACP restarts")
        }
    }
}
