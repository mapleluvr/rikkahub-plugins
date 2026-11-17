package dev.rikkahub.plugins.runtime

import android.content.Context
import dev.rikkahub.plugins.core.PluginCatalog
import dev.rikkahub.plugins.core.PluginCatalogFile
import dev.rikkahub.plugins.core.PluginPackageStore
import dev.rikkahub.plugins.core.VerifiedPluginPackage
import dev.rikkahub.plugins.protocol.InstalledPlugin
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginCatalogState
import dev.rikkahub.plugins.protocol.PluginReleaseOrigin
import dev.rikkahub.plugins.core.PluginReleaseCandidate
import dev.rikkahub.plugins.core.PluginReleaseContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One application owner. Package bytes, confirmed activation and transient engines have separate lifetimes. */
class PluginHost private constructor(
    private val context: Context,
    private val packages: PluginPackageStore,
    private val storage: PluginCatalogFile,
    private val catalog: PluginCatalog,
) {
    class Candidate internal constructor(
        internal val owner: PluginHost,
        val pkg: VerifiedPluginPackage,
        val revision: Long,
        val origin: PluginReleaseOrigin? = null,
    )

    private val lifecycle = Mutex()
    private val clients = ConcurrentHashMap<IsolatedPluginClient, Job>()

    @Volatile private var closed = false
    val state: StateFlow<PluginCatalogState> = catalog.state

    fun authorized(lease: PackageLease, capabilities: Set<String>): Boolean =
        !closed && catalog.authorized(lease, capabilities)

    /** Registration has no host capabilities. Consent is requested only for a fully checked candidate. */
    suspend fun prepare(bytes: ByteArray, expectedHash: String? = null): Candidate = lifecycle.withLock {
        check(!closed)
        val revision = state.value.revision
        val pkg = withContext(Dispatchers.IO) { packages.install(bytes, expectedHash) }
        val archive = withContext(Dispatchers.IO) { packages.readArchive(pkg.hash) }
        val probe = IsolatedPluginClient.prepare(
            context,
            archive,
            PackageLease(pkg.manifest.id, pkg.hash, 1),
            { _, _ -> false },
        )
        check(probe.close()) { "Plugin preflight did not release its runtime" }
        Candidate(this, pkg, revision)
    }

    suspend fun prepareInstalled(hash: String): Candidate {
        val bytes = withContext(Dispatchers.IO) { packages.readArchive(hash) }
        val prepared = prepare(bytes, hash)
        val origin = state.value.packages[hash]?.origin
        return Candidate(this, prepared.pkg, prepared.revision, origin)
    }

    suspend fun prepareRelease(bytes: ByteArray, selected: PluginReleaseCandidate): Candidate {
        PluginReleaseContract.verifyPackage(selected.entry, bytes)
        val prepared = prepare(bytes, selected.entry.sha256)
        return Candidate(this, prepared.pkg, prepared.revision, selected.origin)
    }

    suspend fun activate(
        candidate: Candidate,
        granted: Set<String>,
        source: String = candidate.origin?.let { "github:" + it.repository } ?: "local",
        allowRollback: Boolean = false,
        allowSourceChange: Boolean = false,
    ): InstalledPlugin = lifecycle.withLock {
        check(!closed && candidate.owner === this) { "Candidate belongs to an expired host" }
        catalog.activate(candidate.pkg, granted, candidate.revision, source, candidate.origin, allowRollback, allowSourceChange)
    }

    /** Commit revocation first. All retained versions become unauthorized before engine shutdown begins. */
    suspend fun disable(pluginId: String) = withContext(NonCancellable) {
        lifecycle.withLock {
            check(!closed)
            catalog.disable(pluginId)
            val revoked = clients.entries.filter { it.key.lease.pluginId == pluginId }
            revoked.forEach { it.value.cancel() }
            revoked.forEach { it.key.close() }
            revoked.forEach { it.value.join() }
        }
    }

    /** Scope owns callbacks; cancellation, panel disposal and task completion drain the exact instance. */
    suspend fun <T> withInstance(lease: PackageLease, block: suspend (IsolatedPluginClient) -> T): T = coroutineScope {
        val owner = currentCoroutineContext().job
        val client = lifecycle.withLock {
            val installed = requireNotNull(state.value.packages[lease.packageHash]) { "Plugin package unavailable" }
            check(authorized(lease, installed.manifest.capabilities)) { "Plugin lease was revoked" }
            val bytes = withContext(Dispatchers.IO) { packages.readArchive(lease.packageHash) }
            IsolatedPluginClient.prepare(context, bytes, lease, ::authorized).also { clients[it] = owner }
        }
        try {
            block(client)
        } finally {
            client.close()
            clients.remove(client)
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        lifecycle.withLock {
            if (!closed) {
                closed = true
                val active = clients.entries.toList()
                active.forEach { it.value.cancel() }
                active.forEach { it.key.close() }
                active.forEach { it.value.join() }
                clients.clear()
                withContext(Dispatchers.IO) { storage.close() }
            }
        }
    }

    companion object {
        suspend fun open(context: Context, root: File): PluginHost {
            var opened: PluginHost? = null
            try {
                return withContext(Dispatchers.IO) { openOnIo(context, root).also { opened = it } }
            } catch (error: Throwable) {
                // withContext may discard a successful result if its caller is cancelled on dispatch back.
                opened?.close()
                throw error
            }
        }

        private fun openOnIo(context: Context, root: File): PluginHost {
            val storage = PluginCatalogFile.open(root.toPath())
            try {
                val packages = PluginPackageStore(root.toPath().resolve("packages"))
                val initial = storage.read()
                // Verify retained versions too: resumed calls must never silently fall back to a newer package.
                initial.packages.forEach { (hash, record) ->
                    require(packages.load(hash).manifest == record.manifest) { "Installed package metadata changed" }
                }
                val catalog = PluginCatalog(initial) { state -> withContext(Dispatchers.IO) { storage.write(state) } }
                return PluginHost(context.applicationContext, packages, storage, catalog)
            } catch (error: Throwable) {
                storage.close()
                throw error
            }
        }
    }
}
