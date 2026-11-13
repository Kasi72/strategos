package com.drkkr.strategos.domain.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Cards in the chat are described in the A2UI 1.0 message format (a2ui.org): `createSurface`, `updateComponents`,
 * `updateDataModel`. Today the app writes these messages itself, on the device, from what the engine already worked
 * out, so a card costs no model tokens. Because the messages are the standard ones, a model or Google's own renderer
 * can take either end later without the other changing.
 *
 * Only the components of the app's own catalog are ever drawn. A message that names anything else is dropped whole.
 */
object A2ui {
    const val VERSION = "1.0"
    /** The app's catalog: the basic catalog's Card, Column, Row, Text, Button and Divider, plus Option and Step. */
    const val CATALOG = "urn:strategos:a2ui:catalog:calm:v1"
    const val ROOT = "root"

    /** A button or option that sends [UiAction.context]'s `text` as the user's next message. */
    const val REPLY = "reply"
    /** Opens the project overview, where what the app has on record can be checked and fixed. */
    const val OPEN_OVERVIEW = "open_overview"

    const val MAX_COMPONENTS = 64
    const val MAX_DEPTH = 8
    const val MAX_TEXT = 600
}

/** What a tap asks for: an event name and its plain-text context, as in A2UI's `action.event`. */
data class UiAction(val name: String, val context: Map<String, String> = emptyMap()) {
    val isReply: Boolean get() = name == A2ui.REPLY
    val text: String get() = context["text"].orEmpty()
}

/** One drawn component, with its references already followed and its bound values already read. */
sealed class UiNode {
    abstract val id: String
    data class Card(override val id: String, val child: UiNode) : UiNode()
    data class Column(override val id: String, val children: List<UiNode>) : UiNode()
    data class Row(override val id: String, val children: List<UiNode>) : UiNode()
    /** [variant] is `h3` (a card's heading), `body` or `caption`. */
    data class Text(override val id: String, val text: String, val variant: String) : UiNode()
    data class Button(override val id: String, val label: String, val primary: Boolean, val action: UiAction?) : UiNode()
    data class Divider(override val id: String) : UiNode()
    /** A tappable choice: a small [tag] above its [title], with an optional [detail] line. */
    data class Option(override val id: String, val tag: String, val title: String, val detail: String, val action: UiAction?) : UiNode()
    /** One point on a timeline: when ([label]), what ([title]) and a quieter [detail]. */
    data class Step(override val id: String, val label: String, val title: String, val detail: String) : UiNode()
}

/** A card ready to draw. */
data class Surface(val id: String, val root: UiNode) {
    /** Every action a tap on this card can raise, for tests and for deciding whether it is still live. */
    fun actions(): List<UiAction> {
        val out = mutableListOf<UiAction>()
        fun walk(n: UiNode) {
            when (n) {
                is UiNode.Card -> walk(n.child)
                is UiNode.Column -> n.children.forEach(::walk)
                is UiNode.Row -> n.children.forEach(::walk)
                is UiNode.Button -> n.action?.let { out += it }
                is UiNode.Option -> n.action?.let { out += it }
                else -> Unit
            }
        }
        walk(root)
        return out
    }
}

/** Reads A2UI messages into cards. Pure, and strict: anything it does not fully understand is not drawn. */
object A2uiReader {
    private class Draft(val id: String, val catalog: String?) {
        val components = LinkedHashMap<String, JsonObject>()
        var data: JsonElement = JsonObject(emptyMap())
    }

    /** The cards the [messages] describe, in the order their surfaces were created. */
    fun surfaces(messages: JsonArray): List<Surface> {
        val drafts = LinkedHashMap<String, Draft>()
        for (el in messages) {
            val m = el as? JsonObject ?: continue
            if (str(m["version"]) != A2ui.VERSION) continue
            (m["createSurface"] as? JsonObject)?.let { c ->
                val id = str(c["surfaceId"]) ?: return@let
                val d = Draft(id, str(c["catalogId"]))
                (c["components"] as? JsonArray)?.let { put(d, it) }
                c["dataModel"]?.let { d.data = it }
                drafts[id] = d
            }
            (m["updateComponents"] as? JsonObject)?.let { u ->
                val d = drafts[str(u["surfaceId"])] ?: return@let
                (u["components"] as? JsonArray)?.let { put(d, it) }
            }
            (m["updateDataModel"] as? JsonObject)?.let { u ->
                val d = drafts[str(u["surfaceId"])] ?: return@let
                d.data = Pointer.set(d.data, str(u["path"]) ?: "/", u["value"] ?: JsonNull)
            }
            (m["deleteSurface"] as? JsonObject)?.let { u -> drafts.remove(str(u["surfaceId"])) }
        }
        return drafts.values.mapNotNull(::resolve)
    }

    fun surfaces(json: String): List<Surface> =
        runCatching { (Json.parseToJsonElement(json) as? JsonObject)?.get("messages") as? JsonArray }.getOrNull()?.let(::surfaces).orEmpty()

    private fun put(d: Draft, components: JsonArray) {
        for (c in components) {
            val o = c as? JsonObject ?: continue
            d.components[str(o["id"]) ?: continue] = o
        }
    }

    private class Unreadable : Exception()

