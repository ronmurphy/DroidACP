package com.droidacp.core

import com.droidacp.core.chat.Profile
import com.droidacp.core.chat.ProfileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProfileTest {
    @Test
    fun copiesGetANameNoOtherAgentHas() {
        assertEquals("Laptop (copy)", Profile.copyName("Laptop", listOf("Laptop")))
        assertEquals("Laptop (copy 2)", Profile.copyName("Laptop", listOf("Laptop", "Laptop (copy)")))
        assertEquals("Laptop (copy 3)", Profile.copyName("Laptop", listOf("laptop", "LAPTOP (COPY)", "Laptop (copy 2)")))
        // Copying a copy makes the next copy of the original, not "Laptop (copy) (copy)".
        assertEquals("Laptop (copy 2)", Profile.copyName("Laptop (copy)", listOf("Laptop", "Laptop (copy)")))
        assertEquals("Agent (copy)", Profile.copyName("  ", emptyList()))
    }

    @Test
    fun aCopyKeepsTheSettingsUnderANewId() {
        val original = Profile(id = "a", name = "Bridge", kind = ProfileKind.Bridge, url = "ws://x:8765", token = "t", cwd = "/home/me/p")
        val copy = original.copyAsNew("b", listOf("Bridge"))
        assertEquals("b", copy.id)
        assertNotEquals(original.id, copy.id)
        assertEquals("Bridge (copy)", copy.name)
        assertEquals(original.copy(id = "b", name = "Bridge (copy)"), copy)
    }
}
