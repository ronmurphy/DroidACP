package com.droidacp.core.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Small, forgiving accessors for reading JSON-RPC payloads.
//
// ACP messages come from many different agents, so instead of strict data classes
// we read fields one by one and fall back to null when something is missing or has
// an unexpected type. That keeps the app working when an agent adds new fields or
// leaves optional ones out.

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Like [str] but also accepts numbers/booleans and renders them as text. */
fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** The objects inside an array field, skipping anything that isn't an object. */
fun JsonObject.objects(key: String): List<JsonObject> = arr(key)?.mapNotNull { it as? JsonObject } ?: emptyList()

/** True when the key is present with a non-null value (used for `{}` capability flags). */
fun JsonObject.has(key: String): Boolean = this[key] != null && this[key] !is JsonNull

private val prettyJson = Json { prettyPrint = true }

/** Human readable JSON for showing raw tool input/output, or null for null/absent values. */
fun JsonElement?.pretty(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content
    else -> prettyJson.encodeToString(JsonElement.serializer(), this)
}
