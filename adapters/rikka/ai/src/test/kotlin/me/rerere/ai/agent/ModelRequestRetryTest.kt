package me.rerere.ai.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration

class ModelRequestRetryTest {
    private val policy = RetryPolicy(2, Duration.ZERO) { true }

    @Test fun controlStorageFailureIsNotANetworkRetry() = runBlocking {
        var controls = 0
        var providers = 0
        val storage = IOException("reservation commit failed")
        val failure = runCatching {
            withModelRequestRetry(policy, dispatch = {
                controls++
                throw storage
            }) { providers++ }
        }.exceptionOrNull()
        assertSame(storage, failure)
        assertEquals(1, controls)
        assertEquals(0, providers)
    }

    @Test fun confirmationStorageFailureDoesNotRepeatTheProvider() = runBlocking {
        var controls = 0
        var providers = 0
        val storage = IOException("response evidence commit failed")
        val failure = runCatching {
            withModelRequestRetry(policy, dispatch = { request ->
                controls++
                request()
                throw storage
            }) { providers++ }
        }.exceptionOrNull()
        assertSame(storage, failure)
        assertEquals(1, controls)
        assertEquals(1, providers)
    }

    @Test fun actualProviderFailureCanUseAnotherDispatchTicket() = runBlocking {
        var controls = 0
        var providers = 0
        var retries = 0
        val answer = withModelRequestRetry(policy, dispatch = {
            controls++
            it()
        }, onRetry = { retries++ }) {
            if (++providers == 1) throw IOException("network response failed")
            "answer"
        }
        assertEquals("answer", answer)
        assertEquals(2, providers)
        assertEquals(2, controls)
        assertEquals(1, retries)
    }

    @Test fun retryEveryErrorStillCannotRetryHostAdmissionDenial() = runBlocking {
        var controls = 0
        var providers = 0
        val denied = IllegalStateException("owning run has a pause gate")
        val failure = runCatching {
            withModelRequestRetry(policy, dispatch = {
                controls++
                throw denied
            }) { providers++ }
        }.exceptionOrNull()
        assertSame(denied, failure)
        assertEquals(1, controls)
        assertEquals(0, providers)
    }
}
