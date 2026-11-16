package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.ArtifactDraft
import dev.rikkahub.plugins.protocol.BranchRef
import dev.rikkahub.plugins.protocol.ContextItem
import dev.rikkahub.plugins.protocol.Gate
import dev.rikkahub.plugins.protocol.GateKind
import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.ProjectionPlan
import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.RunState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationRunnerTest {
    private val lease = PackageLease("test", "package", 1)
    private val call =
        OperationCall("request", "test/report", BranchRef("conversation", "branch"), JsonPrimitive("input"), lease)

    @Test
    fun operationsCanReduceWithoutModelWorkOrPublishReportsWithoutProjection() = runTest {
        val source = listOf(ContextItem("a", "old"), ContextItem("b", "new"))
        val runner = OperationRunner(listOf(
            OperationDefinition("test/reduce", lease, setOf("context.write")) { _ ->
                OperationResult(projection = ProjectionPlan(omit = setOf("a")))
            },
            OperationDefinition("test/report", lease, emptySet()) { invocation ->
                OperationResult(artifacts = listOf(ArtifactDraft("review", invocation.call.input)))
            }
        ))
        val reduced = runner.execute(call.copy(type = "test/reduce"), setOf("context.write"), "attempt") { }
        assertEquals(listOf(source[1]), ContextProjection.apply(source, reduced.projection!!))
        assertEquals(2, source.size)
        val report = runner.execute(call, emptySet(), "attempt") { }
        assertNull(call.run)
        assertNull(report.projection)
        assertEquals(JsonPrimitive("input"), report.artifacts.single().content)
    }

    @Test
    fun revokeWhileOperationRunsRejectsItsLateResult() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var authorized = true
        val runner = OperationRunner(listOf(OperationDefinition("test/report", lease, emptySet()) {
            entered.complete(Unit)
            release.await()
            OperationResult()
        }))
        val work = async {
            runCatching { runner.execute(call, emptySet(), "attempt") { check(authorized) } }
        }
        entered.await()
        authorized = false
        release.complete(Unit)
        assertTrue(work.await().isFailure)
    }

    @Test
    fun cancellationReachesOperationAndPermissionDenialDoesNotInvokeIt() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val runner = OperationRunner(listOf(OperationDefinition("test/report", lease, setOf("report.write")) {
            entered.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancelled.complete(Unit)
            }
            OperationResult()
        }))
        assertTrue(runCatching { runner.execute(call, emptySet(), "attempt") { } }.isFailure)
        assertFalse(entered.isCompleted)
        val work = async { runner.execute(call, setOf("report.write"), "attempt") { } }
        entered.await()
        work.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun twoPackagesShareOneRunAndDispatchOnlyToTheirRecordedHandlerLease() = runTest {
        val other = PackageLease("second", "second-hash", 2)
        val run = RunRef(call.target.conversationId, call.target.branchId, "shared")
        var state = RunControl.addGate(RunState(run, 3, 4), Gate("pause", GateKind.USER_PAUSE))
        val observed = mutableListOf<OperationContext>()
        val runner = OperationRunner(listOf(lease, other).map { version ->
            OperationDefinition("test/report", version, emptySet()) { invocation ->
                observed += invocation
                OperationResult()
            }
        })
        for (version in listOf(lease, other)) {
            val invocation = call.copy(handler = version, caller = lease, run = run)
            assertTrue(runCatching {
                runner.execute(invocation, emptySet(), "attempt") { check(state.gates.isEmpty()) }
            }.isFailure)
        }
        assertTrue(observed.isEmpty())
        state = RunControl.resolveGate(state, "pause")
        for (version in listOf(lease, other)) {
            runner.execute(call.copy(handler = version, caller = lease, run = run), emptySet(), "attempt") {
                check(state.gates.isEmpty())
            }
        }
        assertEquals(listOf(lease, other), observed.map { it.call.handler })
        assertEquals(setOf(run), observed.map { it.call.run }.toSet())
        assertEquals(setOf("attempt"), observed.map { it.attemptId }.toSet())
        assertTrue(runCatching {
            runner.execute(call.copy(handler = lease.copy(packageHash = "missing")), emptySet(), "attempt") {}
        }.isFailure)
        assertEquals(2, observed.size)
    }

    @Test
    fun projectionCannotSplitToolGroupsOrDeleteProtectedInput() {
        val source = listOf(
            ContextItem("system", "instructions", protected = true),
            ContextItem("call", "call", toolGroup = "pair"),
            ContextItem("result", "result", toolGroup = "pair")
        )
        for (omit in listOf(setOf("system"), setOf("call"), setOf("unknown"))) {
            assertThrows(IllegalArgumentException::class.java) { ContextProjection.apply(source, ProjectionPlan(omit)) }
        }
        assertEquals(listOf(source[0]), ContextProjection.apply(source, ProjectionPlan(setOf("call", "result"))))
    }
}
