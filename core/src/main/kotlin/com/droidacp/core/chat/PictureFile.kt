package com.droidacp.core.chat

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * A picture from the chat as a file, to save or share: its bytes, its type, and a name.
 * The type comes from the bytes themselves when they're a kind we know, since an agent's
 * `mimeType` is sometimes wrong; otherwise from the `mimeType`; otherwise PNG.
 */
data class PictureFile(val bytes: ByteArray, val mimeType: String, val extension: String) {
    /** "DroidACP-20261127-221530.png", for a picture saved at [time]. */
    fun name(time: LocalDateTime): String = "DroidACP-${time.format(STAMP)}.$extension"

    override fun equals(other: Any?) =
        other is PictureFile && other.mimeType == mimeType && other.extension == extension && other.bytes.contentEquals(bytes)

    override fun hashCode() = 31 * mimeType.hashCode() + bytes.contentHashCode()

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        private val KNOWN = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/webp" to "webp",
            "image/gif" to "gif",
            "image/bmp" to "bmp",
            "image/heic" to "heic",
            "image/heif" to "heif",
            "image/avif" to "avif",
        )

        /**
         * The power of two to shrink a [width]×[height] picture by as it's read (Android's
         * `inSampleSize`), so its long side is at most [maxEdge]: 1 when it already fits.
         */
        fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
            val edge = maxOf(width, height)
            val most = maxEdge.coerceAtLeast(1)
            var sample = 1
            while (edge / sample > most) sample *= 2
            return sample
        }

        /** An ACP image block's [base64] data (a `data:` URL's too) and [mimeType]; null if it isn't base64. */
        fun of(base64: String, mimeType: String): PictureFile? {
            val data = if (base64.startsWith("data:")) base64.substringAfter(',', "") else base64
            val bytes = try {
                Base64.getDecoder().decode(data.filterNot { it.isWhitespace() })
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (bytes.isEmpty()) return null
            val declared = mimeType.substringBefore(';').trim().lowercase().let { if (it == "image/jpg") "image/jpeg" else it }
            val type = sniff(bytes) ?: declared.takeIf { it in KNOWN } ?: "image/png"
            return PictureFile(bytes, type, KNOWN.getValue(type))
        }

        /** The type the bytes start with, for the common kinds. */
        private fun sniff(b: ByteArray): String? {
            fun at(i: Int, vararg v: Int) = b.size >= i + v.size && v.indices.all { b[i + it].toInt() and 0xFF == v[it] }
            return when {
                at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
                at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
                at(0, 0x47, 0x49, 0x46, 0x38) -> "image/gif"
                at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"
                at(0, 0x42, 0x4D) -> "image/bmp"
                else -> null
            }
        }
    }
}
