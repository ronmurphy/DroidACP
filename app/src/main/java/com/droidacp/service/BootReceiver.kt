package com.droidacp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.droidacp.DroidAcpApp

/**
 * After a reboot, connects the tablet's relay again if it was on (plan D15). Android sends
 * this once the device is unlocked for the first time, since the saved agents are kept in
 * storage that's locked until then.
 *
 * The foreground service is started here, while Android allows it after a boot. Android 15
 * doesn't allow a `dataSync` service from here; this one is `remoteMessaging` from Android 14
 * on, which it allows, and `dataSync` only before Android 14.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (DroidAcpApp.get(context).connectAfterBoot()) AgentService.start(context)
    }
}
