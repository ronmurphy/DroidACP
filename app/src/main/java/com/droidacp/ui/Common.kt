package com.droidacp.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

// Shared bits for the screens in this package. Everything in `ui/` is plain
// Compose (no android.* imports) except Theme.kt and Platform.kt, which keeps the
// screens easy to read and to preview.

/** Turns base64 image data from the agent into something Compose can draw. Provided by the Android side. */
fun interface ImageDecoder {
    /**
     * [base64] as a bitmap at most [maxEdge] pixels on its long side, shrunk by halves as it's
     * read (a 50 MP photo at full size would be 200 MB), or null if it can't be read.
     */
    fun decode(base64: String, maxEdge: Int): ImageBitmap?

    companion object {
        /** In the chat, at most 320 dp high: enough for a tablet's column. */
        const val IN_CHAT = 1536

        /** Full screen, to zoom into. */
        const val FULL_SCREEN = 4096
    }
}

val LocalImageDecoder = compositionLocalOf { ImageDecoder { _, _ -> null } }

/** An image the user picked to send with the next message. */
data class AttachmentThumb(val id: Long, val image: ImageBitmap?)

object UiColors {
    val diffAddedBg = Color(0x3329B34A)
    val diffRemovedBg = Color(0x33E5484D)
    val diffAddedText = Color(0xFF2E9E4F)
    val diffRemovedText = Color(0xFFD9363E)
    val success = Color(0xFF2E9E4F)
}

fun kindIcon(kind: String): String = when (kind) {
    "read" -> "📖"
    "edit" -> "✏️"
    "delete" -> "🗑️"
    "move" -> "📦"
    "search" -> "🔍"
    "execute" -> "▶️"
    "think" -> "💭"
    "fetch" -> "🌐"
    "switch_mode" -> "🔀"
    else -> "🔧"
}

fun kindVerb(kind: String): String = when (kind) {
    "read" -> "read a file"
    "edit" -> "edit a file"
    "delete" -> "delete a file"
    "move" -> "move a file"
    "search" -> "search"
    "execute" -> "run a command"
    "fetch" -> "fetch from the web"
    "switch_mode" -> "switch mode"
    else -> "use a tool"
}

/** "12.3k" style token counts. */
fun compactNumber(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> String.format("%.1fk", n / 1000.0)
    else -> n.toString()
}

/** Show only the file name of a path, e.g. `/home/me/app/src/Main.kt` → `Main.kt`. */
fun fileName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
