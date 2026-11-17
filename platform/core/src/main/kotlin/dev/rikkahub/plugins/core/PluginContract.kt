package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginToolResult
import dev.rikkahub.plugins.protocol.PluginUiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Wire validation is independent of Android, engine execution and installation state. */
object PluginContract {
    val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }
    val capabilities =
        setOf("context.read", "context.write", "artifacts.write", "tools.register", "ui.actions", "ui.overlay.app")
    val features = setOf("operations.v1", "tools.v1", "ui.native.v1", "ui.overlay.app.v1")
    private val localId = Regex("[a-z][a-z0-9_]{0,39}")
    private val pluginId = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9_-]*)+")
    private val version =
        Regex(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?"
        )

    fun manifest(text: String): PluginManifest {
        require(text.length <= 64 * 1024) { "Manifest exceeds size limit" }
        val value = json.decodeFromString<PluginManifest>(text)
        require(value.id.length <= 120 && pluginId.matches(value.id)) { "Invalid plugin ID" }
        require(value.version.length <= 80 && version.matches(value.version)) { "Invalid plugin version" }
        val prerelease = value.version.substringBefore('+').substringAfter('-', "")
        require(prerelease.split('.').none { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) {
            "Noncanonical prerelease version"
        }
        require(value.name.isNotBlank() && value.name.length <= 120)
        require(value.apiVersion == "1" && value.runtime == "quickjs-es2020") { "Unsupported plugin runtime/API" }
        require(capabilities.containsAll(value.capabilities)) { "Unsupported capability" }
        require(features.containsAll(value.requiredFeatures)) { "Unsupported feature" }
        require(value.entry.endsWith(".js"))
        path(value.entry)
        value.resources.forEach(::path)
        require(value.resources.distinct().size == value.resources.size)
        require(value.entry !in value.resources && "manifest.json" !in value.resources)
        val ids = value.operations.map { it.id } + value.tools.map { it.id } + value.panels.map { it.id }
        require(ids.size in 1..64 && ids.all(localId::matches)) { "Invalid contribution IDs" }
        for (group in listOf(value.operations.map { it.id }, value.tools.map { it.id }, value.panels.map { it.id })) {
            require(group.distinct().size == group.size) { "Duplicate contribution" }
        }
        require(
            (value.operations.map { it.title } + value.tools.map { it.title } + value.panels.map { it.title })
                .all { it.isNotBlank() && it.length <= 120 }
        )
        require(value.tools.isEmpty() || "tools.register" in value.capabilities)
        require(value.panels.isEmpty() || "ui.actions" in value.capabilities)
        require(value.panels.none { it.overlay } || "ui.overlay.app" in value.capabilities)
        value.tools.forEach {
            require(it.description.length in 1..4096)
            PluginToolSchema.check(it.parameters)
        }
        return value
    }

    fun path(value: String) {
        require(value.length in 1..200 && Regex("[A-Za-z0-9_./-]+").matches(value)) { "Invalid package path" }
        require(value.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.endsWith('.') }) {
            "Unsafe package path"
        }
    }

    fun result(manifest: PluginManifest, text: String): OperationResult {
        bounded(text)
        return json.decodeFromString<OperationResult>(text).also {
            require(it.projection == null || "context.write" in manifest.capabilities) {
                "Projection permission required"
            }
            require(it.artifacts.isEmpty() || "artifacts.write" in manifest.capabilities) {
                "Artifact permission required"
            }
            require(it.artifacts.size <= 32 && it.artifacts.all { artifact -> artifact.type.length in 1..120 })
            require((it.projection?.omit?.size ?: 0) <= 10000)
        }
    }

    fun toolResult(text: String): PluginToolResult {
        bounded(text)
        return json.decodeFromString(text)
    }

    fun ui(manifest: PluginManifest, text: String): PluginUiNode {
        bounded(text)
        val root = json.decodeFromString<PluginUiNode>(text)
        var count = 0
        fun checkNode(node: PluginUiNode, depth: Int) {
            require(++count <= 200 && depth <= 12) { "UI tree exceeds limit" }
            require(node.type in setOf("text", "markdown", "column", "button")) { "Unsupported UI node" }
            require(node.text.length <= 32000)
            require(node.type == "column" || node.children.isEmpty())
            if (node.type == "button") {
                require(node.operation in manifest.operations.map { it.id }) { "Unknown UI operation" }
            } else {
                require(node.operation == null)
            }
            node.children.forEach { checkNode(it, depth + 1) }
        }
        checkNode(root, 0)
        return root
    }

    private fun bounded(text: String) {
        require(text.length <= 512 * 1024) { "Plugin result exceeds size limit" }
    }
}
