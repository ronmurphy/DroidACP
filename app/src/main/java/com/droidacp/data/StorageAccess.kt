package com.droidacp.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Whether the built-in agent may work in the device's shared storage (Documents,
 * Download, ...). The app's own private folders never need permission.
 *
 * A coding agent needs real file paths and a shell that can see them, so the usual
 * Android file picker isn't enough. On Android 11+ that means the "All files access"
 * setting, which the user switches on in system Settings.
 */
object StorageAccess {
    /** Where a new on-device agent works unless the user picks another folder. */
    fun defaultFolder(): String =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DroidACP").path

    fun isGranted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** True if [path] is in shared storage, outside the folders Android gives this app for free. */
    fun isNeededFor(context: Context, path: String): Boolean {
        val shared = Environment.getExternalStorageDirectory().path
        if (path != shared && !path.startsWith("$shared/")) return false
        val own = listOfNotNull(context.getExternalFilesDir(null)?.parentFile?.path)
        return own.none { path == it || path.startsWith("$it/") }
    }

    /** Android 11+: opens the "All files access" switch for this app. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
}
