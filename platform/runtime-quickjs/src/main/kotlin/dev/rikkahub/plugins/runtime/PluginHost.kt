package dev.rikkahub.plugins.runtime

import android.content.Context
import dev.rikkahub.plugins.core.ExecutionAuthority
import dev.rikkahub.plugins.core.ExecutionAuthorizationRevoked
import dev.rikkahub.plugins.core.ExecutionBinding
import dev.rikkahub.plugins.core.PluginCatalog
import dev.rikkahub.plugins.core.PluginCatalogFile
import dev.rikkahub.plugins.core.PluginPackageStore
import dev.rikkahub.plugins.core.PluginReleaseCandidate
import dev.rikkahub.plugins.core.PluginReleaseContract
import dev.rikkahub.plugins.core.VerifiedPluginPackage
import dev.rikkahub.plugins.protocol.InstalledPlugin
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginCatalogState
import dev.rikkahub.plugins.protocol.PluginReleaseOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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

    private class InvocationOwner(val host: PluginHost, val previous: InvocationOwner?,) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<InvocationOwner>
        fun contains(target: PluginHost): Boolean = host === target || previous?.contains(target) == true
    }

    private suspend fun requireExternalManagement() {
        check(currentCoroutineContext()[InvocationOwner]?.contains(this) != true) {
            "Plugin lifecycle changes must be scheduled outside their owned invocation scopes"
        }
    }

    private val lifecycle = Mutex()

    @Volatile private var closed = false
    val state: StateFlow<PluginCatalogState> = catalog.state
    private val authority = ExecutionAuthority { dependencies ->
        dependencies.all { lease ->
            state.value.packages[lease.packageHash]?.let { catalog.authorized(lease, it.manifest.capabilities) } == true
        }
    }

    fun authorized(lease: PackageLease, capabilities: Set<String>): Boolean =
        !closed && authority.allowed(listOf(lease)) && catalog.authorized(lease, capabilities)

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
        catalog.activate(
            candidate.pkg,
            granted,
            candidate.revision,
            source,
            candidate.origin,
            allowRollback,
            allowSourceChange
        )
    }

    /** Order revocation before any later dispatch, commit it, then drain every dependent child scope. */
    suspend fun disable(pluginId: String) {
        requireExternalManagement()
        withContext(NonCancellable) {
            lifecycle.withLock {
                check(!closed)
                val revoked = authority.beginRevocation(pluginId)
                try {
                    catalog.disable(pluginId)
                } finally {
                    revoked.forEach { it.owner.cancel(CancellationException("Plugin execution authorization revoked")) }
                    revoked.forEach { it.owner.join() }
                    authority.endRevocation(pluginId)
                }
            }
        }
    }

    /** Registration precedes asynchronous preparation; the complete binding owns callbacks and final transport sends. */
    suspend fun <T> withInstance(
        lease: PackageLease,
        binding: ExecutionBinding = ExecutionBinding(listOf(lease)),
        block: suspend (IsolatedPluginClient) -> T,
    ): T {
        var execution: ExecutionAuthority.Scope? = null
        try {
            return coroutineScope {
                val owner = currentCoroutineContext().job
                val scope = lifecycle.withLock {
                    check(!closed)
                    authority.open(binding.copy(dependencies = (binding.dependencies + lease).distinct()), owner)
                        .also { execution = it }
                }
                try {
                    val ancestry = InvocationOwner(this@PluginHost, currentCoroutineContext()[InvocationOwner])
                    withContext(ancestry) {
                        val bytes = withContext(Dispatchers.IO) {
                            scope.check()
                            packages.readArchive(lease.packageHash)
                        }
                        scope.check()
                        val client = IsolatedPluginClient.prepare(context, bytes, lease, ::authorized, scope)
                        try {
                            block(client)
                        } finally {
                            client.close()
                        }
                    }
                } finally {
                    scope.close()
                }
            }
        } catch (error: CancellationException) {
            // Cancelling an execution child is distinct from user Stop of its parent logical run.
            if (execution?.revoked == true) throw ExecutionAuthorizationRevoked()
            throw error
        }
    }

    suspend fun close() {
        requireExternalManagement()
        withContext(NonCancellable) {
            lifecycle.withLock {
                if (!closed) {
                    closed = true
                    val active = authority.close()
                    active.forEach { it.owner.cancel(CancellationException("Plugin host closed")) }
                    active.forEach { it.owner.join() }
                    withContext(Dispatchers.IO) { storage.close() }
                }
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
