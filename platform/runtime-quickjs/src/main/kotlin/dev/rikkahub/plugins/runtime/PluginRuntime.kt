package dev.rikkahub.plugins.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import dev.rikkahub.plugins.core.ExecutionResultUnavailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One persistent plugin instance. The owner closes it from outside its host callbacks.
 * A failed/cancelled evaluation revokes the instance; callers rebuild from committed state.
 * Host callbacks must cooperate with cancellation and bound their external operations.
 */
class PluginRuntime(private val timeoutMillis: Long = 10_000, private val hostRequest: suspend (String) -> String,) {
    init {
        require(timeoutMillis > 0)
    }

    private val lifecycle = Any()
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.Default)
    private val calls = Mutex()
    private val closing = Mutex()
    private var closed = false

    @Volatile private var activeChain: RuntimeCallChain? = null
    private val engine =
        QuickJs.create(Dispatchers.Default).apply {
            memoryLimit = 64L * 1024 * 1024
            maxStackSize = 256L * 1024
            evaluationTimeoutMillis = timeoutMillis
        }

    init {
        engine.asyncFunction("hostRequest") { args ->
            val request = args.singleOrNull() as? String ?: error("hostRequest expects one JSON string")
            require(request.length <= MAX_MESSAGE_CHARS) { "Host request exceeds size limit" }
            val chain = checkNotNull(activeChain) { "RPC has no active evaluation" }
            owned {
                withContext(chain) {
                    hostRequest(request).also {
                        require(it.length <= MAX_MESSAGE_CHARS) { "Host response exceeds size limit" }
                    }
                }
            }.await()
        }
    }

    suspend fun evaluate(code: String): String {
        val ancestors = currentCoroutineContext()[RuntimeCallChain]?.instances.orEmpty()
        require(this !in ancestors) { "Synchronous plugin RPC cycle is unsupported; schedule independent work instead" }
        return calls.withLock {
            require(code.length <= MAX_MESSAGE_CHARS) { "Plugin source exceeds size limit" }
            activeChain = RuntimeCallChain(ancestors + this)
            try {
                owned {
                    withTimeout(timeoutMillis) {
                        engine.evaluate<String>(code).also {
                            require(it.length <= MAX_MESSAGE_CHARS) { "Plugin result exceeds size limit" }
                        }
                    }
                }.await()
            } catch (error: Throwable) {
                // quickjs-kt 1.0.15 does not join host jobs on eval cancellation/close.
                // Cancel and drain our ownership tree before releasing the native engine.
                close()
                currentCoroutineContext().ensureActive()
                // The native watchdog has a distinct SDK type even when its text looks like a JS error.
                throw if (error is QuickJsInterruptedException) {
                    ExecutionResultUnavailable("Plugin evaluation was interrupted before confirming a result", error)
                } else {
                    error
                }
            } finally {
                activeChain = null
            }
        }
    }

    suspend fun close() =
        withContext(NonCancellable) {
            check(this@PluginRuntime !in currentCoroutineContext()[RuntimeCallChain]?.instances.orEmpty()) {
                "Close must be requested by the external runtime owner, not its RPC callback"
            }
            closing.withLock {
                synchronized(lifecycle) {
                    closed = true
                    owner.cancel()
                }
                owner.join()
                engine.close()
            }
        }

    private fun <T> owned(block: suspend () -> T): Deferred<T> =
        synchronized(lifecycle) {
            check(!closed) { "Plugin instance is closed; rebuild before invoking it" }
            scope.async { block() }
        }

    private class RuntimeCallChain(val instances: List<PluginRuntime>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RuntimeCallChain>
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 1024 * 1024
    }
}
