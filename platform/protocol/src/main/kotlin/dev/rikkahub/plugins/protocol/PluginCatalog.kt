package dev.rikkahub.plugins.protocol

import kotlinx.serialization.Serializable

@Serializable
data class InstalledPlugin(
    val manifest: PluginManifest,
    val lease: PackageLease,
    val granted: Set<String>,
    val enabled: Boolean = true,
    val source: String = "local",
    val origin: PluginReleaseOrigin? = null,
)

@Serializable
data class PluginCatalogState(
    val version: Int = 1,
    val revision: Long = 0,
    val nextEpoch: Long = 1,
    val packages: Map<String, InstalledPlugin> = emptyMap(),
    val preferred: Map<String, String> = emptyMap(),
    val updatePolicies: Map<String, PluginUpdatePolicy> = emptyMap(),
    /** Persisted only with the authorization directory, not with conversation backups. */
    val issuerId: String = "",
)
