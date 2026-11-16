package dev.rikkahub.plugins.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRuntimeTest {
    @Test(timeout = 15_000)
    fun closingWaitsForHostCleanup() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            val runtime =
                PluginRuntime {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            allowCleanup.await()
                        }
                    }
                }
            val call = async(Dispatchers.Default) { runtime.evaluate("await hostRequest('test')") }
            withTimeout(3_000) { entered.await() }
            val close = async(Dispatchers.Default) { runtime.close() }
            withTimeout(3_000) { cleaning.await() }
            assertFalse(close.isCompleted)
            allowCleanup.complete(Unit)
            withTimeout(3_000) {
                close.await()
                call.join()
            }
            assertTrue(call.isCancelled)
            assertTrue(runCatching { runtime.evaluate("'late'") }.isFailure)
            runtime.close()
        }

    @Test(timeout = 15_000)
    fun callerCancellationRevokesInstanceAndDrainsHostTasks() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val runtime =
                PluginRuntime {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cleaned.complete(Unit)
                    }
                }
            val call = async(Dispatchers.Default) { runtime.evaluate("await hostRequest('test')") }
            withTimeout(3_000) { entered.await() }
            withTimeout(3_000) { call.cancelAndJoin() }
            assertTrue(cleaned.isCompleted)
            assertTrue(runCatching { runtime.evaluate("'stale'") }.isFailure)
            runtime.close()
        }

    @Test(timeout = 15_000)
    fun keepsRegisteredCallbacksAcrossSuccessfulCalls() =
        runBlocking {
            val runtime = PluginRuntime { request -> "reply:$request" }
            try {
                runtime.evaluate("globalThis.callback = x => hostRequest(x); 'registered'")
                assertEquals("reply:one", runtime.evaluate("await callback('one')"))
                assertEquals("reply:two", runtime.evaluate("await callback('two')"))
            } finally {
                runtime.close()
            }
        }
}
