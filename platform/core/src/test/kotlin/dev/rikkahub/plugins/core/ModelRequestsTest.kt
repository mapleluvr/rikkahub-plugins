package dev.rikkahub.plugins.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRequestsTest {
    @Test fun revocationBeforeReserveDoesNotCreateACharge() = runBlocking {
        val journal = Journal()
        val model = JournaledModelRequests(journal) { error("revoked") }
        assertTrue(runCatching { model.dispatch { error("must not enter Provider") } }.isFailure)
        assertTrue(journal.reserved.isEmpty())
        assertEquals(0, journal.charges)
    }

    @Test fun revocationAfterReservationRefundsWithoutEnteringProvider() = runBlocking {
        var authorized = true
        val journal = Journal(afterReserve = { authorized = false })
        val model = JournaledModelRequests(journal) { check(authorized) }
        var requests = 0
        assertTrue(runCatching { model.dispatch { requests++ } }.isFailure)
        assertEquals(0, requests)
        assertEquals(0, journal.charges)
        assertTrue(journal.started.isEmpty())
        assertEquals(journal.reserved, journal.refused)
    }

    @Test fun cancelledIntentWhosePermissionNeverReturnedIsRefunded() = runBlocking {
        val journal = Journal(afterStart = { currentCoroutineContext().cancel() })
        val model = JournaledModelRequests(journal) { }
        var requests = 0
        val work = launch { model.dispatch { requests++ } }
        work.join()
        assertTrue(work.isCancelled)
        assertEquals(0, requests)
        assertEquals(0, journal.charges)
        assertEquals(journal.started, journal.refused)
    }

    @Test fun refusalOfNextRetryPreservesTheFirstDispatchedFailure() = runBlocking {
        var authorized = true
        val journal = Journal()
        val model = JournaledModelRequests(journal) { check(authorized) }
        var requests = 0
        assertTrue(runCatching {
            model.dispatch {
                requests++
                error("network response unknown")
            }
        }.isFailure)
        journal.afterReserve = { authorized = false }
        assertTrue(runCatching { model.dispatch { requests++ } }.isFailure)
        assertEquals(1, requests)
        assertEquals(1, journal.charges)
        assertEquals(2, journal.reserved.distinct().size)
        assertEquals(listOf(journal.reserved.last()), journal.refused)
        assertTrue(journal.confirmed.isEmpty())
    }

    @Test fun cancellationInsideProviderDoesNotRefund() = runBlocking {
        val journal = Journal()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val model = JournaledModelRequests(journal) { }
        val work = launch {
            model.dispatch {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        work.cancelAndJoin()
        assertEquals(1, journal.charges)
        assertTrue(journal.refused.isEmpty())
        assertTrue(journal.confirmed.isEmpty())
    }

    @Test fun sharedStorageFailureDuringRefusalPreservesTheOriginalError() = runBlocking {
        val storage = java.io.IOException("reservation storage unavailable")
        val journal = object : ModelRequestJournal {
            override suspend fun reserve(id: String): Unit = throw storage
            override suspend fun start(id: String): Unit = error("No reservation was committed")
            override suspend fun refuse(id: String): Unit = throw storage
            override suspend fun confirm(id: String): Unit = error("Provider was not entered")
        }
        val model = JournaledModelRequests(journal) { }
        var requests = 0
        repeat(2) {
            assertSame(storage, runCatching { model.dispatch { requests++ } }.exceptionOrNull())
        }
        assertEquals(0, requests)
    }

    @Test fun confirmedResponseAndFreshSequentialTicketAreDistinct() = runBlocking {
        val journal = Journal()
        val model = JournaledModelRequests(journal) { }
        assertEquals("first", model.dispatch { "first" })
        assertEquals("second", model.dispatch { "second" })
        assertEquals(2, journal.reserved.distinct().size)
        assertEquals(journal.reserved, journal.confirmed)
        assertEquals(2, journal.charges)
        assertTrue(journal.refused.isEmpty())
    }

    @Test fun concurrentRequestsCannotOverwriteTheInFlightTicket() = runBlocking {
        val journal = Journal()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val model = JournaledModelRequests(journal) { }
        val first = launch {
            model.dispatch {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            assertTrue(runCatching { model.dispatch { error("second request must not start") } }.isFailure)
            assertEquals(1, journal.reserved.size)
            assertTrue(journal.refused.isEmpty())
        } finally {
            release.complete(Unit)
            first.join()
        }
        assertFalse(first.isCancelled)
        assertEquals(journal.reserved, journal.confirmed)
    }

    private class Journal(var afterReserve: suspend () -> Unit = {}, val afterStart: suspend () -> Unit = {},) :
        ModelRequestJournal {
        val reserved = mutableListOf<String>()
        val started = mutableListOf<String>()
        val refused = mutableListOf<String>()
        val confirmed = mutableListOf<String>()
        val charges: Int get() = reserved.size - refused.size
        override suspend fun reserve(id: String) {
            reserved += id
            afterReserve()
        }
        override suspend fun start(id: String) {
            started += id
            afterStart()
        }
        override suspend fun refuse(id: String) {
            if (id in reserved) refused += id
        }
        override suspend fun confirm(id: String) {
            confirmed += id
        }
    }
}
