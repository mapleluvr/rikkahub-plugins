package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.InstalledPlugin
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginCatalogState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    ): InstalledPlugin = changes.withLock {
        val previous = state.value
        check(previous.revision == expectedRevision) { "Activation changed during preflight; retry with current state" }
        require(granted == pkg.manifest.capabilities) { "Approve the package's declared capabilities" }
        val collision = previous.packages.values.any {
            it.manifest.id == pkg.manifest.id && it.manifest.version == pkg.manifest.version &&
                it.lease.packageHash != pkg.hash
        }
        require(!collision) { "Same plugin version has different bytes" }
        val existing = previous.packages[pkg.hash]
        if (existing?.enabled == true && existing.granted == granted) {
            if (previous.preferred[pkg.manifest.id] != pkg.hash) {
                commit(previous.copy(
                    revision = previous.revision + 1,
                    preferred = previous.preferred + (pkg.manifest.id to pkg.hash),
                ))
            }
            return@withLock existing
        }
        val installed = InstalledPlugin(
            pkg.manifest,
            PackageLease(pkg.manifest.id, pkg.hash, previous.nextEpoch),
            granted.toSet(),
            source = source,
        )
        commit(previous.copy(
            revision = previous.revision + 1,
            nextEpoch = previous.nextEpoch + 1,
            packages = previous.packages + (pkg.hash to installed),
            preferred = previous.preferred + (pkg.manifest.id to pkg.hash),
        ))
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

    private fun validate(value: PluginCatalogState) {
        require(value.version == 1 && value.revision >= 0 && value.nextEpoch > 0)
        value.packages.forEach { (hash, record) ->
            PluginArchive.requireHash(hash)
            require(hash == record.lease.packageHash && record.lease.pluginId == record.manifest.id)
            require(record.lease.activationEpoch in 1 until value.nextEpoch)
            require(record.manifest.capabilities.containsAll(record.granted))
        }
        value.preferred.forEach { (id, hash) ->
            require(value.packages[hash]?.let { it.enabled && it.manifest.id == id } == true)
        }
    }
}
