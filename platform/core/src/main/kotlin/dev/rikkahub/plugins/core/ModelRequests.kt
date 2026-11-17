package dev.rikkahub.plugins.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Persistent callbacks for one model job; tickets never replace the main run's request record. */
interface ModelRequestJournal {
    suspend fun reserve(id: String)
    suspend fun start(id: String)
    suspend fun refuse(id: String)
    suspend fun confirm(id: String)
}

/** Wrap the actual Provider entry, after request construction, not an entire feature handler. */
interface ModelRequests : suspend () -> Unit {
    suspend fun <T> dispatch(request: suspend () -> T): T

    companion object {
        /** For hosts without a persistent journal; does not claim ticket-based recovery. */
        fun fromPermission(before: suspend () -> Unit): ModelRequests = object : ModelRequests {
            override suspend fun invoke() = before()
            override suspend fun <T> dispatch(request: suspend () -> T): T {
                before()
                return request()
            }
        }
    }
}

/**
 * One in-flight attempt per job; retries get fresh identities. A refusal before the consumer is entered
 * refunds only this ticket, even if its intent committed. Failure after entry remains uncertain.
 */
class JournaledModelRequests(private val journal: ModelRequestJournal, private val validate: suspend () -> Unit,) :
    ModelRequests {
    private val inFlight = Mutex()
    private val lifecycle = Any()
    private var closed = false
    private var owner: Job? = null

    override suspend fun <T> dispatch(request: suspend () -> T): T = permission(confirmResult = true, request)

    /** Compatibility for permission-only consumers; once returned, dispatch cannot be disproved here. */
    override suspend fun invoke() = permission(confirmResult = false) { }

    private suspend fun <T> permission(confirmResult: Boolean, request: suspend () -> T): T {
        val caller = currentCoroutineContext().job
        val id = Uuid.random().toString()
        synchronized(lifecycle) {
            check(!closed) { "Model job has ended" }
            check(inFlight.tryLock()) { "Concurrent attempts within one model job are not supported" }
            owner = caller
        }
        var consumerEntered = false
        try {
            currentCoroutineContext().ensureActive()
            validate()
            journal.reserve(id)
            currentCoroutineContext().ensureActive()
            validate()
            journal.start(id)
            currentCoroutineContext().ensureActive()
            validate()
            consumerEntered = true
            val result = request()
            if (confirmResult) withContext(NonCancellable) { journal.confirm(id) }
            return result
        } catch (error: Throwable) {
            if (!consumerEntered) {
                withContext(NonCancellable) {
                    val refusalFailure = runCatching { journal.refuse(id) }.exceptionOrNull()
                    if (refusalFailure != null && refusalFailure !== error) error.addSuppressed(refusalFailure)
                }
            }
            throw error
        } finally {
            synchronized(lifecycle) {
                owner = null
                inFlight.unlock()
            }
        }
    }

    /** Called only by the operation owner, outside any dispatch, under NonCancellable. */
    internal suspend fun closeAndDrain(): Boolean {
        val pending = synchronized(lifecycle) {
            closed = true
            owner
        }
        pending?.cancel()
        inFlight.lock()
        inFlight.unlock()
        return pending != null
    }
}
