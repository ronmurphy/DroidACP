package com.droidacp.core.tablet

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong

/**
 * The tablet's actions in words, for their cards, the widget and the notification (plan
 * D12): `Tap "Send"` instead of `tap · tablet` and the raw arguments. They're TabDesk's
 * acting tools (tool list 2, TabDesk's docs/agent-control.md), as the agent in PlasmaLLM
 * calls them (`mcp__tablet__tap`).
 *
 * Only the arguments are described: what the agent asked for, not what's on the screen.
 * A text to type is never in the words, only how long it is, since the notification can be
 * on the lock screen. (The card's raw arguments still hold it, shown when the card is opened.)
 */
object TabletActions {
    // mcp__tablet__<tool>, as PlasmaLLM names the tablet's tools.
    private val title = Regex("mcp__tablet__(\\w+)", RegexOption.IGNORE_CASE)

    /** The longest quote of a target, so a card stays one line. */
    const val QUOTE_CHARS = 40

    /** [toolTitle]'s action in words, from [rawInput] (the call's arguments as JSON); null if it isn't one of the tablet's actions. */
    fun describe(toolTitle: String, rawInput: String?): String? {
        val tool = title.matchEntire(toolTitle.trim())?.groupValues?.get(1)?.lowercase() ?: return null
        val input = parse(rawInput) ?: return null
        return when (tool) {
            "tap" -> tap(input)
            "type" -> type(input)
            "swipe" -> swipe(input)
            "scroll" -> scroll(input)
            "key" -> key(input)
            "wait_for" -> waitFor(input)
            else -> null
        }
    }

    private fun tap(input: JsonObject): String {
        val verb = if (input.bool("long")) "Long-press" else "Tap"
        val where = target(input.obj("target")) ?: point(input)?.let { "at $it" }
        return listOfNotNull(verb, where).joinToString(" ")
    }

    private fun type(input: JsonObject): String {
        val text = input.string("text").orEmpty()
        val n = text.codePointCount(0, text.length)
        val characters = if (n == 1) "1 character" else "$n characters"
        val field = target(input.obj("target"))
        val action = if (input.bool("replace")) {
            "Replace the text in ${field ?: "the focused field"} with $characters"
        } else {
            "Type $characters into ${field ?: "the focused field"}"
        }
        return if (input.bool("submit")) "$action, then press Enter" else action
    }

    private fun swipe(input: JsonObject): String {
        val from = target(input.obj("from")) ?: point(input)
        return listOfNotNull("Swipe", direction(input), from?.let { "from $it" }).joinToString(" ")
    }

    private fun scroll(input: JsonObject): String {
        val inside = target(input.obj("target"))?.let { "in $it" }
        val half = if (input.string("amount") == "half") "by half a page" else null
        return listOfNotNull("Scroll", direction(input), half, inside).joinToString(" ")
    }

    private fun key(input: JsonObject): String = when (input.string("key")) {
        "back" -> "Press Back"
        "home" -> "Press Home"
        "enter" -> "Press Enter"
        else -> "Press a key"
    }

    private fun waitFor(input: JsonObject): String {
        val what = target(input.obj("target")) ?: "an item"
        return if (input.bool("gone")) "Wait until $what is gone" else "Wait for $what"
    }

    /** A locator as words: `"Send" (button)`, or `an item (0.3.1)` for an id from the screen. */
    private fun target(o: JsonObject?): String? {
        if (o == null) return null
        val role = o.string("role")?.trim()?.takeIf { it.isNotEmpty() }?.let { " (${clip(it)})" }.orEmpty()
        o.string("content_contains")?.takeIf { it.isNotBlank() }?.let { return "“${clip(it)}”$role" }
        o.string("id")?.takeIf { it.isNotBlank() }?.let { return "an item (${clip(it)})" }
        return null
    }

    // TabDesk takes x and y as numbers; a decimal one is rounded, as a tap lands on a pixel.
    private fun point(input: JsonObject): String? {
        val x = (input["x"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: return null
        val y = (input["y"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: return null
        return "${x.roundToLong()}, ${y.roundToLong()}"
    }

    private fun direction(input: JsonObject): String? =
        input.string("direction")?.takeIf { it in setOf("up", "down", "left", "right") }

    // Counted in characters (code points), so an emoji is never cut in half.
    private fun clip(s: String): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        if (one.codePointCount(0, one.length) <= QUOTE_CHARS) return one
        return one.substring(0, one.offsetByCodePoints(0, QUOTE_CHARS - 1)).trimEnd() + "…"
    }

    private fun parse(rawInput: String?): JsonObject? {
        if (rawInput.isNullOrBlank()) return null
        return try {
            Json.parseToJsonElement(rawInput) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
}
