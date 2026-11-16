package dev.rikkahub.plugins.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same consumer contract on the desktop JNI library and the Android AAR. */
class QuickJsContractTest {
    private fun runtime() =
        QuickJs.create(Dispatchers.Default).apply {
            memoryLimit = 32L * 1024 * 1024
            maxStackSize = 256L * 1024
            evaluationTimeoutMillis = 2_000
        }

    @Test(timeout = 15_000)
    fun callbacksSurviveRegistrationEvaluation() =
        runBlocking {
            val js = runtime()
            try {
                js.evaluate<Any?>("globalThis.plugin = (() => { let n = 0; return { next: () => ++n }; })(); undefined")
                assertEquals(1L, js.evaluate<Long>("plugin.next()"))
                assertEquals(2L, js.evaluate<Long>("plugin.next()"))
            } finally {
                js.close()
            }
        }

    @Test(timeout = 15_000)
    fun concurrentRpcRepliesKeepTheirOwnIdentity() =
        runBlocking {
            val js = runtime()
            val firstArrived = CompletableDeferred<Unit>()
            val secondArrived = CompletableDeferred<Unit>()
            try {
                js.asyncFunction("hostRequest") { args ->
                    val id = args.single() as String
                    if (id == "first") {
                        firstArrived.complete(Unit)
                        secondArrived.await()
                    } else {
                        secondArrived.complete(Unit)
                        firstArrived.await()
                    }
                    "reply:$id"
                }
                assertEquals(
                    "reply:first,reply:second",
                    withTimeout(3_000) {
                        js.evaluate<String>(
                            "await Promise.all([hostRequest('first'), hostRequest('second')]).then(x => x.join(','))"
                        )
                    },
                )
                val results =
                    (1..4)
                        .map { n ->
                            async(Dispatchers.Default) { js.evaluate<Long>("$n * 10") }
                        }.awaitAll()
                assertEquals(listOf(10L, 20L, 30L, 40L), results)
            } finally {
                js.close()
            }
        }

    @Test(timeout = 15_000)
    fun cancellationInterruptsNativeLoopAndAllowsAnotherEvaluation() =
        runBlocking {
            val js = runtime()
            val entered = CompletableDeferred<Unit>()
            try {
                js.evaluationTimeoutMillis = 10_000
                js.function("entered") {
                    entered.complete(Unit)
                    null
                }
                val call = async(Dispatchers.Default) { js.evaluate<Any?>("entered(); while (true) {}") }
                withTimeout(3_000) { entered.await() }
                withTimeout(3_000) { call.cancelAndJoin() }
                assertTrue(call.isCancelled)
                assertEquals(42L, js.evaluate<Long>("40 + 2"))
            } finally {
                js.close()
            }
        }

    @Test(timeout = 15_000)
    fun rawCloseRequestsAsynchronousHostCleanup() =
        runBlocking {
            val js = runtime()
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            js.asyncFunction("hostRequest") {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    released.complete(Unit)
                }
            }
            try {
                val call = async(Dispatchers.Default) { js.evaluate<Any?>("await hostRequest()") }
                withTimeout(3_000) { entered.await() }
                withTimeout(3_000) { call.cancelAndJoin() }
                js.close()
                withTimeout(3_000) { released.await() }
                assertTrue(js.isClosed)
                assertTrue(runCatching { js.evaluate<Any?>("1") }.isFailure)
            } finally {
                if (!js.isClosed) js.close()
            }
        }

    @Test(timeout = 15_000)
    fun executionDeadlineBoundsBusyJavaScript() =
        runBlocking {
            val js = runtime()
            try {
                js.evaluationTimeoutMillis = 50
                val error = runCatching { js.evaluate<Any?>("while (true) {}") }.exceptionOrNull()
                assertTrue(error is QuickJsInterruptedException)
                assertEquals(2L, js.evaluate<Long>("1 + 1"))
            } finally {
                js.close()
            }
        }

    @Test(timeout = 15_000)
    fun newInstanceRequiresExplicitStateRestoration() =
        runBlocking {
            val first = runtime()
            val state =
                try {
                    first.evaluate<Any?>("globalThis.state = {count: 7}; undefined")
                    first.evaluate<String>("JSON.stringify(state)")
                } finally {
                    first.close()
                }
            val second = runtime()
            try {
                assertEquals("undefined", second.evaluate<String>("typeof state"))
                second.evaluate<Any?>("globalThis.state = $state; undefined")
                assertEquals(7L, second.evaluate<Long>("state.count"))
            } finally {
                second.close()
            }
        }
}
