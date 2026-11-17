package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.ExecutionBinding
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginContextMessage
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest

/** A new run captures one catalog publication; resumed runs use their persisted selection. */
fun PluginHost.preferredToolLeases(): List<PackageLease> {
    val catalog = state.value
    return catalog.preferred.toSortedMap().values.map { catalog.packages.getValue(it) }
        .filter { it.enabled && it.manifest.tools.isNotEmpty() }.map { it.lease }
}

/** Provider-safe, bounded names; duplicate-name admission remains authoritative for any collision. */
fun pluginToolName(pluginId: String, toolId: String): String {
    val namespace = MessageDigest.getInstance("SHA-256").digest(pluginId.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it) }
    return "p_${namespace}_$toolId"
}

/** Definitions remain resolvable after revocation so the Agent yields before consuming effect permission. */
fun PluginHost.toolDefinitions(
    leases: List<PackageLease>,
    readMessages: suspend () -> List<UIMessage>,
): List<Tool> = toolDefinitions(ExecutionBinding(leases), readMessages)

fun PluginHost.toolDefinitions(
    binding: ExecutionBinding,
    readMessages: suspend () -> List<UIMessage>,
): List<Tool> = binding.dependencies.flatMap { lease ->
    val installed = requireNotNull(state.value.packages[lease.packageHash]) { "Pinned plugin package is unavailable" }
    require(installed.manifest.id == lease.pluginId)
    installed.manifest.tools.map { declaration ->
        val presentation = JsonObject(mapOf(
            "plugin" to PluginContract.json.encodeToJsonElement(lease),
            "toolId" to JsonPrimitive(declaration.id),
            "title" to JsonPrimitive(declaration.title),
        ))
        val execute: suspend (kotlinx.serialization.json.JsonElement) -> ToolExecutionResult = { input ->
            dev.rikkahub.plugins.core.PluginToolSchema.validate(declaration.parameters, input)
            withInstance(lease, binding) { instance ->
                val result = PluginContract.toolResult(instance.invoke("tool", declaration.id, input) {
                    PluginContract.json.encodeToJsonElement(readMessages().map {
                        PluginContextMessage(it.id.toString(), it.role.name.lowercase(), it.toText())
                    })
                })
                ToolExecutionResult(
                    listOf(UIMessagePart.Text(result.content)),
                    JsonObject(presentation + ("data" to result.details)),
                )
            }
        }
        Tool(
            name = pluginToolName(lease.pluginId, declaration.id),
            description = declaration.description,
            parameters = {
                InputSchema.Obj(
                    declaration.parameters["properties"]?.jsonObject ?: JsonObject(emptyMap()),
                    declaration.parameters["required"]?.jsonArray?.map { it.jsonPrimitive.content },
                    declaration.parameters["additionalProperties"]?.jsonPrimitive?.booleanOrNull,
                )
            },
            needsApproval = { declaration.needsApproval },
            executionVersion = PluginContract.json.encodeToString(lease),
            validateExecution = {
                if (authorizedPackages(binding.dependencies)) null else "Owning plugin permission was revoked"
            },
            presentation = presentation,
            executeDetailed = execute,
            execute = { execute(it).output },
        )
    }
}
