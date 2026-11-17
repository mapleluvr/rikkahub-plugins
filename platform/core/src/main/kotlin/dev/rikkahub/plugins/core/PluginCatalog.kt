package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.InstalledPlugin
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginCatalogState
import dev.rikkahub.plugins.protocol.PluginReleaseOrigin
import dev.rikkahub.plugins.protocol.PluginUpdatePolicy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** P4 catalogs did not yet persist update policy. Both consent UI and activation use the same migration view. */
fun PluginCatalogState.updatePolicy(pluginId: String): PluginUpdatePolicy? = updatePolicies[pluginId]
    ?: packages.values.filter { it.manifest.id == pluginId }.maxByOrNull { PluginVersion.parse(it.manifest.version) }?.let {
        PluginUpdatePolicy(it.manifest.version, it.origin?.repositoryId, it.origin?.repository, it.origin?.publisherKey)
    }

/** Activation metadata commits before registry publication; package bytes are already immutable and verified. */
class PluginCatalog(initial: PluginCatalogState, private val persist: suspend (PluginCatalogState) -> Unit,) {
    private val changes = Mutex()
    private val mutable = MutableStateFlow(initial)
    val state = mutable.asStateFlow()

    init {
        validate(initial)
    }

    fun authorized(lease: PackageLease, capabilities: Set<String>): Boolean {
        val installed = state.value.packages[lease.packageHash] ?: return false
        return installed.lease == lease && installed.enabled && installed.granted.containsAll(capabilities)
    }

    suspend fun activate(
        pkg: VerifiedPluginPackage,
        granted: Set<String>,
        expectedRevision: Long,
        source: String = "local",
        origin: PluginReleaseOrigin? = null,
        allowRollback: Boolean = false,
        allowSourceChange: Boolean = false,
    ): InstalledPlugin = changes.withLock {
        val previous = state.value
        check(previous.revision == expectedRevision) { "Activation changed during preflight; retry with current state" }
        require(granted == pkg.manifest.capabilities) { "Approve the package's declared capabilities" }
        val collision = previous.packages.values.any {
            it.manifest.id == pkg.manifest.id && it.manifest.version == pkg.manifest.version &&
                it.lease.packageHash != pkg.hash
        }
        require(!collision) { "Same plugin version has different bytes" }
        origin?.let(::validateOrigin)
        val id = pkg.manifest.id
        val previousPolicy = previous.updatePolicy(id)
        val nextVersion = PluginVersion.parse(pkg.manifest.version)
        val high = previousPolicy?.highWaterVersion
        require(allowRollback || high == null || nextVersion >= PluginVersion.parse(high)) {
            "Explicit rollback approval required"
        }
        require(allowSourceChange || previousPolicy == null ||
            previousPolicy.repositoryId == origin?.repositoryId && previousPolicy.publisherKey == origin?.publisherKey) {
            "Explicit source or publisher change approval required"
        }
        val policy = PluginUpdatePolicy(
            if (high != null && PluginVersion.parse(high) > nextVersion) high else pkg.manifest.version,
            origin?.repositoryId, origin?.repository, origin?.publisherKey,
        )
        val existing = previous.packages[pkg.hash]
        val retainLease = existing?.enabled == true && existing.granted == granted
        val installed = InstalledPlugin(
            pkg.manifest,
            if (retainLease) existing!!.lease else PackageLease(id, pkg.hash, previous.nextEpoch),
            granted.toSet(),
            source = source,
            origin = origin,
        )
        val changed = previous.copy(
            nextEpoch = previous.nextEpoch + if (retainLease) 0 else 1,
            packages = previous.packages + (pkg.hash to installed),
            preferred = previous.preferred + (id to pkg.hash),
            updatePolicies = previous.updatePolicies + (id to policy),
        )
        if (changed != previous) commit(changed.copy(revision = previous.revision + 1))
        installed
    }

    /** Every retained version is revoked. Re-enabling gets a new epoch; old UI/approval handles stay invalid. */
    suspend fun disable(pluginId: String) = changes.withLock {
        val previous = state.value
        val records = previous.packages.mapValues { (_, record) ->
            if (record.manifest.id == pluginId) record.copy(enabled = false) else record
        }
        if (records != previous.packages) {
            commit(previous.copy(
                revision = previous.revision + 1,
                packages = records,
                preferred = previous.preferred - pluginId,
            ))
        }
    }

    private suspend fun commit(value: PluginCatalogState) = withContext(NonCancellable) {
        validate(value)
        persist(value)
        mutable.value = value
    }

    private fun validateOrigin(origin: PluginReleaseOrigin) {
        require(origin.repositoryId > 0 && origin.releaseId > 0 && origin.assetId > 0)
        require(GitHubPluginSource.parse(origin.repository).tag == null)
        require(origin.tag.isNotBlank() && origin.tag.length <= 200)
        origin.publisherKey?.let(PluginArchive::requireHash)
    }

    private fun validate(value: PluginCatalogState) {
        require(value.version == 1 && value.revision >= 0 && value.nextEpoch > 0)
        value.packages.forEach { (hash, record) ->
            PluginArchive.requireHash(hash)
            require(hash == record.lease.packageHash && record.lease.pluginId == record.manifest.id)
            require(record.lease.activationEpoch in 1 until value.nextEpoch)
            require(record.manifest.capabilities.containsAll(record.granted))
            record.origin?.let(::validateOrigin)
        }
        value.updatePolicies.forEach { (id, policy) ->
            require(value.packages.values.any { it.manifest.id == id })
            PluginVersion.parse(policy.highWaterVersion)
            require((policy.repositoryId == null) == (policy.repository == null))
            policy.repositoryId?.let { require(it > 0) }
            policy.repository?.let { require(GitHubPluginSource.parse(it).tag == null) }
            policy.publisherKey?.let { PluginArchive.requireHash(it); require(policy.repositoryId != null) }
        }
        value.preferred.forEach { (id, hash) ->
            require(value.packages[hash]?.let { it.enabled && it.manifest.id == id } == true)
        }
    }
}
