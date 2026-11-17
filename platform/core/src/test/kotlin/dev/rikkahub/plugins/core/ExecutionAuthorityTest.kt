package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.PackageLease
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExecutionAuthorityTest {
    private val a = PackageLease("a", "a".repeat(64), 1, "installation")
    private val b = PackageLease("b", "b".repeat(64), 1, "installation")

    @Test fun revocationInvalidatesTheWholeOwningBindingButIndependentBRemainsAuthorized() {
        val authority = ExecutionAuthority { true }
        val scope = authority.open(ExecutionBinding(listOf(a, b)), Job())
        assertEquals(listOf(scope), authority.beginRevocation("a"))
        assertTrue(scope.revoked)
        assertFalse(authority.allowed(listOf(a, b)))
        assertTrue(authority.allowed(listOf(b)))
        assertThrows(ExecutionAuthorizationRevoked::class.java) { scope.dispatch {} }
    }

    @Test fun endingAFailedRevocationCannotReviveAnAlreadyInvalidatedScope() {
        val authority = ExecutionAuthority { true }
        val scope = authority.open(ExecutionBinding(listOf(a, b)), Job())
        authority.beginRevocation("a")
        authority.endRevocation("a")
        assertTrue(authority.allowed(listOf(a, b)))
        assertThrows(ExecutionAuthorizationRevoked::class.java) { scope.check() }
        authority.open(ExecutionBinding(listOf(a, b)), Job()).check()
    }

    @Test fun everyCheckRevalidatesAuthorizationAndLiveNativeAdmission() {
        var authorized = true
        var sourceMatches = true
        val authority = ExecutionAuthority { authorized }
        val scope = authority.open(ExecutionBinding(listOf(a), validate = { check(sourceMatches) }), Job())
        sourceMatches = false
        assertThrows(IllegalStateException::class.java) { scope.dispatch {} }
        sourceMatches = true
        authorized = false
        assertThrows(ExecutionAuthorizationRevoked::class.java) { scope.check() }
    }

    @Test fun capturedDependenciesCannotBeChangedByMutatingTheCallersList() {
        val authority = ExecutionAuthority { true }
        val dependencies = mutableListOf(a, b)
        val scope = authority.open(ExecutionBinding(dependencies), Job())
        dependencies.remove(a)
        assertEquals(listOf(scope), authority.beginRevocation("a"))
        assertThrows(ExecutionAuthorizationRevoked::class.java) { scope.dispatch {} }
    }

    @Test fun releasedScopesAreRemovedAndCannotDispatchAgain() {
        val authority = ExecutionAuthority { true }
        val scope = authority.open(ExecutionBinding(listOf(a)), Job())
        scope.close()
        assertTrue(authority.beginRevocation("a").isEmpty())
        assertThrows(ExecutionAuthorizationRevoked::class.java) { scope.dispatch {} }
    }

    @Test fun shutdownInvalidatesEveryDependencyAndBlocksNewScopes() {
        val authority = ExecutionAuthority { true }
        val first = authority.open(ExecutionBinding(listOf(a)), Job())
        val second = authority.open(ExecutionBinding(listOf(b)), Job())
        assertEquals(setOf(first, second), authority.close().toSet())
        assertFalse(authority.allowed(listOf(a)))
        assertThrows(ExecutionAuthorizationRevoked::class.java) { first.dispatch {} }
        assertThrows(ExecutionAuthorizationRevoked::class.java) { authority.open(ExecutionBinding(listOf(b)), Job()) }
    }

    @Test fun finalDispatchAndRevocationHaveAnExplicitOrder() {
        val authority = ExecutionAuthority { true }
        val scope = authority.open(ExecutionBinding(listOf(a, b)), Job())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revocationStarted = CountDownLatch(1)
        val order = CopyOnWriteArrayList<String>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val dispatch = executor.submit {
                scope.dispatch {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    order.add("dispatch")
                }
            }
            check(entered.await(5, TimeUnit.SECONDS))
            val revocation = executor.submit {
                revocationStarted.countDown()
                authority.beginRevocation("a")
                order.add("revocation")
            }
            check(revocationStarted.await(5, TimeUnit.SECONDS))
            assertFalse("Revocation cannot acknowledge ahead of an admitted synchronous send", revocation.isDone)
            release.countDown()
            dispatch.get(5, TimeUnit.SECONDS)
            revocation.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("dispatch", "revocation"), order)
            assertThrows(ExecutionAuthorizationRevoked::class.java) { scope.dispatch { order.add("late") } }
            assertEquals(2, order.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
