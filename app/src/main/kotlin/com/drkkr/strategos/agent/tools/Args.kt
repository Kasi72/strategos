package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.InvalidArguments
import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Argument helpers shared by the tool handlers. They throw [InvalidArguments] on contract violations. */
internal object Args {
    fun string(args: JsonObject, key: String): String? = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun requireString(args: JsonObject, key: String): String =
        string(args, key)?.takeIf { it.isNotBlank() } ?: throw InvalidArguments("$key is required")

    fun array(args: JsonObject, key: String): JsonArray? = when (val v = args[key]) {
        null -> null
        is JsonArray -> v
        else -> throw InvalidArguments("$key must be an array")
    }

    fun double(args: JsonObject, key: String): Double? = when (val v = args[key]) {
        null -> null
        is JsonPrimitive -> v.doubleOrNull ?: throw InvalidArguments("$key must be a number")
        else -> throw InvalidArguments("$key must be a number")
    }

    fun ops(args: JsonObject, max: Int): List<ChangeOp> {
        val raw: JsonElement = array(args, "ops") ?: throw InvalidArguments("ops is required")
        val ops = try {
            ChangeOp.json.decodeFromJsonElement(ListSerializer(ChangeOp.serializer()), raw)
        } catch (e: Exception) {
            throw InvalidArguments(e.message ?: "ops do not match the change_op schema")
        }
        if (ops.isEmpty() || ops.size > max) throw InvalidArguments("ops must have 1-$max items")
        return ops
    }

    /** LEAN PROFILE: `rationale` is optional in the schema; a missing one falls back to the batch summary or label. */
    fun withRationale(ops: List<ChangeOp>, fallback: String): List<ChangeOp> =
        ops.map { if (it.rationale.isNullOrBlank()) it.copy(rationale = fallback.take(ChangeSetValidator.MAX_RATIONALE)) else it }

    fun encodeOps(ops: List<ChangeOp>): String = ChangeOp.json.encodeToString(ListSerializer(ChangeOp.serializer()), ops)

    fun error(code: String, extra: Map<String, JsonElement> = emptyMap()): JsonObject =
        buildJsonObject { put("error", code); extra.forEach { (k, v) -> put(k, v) } }
}
