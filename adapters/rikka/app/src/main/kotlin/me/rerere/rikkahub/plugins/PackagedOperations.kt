package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.PluginContextMessage
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.serialization.json.encodeToJsonElement

/** Every retained version is resolvable by its exact lease; UI chooses only the preferred versions. */
fun PluginHost.operationDefinitions(): List<ConversationOperation> = state.value.packages.values
    .filter { it.enabled }
    .flatMap { installed ->
        installed.manifest.operations.map { declaration ->
            ConversationOperation(
                id = "${installed.manifest.id}/${declaration.id}",
                title = declaration.title,
                lease = installed.lease,
                capabilities = installed.manifest.capabilities,
            ) { source, input ->
                val messages = source.currentMessages.map {
                    PluginContextMessage(it.id.toString(), it.role.name.lowercase(), it.toText())
                }
                PreparedConversationOperation(source.pluginSources(), execute = { execution ->
                    execution.beforeEffect()
                    withInstance(installed.lease) { instance ->
                        val result = instance.invoke("operation", declaration.id, input) {
                            PluginContract.json.encodeToJsonElement(messages)
                        }
                        PluginContract.result(installed.manifest, result)
                    }
                })
            }
        }
    }

fun PluginHost.operationDispatcher(): SessionOperationDispatcher =
    SessionOperationDispatcher(operationDefinitions(), ::authorized)
