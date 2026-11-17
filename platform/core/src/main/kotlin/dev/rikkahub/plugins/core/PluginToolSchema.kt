package dev.rikkahub.plugins.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Version 1 supports a bounded structural JSON Schema subset, checked before package activation. */
object PluginToolSchema {
    private val types = setOf("object", "array", "string", "number", "integer", "boolean", "null")
    private val commonKeys = setOf("type", "description", "enum")

    fun check(schema: JsonObject) {
        require(schema["type"] == JsonPrimitive("object")) { "Tool arguments must be an object" }
        // The provider-facing InputSchema.Obj preserves exactly these root keys.
        require(schema.keys.all { it in setOf("type", "properties", "required", "additionalProperties") }) {
            "Unsupported root schema keyword"
        }
        var count = 0
        fun visit(node: JsonObject, depth: Int) {
            require(++count <= 256 && depth <= 12) { "Tool schema exceeds limit" }
            val type = (node["type"] as? JsonPrimitive)?.contentOrNull
            require(type in types) { "Unsupported schema type" }
            val keys = commonKeys + when (type) {
                "object" -> setOf("properties", "required", "additionalProperties")
                "array" -> setOf("items")
                else -> emptySet()
            }
            require(node.keys.all { it in keys }) { "Unsupported schema keyword" }
            node["description"]?.let { require(it is JsonPrimitive && it.isString && it.content.length <= 4096) }
            node["enum"]?.let { require(it is JsonArray && it.size in 1..64) }
            when (type) {
                "object" -> {
                    val properties = node["properties"]?.let {
                        require(it is JsonObject)
                        it
                    } ?: JsonObject(emptyMap())
                    require(properties.keys.all { it.length in 1..120 })
                    node["required"]?.let { required ->
                        require(required is JsonArray && required.distinct().size == required.size)
                        require(required.all { it is JsonPrimitive && it.isString && it.content in properties })
                    }
                    node["additionalProperties"]?.let {
                        require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null)
                    }
                    properties.values.forEach {
                        require(it is JsonObject)
                        visit(it, depth + 1)
                    }
                }

                "array" -> {
                    val items = node["items"]
                    require(items is JsonObject) { "Array schema must declare items" }
                    visit(items, depth + 1)
                }
            }
        }
        visit(schema, 0)
    }

    /** Called at execution admission as well as in the isolated runtime, before any host context read. */
    fun validate(schema: JsonObject, input: JsonElement) {
        // Budget the whole input, including additionalProperties, before recursive serialization.
        var count = 0
        fun budget(value: JsonElement, depth: Int) {
            require(++count <= 10000 && depth <= 16) { "Tool input exceeds structural limit" }
            when (value) {
                is JsonObject -> value.values.forEach { budget(it, depth + 1) }
                is JsonArray -> value.forEach { budget(it, depth + 1) }
                else -> Unit
            }
        }
        budget(input, 0)
        require(input.toString().length <= 256 * 1024) { "Tool input exceeds limit" }
        fun visit(node: JsonObject, value: JsonElement, depth: Int) {
            val primitive = value as? JsonPrimitive
            val number = primitive?.takeIf { !it.isString }?.doubleOrNull
            val valid = when (node.getValue("type").jsonPrimitive.content) {
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                "string" -> primitive?.isString == true
                "number" -> number?.isFinite() == true
                "integer" -> number?.let { it.isFinite() && it % 1.0 == 0.0 } == true
                "boolean" -> primitive?.let { !it.isString && it.booleanOrNull != null } == true
                "null" -> value == JsonNull
                else -> false
            }
            require(valid) { "Tool input type disagrees with its schema" }
            (node["enum"] as? JsonArray)?.let { require(value in it) { "Tool input is outside the declared enum" } }
            if (value is JsonObject) {
                val properties = node["properties"] as? JsonObject ?: JsonObject(emptyMap())
                (node["required"] as? JsonArray)?.forEach {
                    require(it.jsonPrimitive.content in value) { "Missing required tool argument" }
                }
                if (node["additionalProperties"] == JsonPrimitive(false)) {
                    require(value.keys.all { it in properties }) { "Unexpected tool argument" }
                }
                value.forEach { (key, child) -> properties[key]?.let { visit(it.jsonObject, child, depth + 1) } }
            }
            if (value is JsonArray) value.forEach { visit(node.getValue("items").jsonObject, it, depth + 1) }
        }
        visit(schema, input, 0)
    }
}