    private fun resolve(d: Draft): Surface? {
        if (d.catalog != A2ui.CATALOG || d.components.size > A2ui.MAX_COMPONENTS) return null
        val seen = HashSet<String>()
        fun text(o: JsonObject, key: String): String {
            val v = o[key] ?: return ""
            val raw = when (v) {
                is JsonPrimitive -> if (v is JsonNull) "" else v.content
                is JsonObject -> str(v["@path"])?.let { p -> (Pointer.get(d.data, p) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content }.orEmpty()
                else -> ""
            }
            return raw.take(A2ui.MAX_TEXT)
        }
        fun action(o: JsonObject): UiAction? {
            val e = (o["action"] as? JsonObject)?.get("event") as? JsonObject ?: return null
            val name = str(e["name"]) ?: return null
            val ctx = (e["context"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { k to it.content } }.toMap()
            return UiAction(name, ctx)
        }
        fun node(id: String, depth: Int): UiNode {
            if (depth > A2ui.MAX_DEPTH || !seen.add(id)) throw Unreadable()        // too deep, or drawn twice (a loop)
            val o = d.components[id] ?: throw Unreadable()
            fun kids() = (o["children"] as? JsonArray)?.map { node(str(it) ?: throw Unreadable(), depth + 1) } ?: throw Unreadable()
            return when (str(o["component"])) {
                "Card" -> UiNode.Card(id, node(str(o["child"]) ?: throw Unreadable(), depth + 1))
                "Column" -> UiNode.Column(id, kids())
                "Row" -> UiNode.Row(id, kids())
                "Text" -> UiNode.Text(id, text(o, "text"), str(o["variant"]) ?: "body")
                "Button" -> {
                    val label = (node(str(o["child"]) ?: throw Unreadable(), depth + 1) as? UiNode.Text)?.text ?: throw Unreadable()
                    UiNode.Button(id, label, str(o["variant"]) == "primary", action(o))
                }
                "Divider" -> UiNode.Divider(id)
                "Option" -> UiNode.Option(id, text(o, "tag"), text(o, "title"), text(o, "detail"), action(o))
                "Step" -> UiNode.Step(id, text(o, "label"), text(o, "title"), text(o, "detail"))
                else -> throw Unreadable()                                           // not in the catalog
            }
        }
        return try { Surface(d.id, node(A2ui.ROOT, 0)) } catch (_: Unreadable) { null }
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** JSON Pointer (RFC 6901) over objects, which is all the data model of a card needs. */
internal object Pointer {
    private fun parts(path: String) = path.trim('/').split('/').filter { it.isNotEmpty() }.map { it.replace("~1", "/").replace("~0", "~") }

    fun get(root: JsonElement, path: String): JsonElement? = parts(path).fold(root as JsonElement?) { at, key -> (at as? JsonObject)?.get(key) }

    /** [root] with [value] at [path]; a null value removes the key. Missing objects on the way are created. */
    fun set(root: JsonElement, path: String, value: JsonElement): JsonElement {
        val keys = parts(path)
        if (keys.isEmpty()) return if (value is JsonNull) JsonObject(emptyMap()) else value
        fun at(node: JsonElement?, i: Int): JsonElement {
            val obj = (node as? JsonObject).orEmpty().toMutableMap()
            val key = keys[i]
            if (i == keys.lastIndex) { if (value is JsonNull) obj.remove(key) else obj[key] = value }
            else obj[key] = at(obj[key], i + 1)
            return JsonObject(obj)
        }
        return at(root, 0)
    }
}

/** Writes one card as A2UI messages. Each call adds a component and returns its id, to be used as a child. */
class SurfaceWriter(private val surfaceId: String) {
    private val components = mutableListOf<JsonObject>()
    private var next = 0

    private fun add(type: String, id: String?, fill: JsonObjectBuilder.() -> Unit): String {
        val cid = id ?: "c${next++}"
        components += buildJsonObject { put("id", cid); put("component", type); fill() }
        return cid
    }

    private fun JsonObjectBuilder.event(a: UiAction) = putJsonObject("action") {
        putJsonObject("event") { put("name", a.name); putJsonObject("context") { a.context.forEach { (k, v) -> put(k, v) } } }
    }

    fun text(text: String, variant: String = "body", id: String? = null) = add("Text", id) { put("text", text); put("variant", variant) }
    fun button(label: String, action: UiAction, primary: Boolean = false, id: String? = null): String {
        val child = text(label)
        return add("Button", id) { put("child", child); put("variant", if (primary) "primary" else "borderless"); event(action) }
    }
    fun option(tag: String, title: String, detail: String = "", action: UiAction? = null, id: String? = null) = add("Option", id) {
        put("tag", tag); put("title", title); put("detail", detail); action?.let { event(it) }
    }
    fun step(label: String, title: String, detail: String = "", id: String? = null) = add("Step", id) { put("label", label); put("title", title); put("detail", detail) }
    fun divider(id: String? = null) = add("Divider", id) { put("axis", "horizontal") }
    fun column(children: List<String>, id: String? = null) = add("Column", id) { putJsonArray("children") { children.forEach { add(JsonPrimitive(it)) } } }
    fun row(children: List<String>, id: String? = null) = add("Row", id) { putJsonArray("children") { children.forEach { add(JsonPrimitive(it)) } } }
    fun card(child: String, id: String? = null) = add("Card", id) { put("child", child) }

    /** `createSurface` then `updateComponents`. The caller must have given one component the id [A2ui.ROOT]. */
    fun messages(): JsonArray = buildJsonArray {
        add(buildJsonObject { put("version", A2ui.VERSION); putJsonObject("createSurface") { put("surfaceId", surfaceId); put("catalogId", A2ui.CATALOG) } })
        add(buildJsonObject {
            put("version", A2ui.VERSION)
            putJsonObject("updateComponents") { put("surfaceId", surfaceId); put("components", JsonArray(components)) }
        })
    }
}

/** How a card is stored on a message: its A2UI messages under one key, so the payload is a JSON object like the others. */
object SurfaceCodec {
    fun encode(messages: JsonArray): String = buildJsonObject { put("messages", messages) }.toString()
    fun decode(json: String?): Surface? = json?.let { A2uiReader.surfaces(it).firstOrNull() }
}
