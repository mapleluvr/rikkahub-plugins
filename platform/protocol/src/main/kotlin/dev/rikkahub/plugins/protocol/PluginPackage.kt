package dev.rikkahub.plugins.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: String = "1",
    val runtime: String = "quickjs-es2020",
    val entry: String = "main.js",
    val capabilities: Set<String> = emptySet(),
    val requiredFeatures: Set<String> = emptySet(),
    val resources: List<String> = emptyList(),
    val operations: List<PluginOperationDeclaration> = emptyList(),
    val tools: List<PluginToolDeclaration> = emptyList(),
    val panels: List<PluginPanelDeclaration> = emptyList(),
)

@Serializable
data class PluginOperationDeclaration(val id: String, val title: String)

@Serializable
data class PluginToolDeclaration(
    val id: String,
    val title: String,
    val description: String,
    val parameters: JsonObject,
    val needsApproval: Boolean = true,
)

@Serializable
data class PluginPanelDeclaration(val id: String, val title: String, val overlay: Boolean = false)

/** The host interprets a bounded native tree; actions refer to declared operation IDs. */
@Serializable
data class PluginUiNode(
    val type: String,
    val text: String = "",
    val children: List<PluginUiNode> = emptyList(),
    val operation: String? = null,
    val input: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PluginToolResult(val content: String, val details: JsonObject = JsonObject(emptyMap()),)

@Serializable
data class PluginContextMessage(val id: String, val role: String, val text: String)
