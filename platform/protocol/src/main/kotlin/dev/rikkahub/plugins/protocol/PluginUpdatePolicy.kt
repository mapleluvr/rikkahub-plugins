package dev.rikkahub.plugins.protocol

import kotlinx.serialization.Serializable

/** Trust and version high-water marks survive disable and explicit rollback. */
@Serializable
data class PluginUpdatePolicy(
    val highWaterVersion: String,
    val repositoryId: Long? = null,
    val repository: String? = null,
    val publisherKey: String? = null,
)
