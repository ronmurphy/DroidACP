package com.droidacp.ui

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.droidacp.core.chat.PictureFile
import java.io.File
import java.time.LocalDateTime

/**
 * Saving a chat's picture to the device's Pictures, or sharing it with another app. The
 * picture's own bytes go, as the agent sent them, not the shrunk copy the chat shows.
 */
object PictureActions {
    /** Where saved pictures go, in Pictures. */
    const val FOLDER = "DroidACP"

    /** Android 10 and later save to Pictures with no permission; older ones can only share. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val canSave: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Saves [picture] to Pictures/DroidACP and says where. Blocking: call it off the main thread. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun save(context: Context, picture: PictureFile): String {
        val name = picture.name(LocalDateTime.now())
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, picture.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Android didn't make the file")
        try {
            resolver.openOutputStream(uri)?.use { it.write(picture.bytes) } ?: error("Android didn't open the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "Pictures/$FOLDER/$name"
    }

    /**
     * Android's share sheet for [picture] (Photos, Files, a message…), to start on the main
     * thread. The file waits in the app's cache, where ones shared over an hour ago are
     * cleared. Blocking: it writes the file.
     */
    fun shareIntent(context: Context, picture: PictureFile): Intent {
        val dir = File(context.cacheDir, "shared-pictures").apply { mkdirs() }
        val hourAgo = System.currentTimeMillis() - 60 * 60 * 1000
        dir.listFiles()?.filter { it.lastModified() < hourAgo }?.forEach { it.delete() }
        val file = File(dir, picture.name(LocalDateTime.now())).apply { writeBytes(picture.bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.pictures", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(picture.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, "Share the picture")
    }
}
