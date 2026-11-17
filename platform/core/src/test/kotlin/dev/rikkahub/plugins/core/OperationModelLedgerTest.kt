package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.AttemptStatus
import dev.rikkahub.plugins.protocol.BranchRef
import dev.rikkahub.plugins.protocol.ExecutionLedger
import dev.rikkahub.plugins.protocol.Gate
import dev.rikkahub.plugins.protocol.GateKind
import dev.rikkahub.plugins.protocol.ModelAttemptPhase
import dev.rikkahub.plugins.protocol.ModelAttemptRecord
import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationRecord
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.RunCheckpoint
import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.RunState
import dev.rikkahub.plugins.protocol.RunStatus
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationModelLedgerTest {
    private val ref = RunRef("session", "branch", "run")
    private val main = ModelAttemptRecord("main", 0, ModelAttemptPhase.DISPATCH_INTENT, chargedStep = true)
    private val checkpoint = RunCheckpoint(
        RunState(ref, 6, 10, stepsUsed = 1, attemptsUsed = 1),
        "BeforeModel",
        0,
        modelAttempt = main,
    )
    private val call = OperationCall(
        "job",
        "review/model",
        BranchRef("session", "branch"),
        JsonNull,
        PackageLease("plugin", "hash", 1),
        run = ref
    )
    private val initial = ExecutionLedger(runs = mapOf(ref.runId to checkpoint), operations = mapOf(
        call.id to OperationRecord(call, emptyList(), AttemptStatus.RUNNING, "operation-attempt"),
    ))

    @Test fun refusalPreservesTheMainTicketAndIndependentOperationAndGate() {
        val reserved = reserve(initial, "ticket")
        val run = reserved.runs.getValue(ref.runId)
        val gate = Gate("pause", GateKind.USER_PAUSE)
        val concurrent = reserved.copy(runs = reserved.runs + (ref.runId to run.copy(state =
            RunControl.addGate(run.state.copy(
                attemptsUsed = run.state.attemptsUsed + 1,
                operationStepsUsed = run.state.operationStepsUsed + 1
            ), gate))))
        val refused = OperationModelLedger.refuse(concurrent, call.id, "ticket")
        val saved = refused.runs.getValue(ref.runId)
        assertEquals(main, saved.modelAttempt)
        assertEquals(1, saved.state.stepsUsed)
        assertEquals(2, saved.state.attemptsUsed)
        assertEquals(1, saved.state.operationStepsUsed)
        assertEquals(mapOf(gate.id to gate), saved.state.gates)
        assertEquals(concurrent.runs.getValue(ref.runId).state.frameRevision, saved.state.frameRevision)
        assertEquals(0, refused.operations.getValue(call.id).modelAttempts)
        assertSame(refused, OperationModelLedger.refuse(refused, call.id, "ticket"))
    }

    @Test fun refusedRetryDoesNotRefundTheEarlierUnknownAttempt() {
        val first = start(reserve(initial, "first"), "first")
        val retry = reserve(first, "retry")
        val refused = OperationModelLedger.refuse(retry, call.id, "retry")
        val recovered = OperationLedger.recover(refused)
        assertEquals(2, recovered.runs.getValue(ref.runId).state.attemptsUsed)
        assertEquals(1, recovered.runs.getValue(ref.runId).state.operationStepsUsed)
        assertEquals(1, recovered.operations.getValue(call.id).modelAttempts)
        assertTrue(recovered.operations.getValue(call.id).modelAttempt!!.priorDispatchUnconfirmed)
        assertEquals(AttemptStatus.UNKNOWN, recovered.operations.getValue(call.id).status)
    }

    @Test fun coldReservationRefundsAndRestoresPurePreparationExactlyOnce() {
        val recovered = OperationLedger.recover(reserve(initial, "ticket"))
        assertEquals(1, recovered.runs.getValue(ref.runId).state.attemptsUsed)
        assertEquals(0, recovered.runs.getValue(ref.runId).state.operationStepsUsed)
        val record = recovered.operations.getValue(call.id)
        assertEquals(AttemptStatus.PREPARED, record.status)
        assertEquals(null, record.attemptId)
        assertEquals(ModelAttemptPhase.REFUSED, record.modelAttempt!!.phase)
        assertEquals(recovered, OperationLedger.recover(recovered))
    }

    @Test fun coldIntentDoesNotPretendItWasUndispatched() {
        val intent = start(reserve(initial, "ticket"), "ticket")
        val recovered = OperationLedger.recover(intent)
        assertEquals(intent.runs, recovered.runs)
        assertEquals(AttemptStatus.UNKNOWN, recovered.operations.getValue(call.id).status)
        assertEquals(1, recovered.operations.getValue(call.id).modelAttempts)
        assertEquals(ModelAttemptPhase.DISPATCH_INTENT, recovered.operations.getValue(call.id).modelAttempt!!.phase)
    }

    @Test fun legacyReceiptWithoutTicketRetainsAllCharges() {
        val current = reserve(initial, "ticket")
        val legacy = current.copy(operations = current.operations + (call.id to
            current.operations.getValue(call.id).copy(modelAttempt = null)))
        val recovered = OperationLedger.recover(legacy)
        assertEquals(legacy.runs, recovered.runs)
        assertEquals(1, recovered.operations.getValue(call.id).modelAttempts)
        assertEquals(AttemptStatus.UNKNOWN, recovered.operations.getValue(call.id).status)
    }

    @Test fun staleTicketAndCompletedRequestCannotRefund() {
        val intent = start(reserve(initial, "ticket"), "ticket")
        assertSame(intent, OperationModelLedger.refuse(intent, call.id, "other"))
        val completed = intent.copy(operations = intent.operations + (call.id to
            OperationModelLedger.confirm(intent.operations.getValue(call.id), "ticket")))
        assertSame(completed, OperationModelLedger.refuse(completed, call.id, "ticket"))
        assertEquals(2, completed.runs.getValue(ref.runId).state.attemptsUsed)
    }

    @Test fun modelRefusalDoesNotEraseAnotherExternalEffect() {
        val current = reserve(initial, "ticket")
        val withEffect = current.copy(operations = current.operations + (call.id to
            current.operations.getValue(call.id).copy(effectAdmissions = 2)))
        val recovered = OperationLedger.recover(OperationModelLedger.refuse(withEffect, call.id, "ticket"))
        assertEquals(1, recovered.operations.getValue(call.id).effectAdmissions)
        assertEquals(AttemptStatus.UNKNOWN, recovered.operations.getValue(call.id).status)
    }

    @Test fun refusalDoesNotReviveStoppedRun() {
        val current = reserve(initial, "ticket")
        val run = current.runs.getValue(ref.runId)
        val stopped = current.copy(runs = current.runs + (ref.runId to run.copy(state =
            run.state.copy(status = RunStatus.CANCELLED))))
        val refused = OperationModelLedger.refuse(stopped, call.id, "ticket")
        assertEquals(RunStatus.CANCELLED, refused.runs.getValue(ref.runId).state.status)
        assertEquals(1, refused.runs.getValue(ref.runId).state.attemptsUsed)
        assertEquals(main, refused.runs.getValue(ref.runId).modelAttempt)
    }

    private fun reserve(ledger: ExecutionLedger, id: String): ExecutionLedger {
        val record = OperationModelLedger.reserve(ledger.operations.getValue(call.id), id)
        val run = ledger.runs.getValue(ref.runId)
        return ledger.copy(
            operations = ledger.operations + (call.id to record),
            runs = ledger.runs +
                (ref.runId to run.copy(state = run.state.copy(
                    attemptsUsed = run.state.attemptsUsed + 1,
                    operationStepsUsed = run.state.operationStepsUsed + if (record.modelAttempt!!.chargedStep) 1 else 0
                )))
        )
    }

    private fun start(ledger: ExecutionLedger, id: String): ExecutionLedger = ledger.copy(operations =
        ledger.operations + (call.id to OperationModelLedger.start(ledger.operations.getValue(call.id), id)))
}
