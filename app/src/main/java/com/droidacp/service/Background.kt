package com.droidacp.service

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Android's battery optimisation, which can stop DroidACP's connection while the screen is
 * off (plan D14): the tablet's relay needs it off, and so does a phone that should keep
 * getting the agent's notifications.
 */
object Background {
    /** DroidACP is left out of battery optimisation (true where Android can't say). */
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /**
     * Asks, with Android's own dialog, to leave DroidACP out of battery optimisation; where a
     * maker's Android has no such dialog, opens its list of apps instead. Nothing if it's
     * already out. TabDesk's setup explains a maker's own extra steps.
     */
    // DroidACP keeps a connection open for the user's agent (and the tablet's relay), which is
    // what the permission is for; it isn't published on Google Play.
    @SuppressLint("BatteryLife")
    fun askToBeExempt(context: Context) {
        if (isExempt(context)) return
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        for (intent in listOf(ask, list)) {
            try {
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next one.
            }
        }
    }
}
