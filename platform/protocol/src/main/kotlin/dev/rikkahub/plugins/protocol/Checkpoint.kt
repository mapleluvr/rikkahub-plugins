package dev.rikkahub.plugins.protocol

import kotlinx.serialization.Serializable

@Serializable
data class SourceStamp(val id: String, val digest: String)

@Serializable
enum class AttemptStatus { PREPARED, RUNNING, SETTLED, UNKNOWN, CANCELLED }

/** The attempt identity is also the idempotency key passed to external work. */
@Serializable
data class OperationRecord(
    val call: OperationCall,
    val sources: List<SourceStamp>,
    val status: AttemptStatus = AttemptStatus.PREPARED,
    val attemptId: String? = null,
    val result: OperationResult? = null
)

@Serializable
data class RunCheckpoint(val state: RunState, val boundary: String, val nextStep: Int, val toolCallId: String? = null)

/** One versioned host envelope. Unknown envelope versions must never execute. */
@Serializable
data class ExecutionLedger(
    val version: Int = 1,
    val runs: Map<String, RunCheckpoint> = emptyMap(),
    val operations: Map<String, OperationRecord> = emptyMap()
)
