package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.ArtifactDraft
import dev.rikkahub.plugins.protocol.ContextItem
import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.ProjectionPlan
import dev.rikkahub.plugins.protocol.RunRef
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
    private val ref = RunRef("conversation", "branch", "run", 1, "package")
    private val call = OperationCall("request", "test/report", ref, JsonPrimitive("input"))

    @Test
    fun operationsCanReduceWithoutModelWorkOrPublishReportsWithoutProjection() = runTest {
        val source = listOf(ContextItem("a", "old"), ContextItem("b", "new"))
        val runner = OperationRunner(listOf(
            OperationDefinition("test/reduce", setOf("context.write")) { _ ->
                OperationResult(projection = ProjectionPlan(omit = setOf("a")))
            },
            OperationDefinition("test/report", emptySet()) { input ->
                OperationResult(artifacts = listOf(ArtifactDraft("review", input)))
            }
        ))
        val reduced = runner.execute(call.copy(type = "test/reduce"), setOf("context.write")) { }
        assertEquals(listOf(source[1]), ContextProjection.apply(source, reduced.projection!!))
        assertEquals(2, source.size)
        val report = runner.execute(call, emptySet()) { }
        assertNull(report.projection)
        assertEquals(JsonPrimitive("input"), report.artifacts.single().content)
    }

    @Test
    fun revokeWhileOperationRunsRejectsItsLateResult() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var authorized = true
        val runner = OperationRunner(listOf(OperationDefinition("test/report", emptySet()) {
            entered.complete(Unit)
            release.await()
            OperationResult()
        }))
        val work = async {
            runCatching { runner.execute(call, emptySet()) { check(authorized) } }
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
        val runner = OperationRunner(listOf(OperationDefinition("test/report", setOf("report.write")) {
            entered.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancelled.complete(Unit)
            }
            OperationResult()
        }))
        assertTrue(runCatching { runner.execute(call, emptySet()) { } }.isFailure)
        assertFalse(entered.isCompleted)
        val work = async { runner.execute(call, setOf("report.write")) { } }
        entered.await()
        work.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
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
