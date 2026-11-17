package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.AttemptStatus
import dev.rikkahub.plugins.protocol.ExecutionLedger
import dev.rikkahub.plugins.protocol.ModelAttemptPhase
import dev.rikkahub.plugins.protocol.ModelAttemptRecord
import dev.rikkahub.plugins.protocol.OperationRecord

/** Job-local dispatch evidence; accounting changes never touch the main run's modelAttempt. */
object OperationModelLedger {
    fun reserve(record: OperationRecord, id: String): OperationRecord {
        require(id.isNotBlank() && record.modelAttempt?.id != id)
        check(record.status == AttemptStatus.RUNNING)
        check(record.modelAttempt?.phase != ModelAttemptPhase.RESERVED) { "Previous job reservation is unresolved" }
        return record.copy(
            modelAttempts = record.modelAttempts + 1,
            effectAdmissions = record.effectAdmissions + 1,
            modelAttempt = ModelAttemptRecord(
                id,
                record.modelAttempts,
                chargedStep = record.modelAttempts == 0,
                priorDispatchUnconfirmed = record.modelAttempt?.let {
                    it.phase == ModelAttemptPhase.DISPATCH_INTENT || it.priorDispatchUnconfirmed
                } ?: (record.modelAttempts > 0),
            ),
        )
    }

    fun start(record: OperationRecord, id: String): OperationRecord {
        val attempt = checkNotNull(record.modelAttempt)
        require(attempt.id == id && attempt.phase == ModelAttemptPhase.RESERVED)
        check(record.status == AttemptStatus.RUNNING)
        return record.copy(modelAttempt = attempt.copy(phase = ModelAttemptPhase.DISPATCH_INTENT))
    }

    /** Only the still-local dispatch caller may prove an intent never entered the Provider. */
    fun refuse(ledger: ExecutionLedger, operationId: String, id: String): ExecutionLedger {
        val record = ledger.operations.getValue(operationId)
        val refused = ModelAttemptLedger.refusedTicket(record.modelAttempt, id) ?: return ledger
        check(record.modelAttempts > 0 && record.effectAdmissions > 0) { "Job accounting is inconsistent" }
        var runs = ledger.runs
        record.call.run?.let { ref ->
            runs[ref.runId]?.let { run ->
                require(run.state.ref == ref) { "Owning run changed" }
                val step = if (refused.chargedStep) 1 else 0
                check(run.state.attemptsUsed > 0 && run.state.operationStepsUsed >= step)
                runs = runs + (ref.runId to run.copy(state = run.state.copy(
                    attemptsUsed = run.state.attemptsUsed - 1,
                    operationStepsUsed = run.state.operationStepsUsed - step,
                    revision = run.state.revision + 1,
                )))
            }
        }
        return ledger.copy(
            runs = runs,
            operations = ledger.operations + (operationId to record.copy(
                modelAttempts = record.modelAttempts - 1,
                effectAdmissions = record.effectAdmissions - 1,
                modelAttempt = refused,
            )),
        )
    }

    fun confirm(record: OperationRecord, id: String): OperationRecord = record.modelAttempt?.let { attempt ->
        if (attempt.id == id && attempt.phase == ModelAttemptPhase.DISPATCH_INTENT) {
            record.copy(modelAttempt = attempt.copy(phase = ModelAttemptPhase.COMPLETED))
        } else {
            record
        }
    } ?: record

    /** RESERVED cannot have entered Provider; legacy and intent records are never guessed or refunded. */
    fun recover(ledger: ExecutionLedger): ExecutionLedger {
        var recovered = ledger
        ledger.operations.forEach { (id, record) ->
            record.modelAttempt?.takeIf { it.phase == ModelAttemptPhase.RESERVED }?.let { attempt ->
                recovered = refuse(recovered, id, attempt.id)
            }
            val current = recovered.operations.getValue(id)
            if (current.status in setOf(AttemptStatus.RUNNING, AttemptStatus.UNKNOWN) &&
                current.modelAttempts == 0 && current.effectAdmissions == 0 &&
                current.modelAttempt?.phase == ModelAttemptPhase.REFUSED &&
                current.modelAttempt?.priorDispatchUnconfirmed == false) {
                recovered = recovered.copy(operations = recovered.operations + (id to current.copy(
                    status = AttemptStatus.PREPARED,
                    attemptId = null,
                )))
            }
        }
        return recovered
    }
}
