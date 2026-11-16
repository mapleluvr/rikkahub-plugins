package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.AttemptStatus
import dev.rikkahub.plugins.protocol.BranchRef
import dev.rikkahub.plugins.protocol.ExecutionLedger
import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.SourceStamp
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class OperationLedgerTest {
    private val lease = PackageLease("test", "v1", 1)
    private val call = OperationCall("command", "test/review", BranchRef("c", "b"), JsonNull, lease)
    private val sources = listOf(SourceStamp("message", "digest"))

    @Test
    fun commandRetriesKeepOneReceiptAndRejectIdentityReuse() {
        val prepared = OperationLedger.prepare(ExecutionLedger(), call, sources)
        assertSame(prepared, OperationLedger.prepare(prepared, call, sources))
        assertThrows(IllegalArgumentException::class.java) {
            OperationLedger.prepare(prepared, call.copy(type = "other"), sources)
        }
        val running = OperationLedger.start(prepared, call.id, "attempt")
        assertThrows(IllegalStateException::class.java) { OperationLedger.start(running, call.id, "duplicate") }
        val settled = OperationLedger.settle(running, call.id, "attempt", call, sources, OperationResult())
        assertSame(settled, OperationLedger.settle(settled, call.id, "attempt", call, sources, OperationResult()))
    }

    @Test
    fun uncertainExternalEffectsNeverAutomaticallyRunAgainAfterRecoveryOrCancellation() {
        val prepared = OperationLedger.prepare(ExecutionLedger(), call, sources)
        val running = OperationLedger.start(prepared, call.id, "attempt")
        for (uncertain in listOf(OperationLedger.recover(running), OperationLedger.cancel(running, call.id))) {
            assertEquals(AttemptStatus.UNKNOWN, uncertain.operations.getValue(call.id).status)
            assertThrows(IllegalStateException::class.java) { OperationLedger.start(uncertain, call.id, "retry") }
            assertThrows(IllegalStateException::class.java) {
                OperationLedger.settle(uncertain, call.id, "attempt", call, sources, OperationResult())
            }
        }
        assertEquals(
            AttemptStatus.CANCELLED,
            OperationLedger.cancel(prepared, call.id).operations.getValue(call.id).status
        )
    }

    @Test
    fun sourceEditsAndLeaseChangesRejectResultsButUnrelatedTailGrowthDoesNot() {
        val running = OperationLedger.start(
            OperationLedger.prepare(ExecutionLedger(), call, sources),
            call.id,
            "attempt"
        )
        assertEquals(AttemptStatus.SETTLED, OperationLedger.settle(
            running,
            call.id,
            "attempt",
            call,
            sources + SourceStamp("tail", "new"),
            OperationResult()
        ).operations.getValue(call.id).status)
        assertThrows(IllegalArgumentException::class.java) {
            OperationLedger.settle(
                running,
                call.id,
                "attempt",
                call,
                listOf(SourceStamp("message", "edited")),
                OperationResult()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OperationLedger.settle(
                running,
                call.id,
                "attempt",
                call.copy(handler = lease.copy(packageHash = "v2")),
                sources,
                OperationResult()
            )
        }
    }

    @Test
    fun unknownLedgerVersionsCannotExecute() {
        assertThrows(IllegalArgumentException::class.java) {
            OperationLedger.prepare(ExecutionLedger(version = 999), call, sources)
        }
    }
}
