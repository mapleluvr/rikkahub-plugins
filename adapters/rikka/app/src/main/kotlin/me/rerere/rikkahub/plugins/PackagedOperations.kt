package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.PluginContextMessage
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.serialization.json.encodeToJsonElement

/** Retained versions remain resolvable; session admission still checks the selected package and epoch. */
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
                check(source.selectedPackage(installed.lease)) { "Conversation plugin version changed" }
                val messages = source.currentMessages.map {
                    PluginContextMessage(it.id.toString(), it.role.name.lowercase(), it.toText())
                }
                PreparedConversationOperation(source.pluginSources(), validateAdoption = { current ->
                    requireOperationAdmission(
                        current.selectedPackage(installed.lease),
                        "Conversation plugin version changed"
                    )
                }, execute = { execution ->
                    withInstance(
                        installed.lease,
                        execution.executionBinding.copy(beforeDispatch = execution.beforeEffect),
                    ) { instance ->
                        val result = instance.invoke("operation", declaration.id, input) {
                            PluginContract.json.encodeToJsonElement(messages)
                        }
                        PluginContract.result(installed.manifest, result)
                    }
                })
            }
        }
    }

fun PluginHost.authorizedPackages(leases: List<dev.rikkahub.plugins.protocol.PackageLease>): Boolean =
    leases.all { lease ->
        state.value.packages[lease.packageHash]?.let { authorized(lease, it.manifest.capabilities) } == true
    }

fun PluginHost.operationDispatcher(): SessionOperationDispatcher =
    SessionOperationDispatcher(
        operationDefinitions(),
        packageAuthorization = ::authorizedPackages,
        authorized = ::authorized
    )
