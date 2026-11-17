package dev.rikkahub.plugins.protocol

import kotlinx.serialization.Serializable

@Serializable
data class PluginReleaseIndex(val schemaVersion: Int = 1, val packages: List<PluginReleasePackage>)

@Serializable
data class PluginReleasePackage(
    val id: String,
    val version: String,
    val asset: String,
    val sha256: String,
    val sizeBytes: Int,
    val apiVersion: String,
    val runtime: String,
    val requiredFeatures: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
)

/** Immutable remote identities are kept separately from a repository's display name. */
@Serializable
data class PluginReleaseOrigin(
    val repositoryId: Long,
    val repository: String,
    val releaseId: Long,
    val tag: String,
    val assetId: Long,
    val publisherKey: String? = null,
)

@Serializable
data class PluginReleaseSignature(val algorithm: String = "ES256", val publicKey: String, val signature: String,)
