package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginCatalogState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.utils.JsonInstant

private const val PACKAGE_SELECTION = "host.packages"

@Serializable
data class SessionPackageSelection(val version: Int = 1, val packages: Map<String, String> = emptyMap())

fun Conversation.packageSelection(): SessionPackageSelection? = pluginState[PACKAGE_SELECTION]?.let {
    JsonInstant.decodeFromJsonElement<SessionPackageSelection>(it).also { selection ->
        require(selection.version == 1) { "Unsupported conversation package selection" }
    }
}

fun Conversation.withPackageSelection(selection: SessionPackageSelection): Conversation = copy(
    pluginState = JsonObject(pluginState + (PACKAGE_SELECTION to JsonInstant.encodeToJsonElement(selection)))
)

fun PluginCatalogState.preferredSelection(): SessionPackageSelection = SessionPackageSelection(packages = preferred.toMap())

/** Disabling a plugin removes it from new runs. Re-enabling uses its new epoch; old run approvals keep their old lease. */
fun SessionPackageSelection.resolve(
    catalog: PluginCatalogState
): List<PackageLease> = packages.toSortedMap().mapNotNull { (id, hash) ->
    val installed = requireNotNull(catalog.packages[hash]) { "Conversation package is unavailable" }
    require(installed.manifest.id == id)
    installed.takeIf { it.enabled }?.lease
}

/** Current run identity takes precedence, including the exact epoch held by an outstanding approval. */
fun Conversation.selectedPackage(lease: PackageLease): Boolean {
    val active = executionLedger().runs.values.singleOrNull {
        it.state.status == dev.rikkahub.plugins.protocol.RunStatus.ACTIVE
    }
    if (active != null) return lease in active.pluginLeases
    return packageSelection()?.let { it.packages[lease.pluginId] == lease.packageHash } ?: true
}

fun Conversation.packageLeases(catalog: PluginCatalogState): List<PackageLease> =
    executionLedger().runs.values.singleOrNull {
        it.state.status == dev.rikkahub.plugins.protocol.RunStatus.ACTIVE
    }?.pluginLeases ?: (packageSelection() ?: catalog.preferredSelection()).resolve(catalog)

/** First use persists even an empty selection, so a later global install cannot silently add tools to this conversation. */
suspend fun ConversationSession.capturePackageSelection(catalog: PluginCatalogState): List<PackageLease> {
    val saved = state.value.packageSelection() ?: commitExtension(incarnation) { current ->
        if (current.packageSelection() == null) current.withPackageSelection(catalog.preferredSelection()) else current
    }.packageSelection()!!
    return saved.resolve(catalog)
}
