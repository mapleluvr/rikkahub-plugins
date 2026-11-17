package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.AttemptStatus
import dev.rikkahub.plugins.protocol.ExecutionLedger
import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationRecord
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.SourceStamp

/** Pure receipt transitions. Persist each returned ledger before starting the next effect. */
object OperationLedger {
    fun prepare(ledger: ExecutionLedger, call: OperationCall, sources: List<SourceStamp>): ExecutionLedger {
        validate(ledger)
        require(call.id.isNotBlank())
        require(sources.map { it.id }.distinct().size == sources.size)
        ledger.operations[call.id]?.let { existing ->
            require(existing.call == call && existing.sources == sources) {
                "Command identity reused for different input"
            }
            return ledger
        }
        return ledger.copy(operations = ledger.operations + (call.id to OperationRecord(call, sources.toList())))
    }

    fun start(ledger: ExecutionLedger, id: String, attemptId: String): ExecutionLedger {
        require(attemptId.isNotBlank())
        val record = record(ledger, id)
        check(record.status == AttemptStatus.PREPARED) { "Operation already started; reconcile its recorded attempt" }
        return put(ledger, record.copy(status = AttemptStatus.RUNNING, attemptId = attemptId))
    }

    fun settle(
        ledger: ExecutionLedger,
        id: String,
        attemptId: String,
        call: OperationCall,
        sources: List<SourceStamp>,
        result: OperationResult
    ): ExecutionLedger {
        val record = record(ledger, id)
        require(record.call == call) { "Operation target, input or package lease changed" }
        require(record.attemptId == attemptId) { "Stale attempt" }
        val currentSources = sources.associateBy { it.id }
        require(currentSources.size == sources.size) { "Duplicate source identity" }
        require(record.sources.all { currentSources[it.id] == it }) {
            "Operation source was edited or removed"
        }
        if (record.status == AttemptStatus.SETTLED) {
            require(record.result == result) { "An attempt cannot settle twice with different output" }
            return ledger
        }
        check(record.status == AttemptStatus.RUNNING) {
            "Unknown or cancelled attempts require explicit reconciliation"
        }
        return put(ledger, record.copy(status = AttemptStatus.SETTLED, result = result))
    }

    fun recover(ledger: ExecutionLedger): ExecutionLedger {
        validate(ledger)
        val recovered = OperationModelLedger.recover(ledger)
        return recovered.copy(operations = recovered.operations.mapValues { (_, record) ->
            if (record.status == AttemptStatus.RUNNING) record.copy(status = AttemptStatus.UNKNOWN) else record
        })
    }

    fun cancel(ledger: ExecutionLedger, id: String): ExecutionLedger {
        val record = record(ledger, id)
        return when (record.status) {
            AttemptStatus.SETTLED, AttemptStatus.CANCELLED, AttemptStatus.UNKNOWN -> ledger
            AttemptStatus.PREPARED -> put(ledger, record.copy(status = AttemptStatus.CANCELLED))
            AttemptStatus.RUNNING -> put(ledger, record.copy(status = AttemptStatus.UNKNOWN))
        }
    }

    private fun validate(ledger: ExecutionLedger) {
        require(ledger.version == 3) { "Unsupported execution ledger version" }
        require(ledger.operations.all { (id, record) -> id == record.call.id }) { "Invalid receipt identity" }
    }

    private fun record(ledger: ExecutionLedger, id: String): OperationRecord {
        validate(ledger)
        return requireNotNull(ledger.operations[id]) { "Unknown operation receipt" }
    }

    private fun put(ledger: ExecutionLedger, record: OperationRecord) =
        ledger.copy(operations = ledger.operations + (record.call.id to record))
}
