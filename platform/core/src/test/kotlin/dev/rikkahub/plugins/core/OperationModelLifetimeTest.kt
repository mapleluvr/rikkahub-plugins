package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.BranchRef
import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.PackageLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A detached summary builder may outlive CPU cancellation, but not its owned model journal. */
@OptIn(ExperimentalCoroutinesApi::class)
class OperationModelLifetimeTest {
    private val lease = PackageLease("test", "package", 1)
    private val call = OperationCall(
        "request",
        "test/report",
        BranchRef("session", "branch"),
        JsonPrimitive("input"),
        lease
    )

    @Test
    fun modelPermissionsCannotEscapeTheOperationThatCreatedThem() = runTest {
        var escaped: ModelRequests? = null
        var reservations = 0
        var providers = 0
        val journal = object : ModelRequestJournal {
            override suspend fun reserve(id: String) {
                reservations++
            }
            override suspend fun start(id: String) { }
            override suspend fun refuse(id: String) { }
            override suspend fun confirm(id: String) { }
        }
        val runner = OperationRunner(listOf(OperationDefinition("test/report", lease, emptySet()) {
            escaped = it.modelRequests
            OperationResult()
        }))
        runner.execute(call, emptySet(), "attempt", modelJournal = journal) { }
        assertTrue(
            "A returned handler must not retain a live model permission",
            runCatching { escaped!!.dispatch { providers++ } }.isFailure
        )
        assertEquals(0, reservations)
        assertEquals(0, providers)
    }

    @Test
    fun cancellationDrainsTheDetachedReservationBeforeOperationSettlement() = runTest {
        verifyDetachedReservation(timeout = false)
    }

    @Test
    fun timeoutDrainsTheDetachedReservationBeforeOperationSettlement() = runTest {
        verifyDetachedReservation(timeout = true)
    }

    @Test
    fun returnedHandlerCannotSettleWithAnUnfinishedDetachedModel() = runTest {
        verifyDetachedReservation(timeout = false, returned = true)
    }

    private suspend fun TestScope.verifyDetachedReservation(timeout: Boolean, returned: Boolean = false) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val refunded = CompletableDeferred<Unit>()
        val detachedOwner = Job()
        val detached = CoroutineScope(coroutineContext + detachedOwner)
        var charges = 0
        var providers = 0
        val journal = object : ModelRequestJournal {
            override suspend fun reserve(id: String) {
                charges++
                entered.complete(Unit)
                // A durable write can finish despite cancellation; leave this precise point suspended.
                withContext(NonCancellable) { release.await() }
            }
            override suspend fun start(id: String) {
                error("Cancelled permission cannot reach intent")
            }
            override suspend fun refuse(id: String) {
                charges--
                refunded.complete(Unit)
            }
            override suspend fun confirm(id: String) {
                error("Provider was never entered")
            }
        }
        val runner = OperationRunner(listOf(OperationDefinition("test/report", lease, emptySet()) { invocation ->
            // Same cancellation ownership as ChatRequestFactory's detached CPU summary builder.
            val factory = detached.async {
                invocation.modelRequests.dispatch {
                    providers++
                    "summary"
                }
            }
            try {
                if (returned) entered.await() else factory.await()
                OperationResult()
            } finally {
                factory.cancel()
            }
        }))
        val operation = async {
            runCatching {
                runner.execute(
                    call,
                    emptySet(),
                    "attempt",
                    timeoutMillis = 100,
                    modelJournal = journal
                ) { }
            }
        }
        try {
            runCurrent()
            assertTrue(entered.isCompleted)
            assertEquals(1, charges)
            if (timeout) {
                advanceTimeBy(101)
            } else if (!returned) {
                operation.cancel()
            }
            runCurrent()
            assertFalse(
                "Do not classify settlement while a known-undispatched refund is still running",
                operation.isCompleted
            )
            assertFalse(refunded.isCompleted)
            release.complete(Unit)
            runCurrent()
            assertTrue(refunded.isCompleted)
            assertTrue(operation.isCompleted)
            if (returned) assertTrue(operation.await().isFailure)
            assertEquals(0, charges)
            assertEquals(0, providers)
        } finally {
            release.complete(Unit)
            operation.cancelAndJoin()
            detachedOwner.cancel()
            runCurrent()
        }
    }
}
