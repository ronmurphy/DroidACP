package com.droidacp.core

import com.droidacp.core.chat.PictureFile
import java.time.LocalDateTime
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A chat's picture as a file to save or share: its type, from the bytes first, and its name. */
class PictureFileTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 9)
    private val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun theTypeComesFromTheBytesThenTheMimeType() {
        val p = assertNotNull(PictureFile.of(b64(png), "image/png"))
        assertEquals("image/png", p.mimeType)
        assertEquals("png", p.extension)
        assertEquals(png.toList(), p.bytes.toList())

        // An agent calling a JPEG a PNG: the bytes win.
        assertEquals("jpg", PictureFile.of(b64(jpeg), "image/png")!!.extension)
        assertEquals("image/webp", PictureFile.of(b64(webp), "application/octet-stream")!!.mimeType)

        // Bytes we don't know: the mime type, "image/jpg" read as JPEG, parameters ignored...
        val unknown = byteArrayOf(1, 2, 3, 4)
        assertEquals("image/jpeg", PictureFile.of(b64(unknown), "image/jpg")!!.mimeType)
        assertEquals("heic", PictureFile.of(b64(unknown), "Image/HEIC; q=1")!!.extension)
        // ...and PNG when that's no help either.
        assertEquals("png", PictureFile.of(b64(unknown), "")!!.extension)
    }

    @Test
    fun base64WithLineBreaksOrAsADataUrlIsRead() {
        val wrapped = b64(png).chunked(4).joinToString("\n")
        assertEquals(png.toList(), PictureFile.of(wrapped, "image/png")!!.bytes.toList())
        assertEquals(png.toList(), PictureFile.of("data:image/png;base64," + b64(png), "image/png")!!.bytes.toList())
        assertNull(PictureFile.of("not base64!", "image/png"))
        assertNull(PictureFile.of("", "image/png"))
    }

    @Test
    fun itsNameSaysWhenItWasSaved() {
        val p = PictureFile.of(b64(jpeg), "image/jpeg")!!
        assertEquals("DroidACP-20261127-221530.jpg", p.name(LocalDateTime.of(2026, 11, 27, 22, 15, 30)))
    }
}
