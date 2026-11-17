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
    val result: OperationResult? = null,
    val modelAttempts: Int = 0,
    val maxModelAttempts: Int = 8,
    /** Recorded before permission returns to any external-effect handler. */
    val effectAdmissions: Int = 0,
    /** Null only on legacy receipts; display lineage is not an operation-result index. */
    val adoptedMessageIds: List<String>? = null,
)

@Serializable
data class ToolCallStamp(val id: String, val name: String, val input: String)

/** A host-issued request for one human decision; scoped by its owning RunCheckpoint. */
@Serializable
data class ToolApprovalRequestRecord(
    val id: String,
    val messageId: String,
    val call: ToolCallStamp,
    val definition: String,
    val decision: String? = null,
)

@Serializable
data class RunCheckpoint(
    val state: RunState,
    val boundary: String,
    val nextStep: Int,
    val toolCallId: String? = null,
    val messageId: String? = null,
    val toolBatch: List<ToolCallStamp> = emptyList(),
    val pendingOperation: OperationCall? = null,
    /** Logical input path; independent of the UI's currently selected alternatives. */
    val inputIds: List<String> = emptyList(),
    val replyNodeId: String? = null,
    val featureFlags: Set<String> = emptySet(),
    val toolDefinitions: Map<String, String> = emptyMap(),
    val consumedToolPermits: Map<String, String> = emptyMap(),
    val approvalRequests: Map<String, ToolApprovalRequestRecord> = emptyMap(),
    /** Exact packages selected when this logical run was created, including an explicitly empty selection. */
    val pluginLeases: List<PackageLease> = emptyList(),
)

/** One versioned host envelope. Unknown envelope versions must never execute. */
@Serializable
data class ExecutionLedger(
    val version: Int = 3,
    val runs: Map<String, RunCheckpoint> = emptyMap(),
    val operations: Map<String, OperationRecord> = emptyMap(),
    /** Host-inserted messages are visible only on their source path. */
    val messageLineages: Map<String, List<String>> = emptyMap(),
)
