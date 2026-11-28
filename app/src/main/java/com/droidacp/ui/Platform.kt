package com.droidacp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.droidacp.core.chat.PictureFile
import java.io.ByteArrayOutputStream

// Android-only image helpers.

/** Decodes base64 images sent by the agent. */
object AndroidImageDecoder : ImageDecoder {
    override fun decode(base64: String, maxEdge: Int): ImageBitmap? = try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        // Its size first, then read at the power-of-two shrink that fits.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = PictureFile.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

/** A picked photo/screenshot, shrunk and encoded, ready to attach to a prompt. */
data class PreparedImage(val id: Long, val mimeType: String, val base64: String, val thumb: ImageBitmap)

object ImagePreparer {
    // Models don't benefit from huge images; this keeps uploads small and fast.
    private const val MAX_EDGE = 1568

    fun prepare(context: Context, uri: Uri, id: Long): PreparedImage? = try {
        val resolver = context.contentResolver

        // 1. Read just the size, to pick a cheap power-of-two downsample.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2

            // 2. Decode, then scale to the exact maximum size.
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (decoded == null) {
                null
            } else {
                val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
                val matrix = Matrix()
                if (scale < 1f) matrix.postScale(scale, scale)

                // 3. Camera photos are often stored sideways with an EXIF hint; apply it.
                val orientation = resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                }
                val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                PreparedImage(
                    id = id,
                    mimeType = "image/jpeg",
                    base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
                    thumb = bitmap.asImageBitmap(),
                )
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** Another app the bridge setup may need, and where to get it if it isn't installed. */
enum class HelperApp(val packageName: String, val label: String, val getUrl: String) {
    // Termux on Google Play is years out of date; F-Droid has the maintained build.
    Termux("com.termux", "Termux", "https://f-droid.org/packages/com.termux/"),
    Tailscale("com.tailscale.ipn", "Tailscale", "https://play.google.com/store/apps/details?id=com.tailscale.ipn"),
    ;

    fun isInstalled(context: Context): Boolean = context.packageManager.getLaunchIntentForPackage(packageName) != null

    /** Open the app, or its download page when it isn't installed. */
    fun open(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(getUrl))
        try {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "Couldn't open $label", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
