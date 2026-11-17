package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.PackageLease
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/** Native host binding, never plugin-supplied: captures every owning lease and live admission validation. */
data class ExecutionBinding(
    val dependencies: List<PackageLease>,
    val validate: () -> Unit = {},
    val beforeDispatch: suspend () -> Unit = {},
)

class ExecutionAuthorizationRevoked : IllegalStateException("Owning execution authorization was revoked")

/**
 * Revocation and the final non-suspending dispatch share one lock. Payload/FD preparation and durable
 * admission happen outside it. Owners are child scopes, so revocation drains work without stopping its logical run.
 */
class ExecutionAuthority(private val authorized: (List<PackageLease>) -> Boolean) {
    private val lock = Any()
    private val revoking = mutableSetOf<String>()
    private val scopes = mutableSetOf<Scope>()
    private var closed = false

    fun allowed(dependencies: List<PackageLease>): Boolean = synchronized(lock) { allowedLocked(dependencies) }

    fun open(binding: ExecutionBinding, owner: Job): Scope = synchronized(lock) {
        if (!allowedLocked(binding.dependencies)) throw ExecutionAuthorizationRevoked()
        owner.ensureActive()
        binding.validate()
        Scope(binding.copy(dependencies = binding.dependencies.distinct().toList()), owner).also(scopes::add)
    }

    /** Prevent new scopes/dispatches before the asynchronous catalog revocation is committed. */
    fun beginRevocation(pluginId: String): List<Scope> = synchronized(lock) {
        revoking.add(pluginId)
        scopes.filter { scope -> scope.binding.dependencies.any { it.pluginId == pluginId } }
            .onEach { it.revoked = true }
    }

    /** Even a failed persistent revocation cannot revive a scope already invalidated. */
    fun endRevocation(pluginId: String) = synchronized(lock) { revoking.remove(pluginId) }

    fun close(): List<Scope> = synchronized(lock) {
        closed = true
        scopes.toList().onEach { it.revoked = true }
    }

    private fun allowedLocked(dependencies: List<PackageLease>): Boolean =
        !closed && dependencies.none { it.pluginId in revoking } && authorized(dependencies)

    inner class Scope internal constructor(val binding: ExecutionBinding, val owner: Job) {
        @Volatile var revoked: Boolean = false
            internal set
        private var released = false

        /** Revalidate before host context acquisition and after asynchronous preparation. */
        fun check() = checked { }

        fun <T> checked(block: () -> T): T = synchronized(lock) {
            if (revoked || released || !allowedLocked(binding.dependencies)) throw ExecutionAuthorizationRevoked()
            owner.ensureActive()
            binding.validate()
            block()
        }

        /** Only the final synchronous transport send belongs inside this ordering boundary. */
        fun dispatch(send: () -> Unit) = checked(send)

        fun close() = synchronized(lock) {
            released = true
            scopes.remove(this)
        }
    }
}
