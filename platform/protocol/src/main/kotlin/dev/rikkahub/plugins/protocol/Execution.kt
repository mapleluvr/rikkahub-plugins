package dev.rikkahub.plugins.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Host-issued identity. The transport must bind it to an authenticated principal. */
@Serializable
data class RunRef(
    val conversationId: String,
    val branchId: String,
    val runId: String,
    val incarnation: Long = 0
)

@Serializable
data class BranchRef(val conversationId: String, val branchId: String)

@Serializable
data class PackageLease(val pluginId: String, val packageHash: String, val activationEpoch: Long)

@Serializable
enum class GateKind { USER_PAUSE, TOOL_APPROVAL, PLUGIN_INPUT, OPERATION_DEPENDENCY, PLUGIN_FAULT }

@Serializable
data class Gate(val id: String, val kind: GateKind)

@Serializable
enum class RunStatus { ACTIVE, COMPLETED, CANCELLED }

@Serializable
data class RunState(
    val ref: RunRef,
    val maxSteps: Int,
    val maxAttempts: Int,
    val revision: Long = 0,
    val status: RunStatus = RunStatus.ACTIVE,
    val gates: Map<String, Gate> = emptyMap(),
    val stepsUsed: Int = 0,
    val attemptsUsed: Int = 0,
    /** Invalidates prepared input; bookkeeping alone never advances this version. */
    val frameRevision: Long = 0
) {
    init {
        require(maxSteps > 0 && maxAttempts > 0)
        require(stepsUsed in 0..maxSteps && attemptsUsed in stepsUsed..maxAttempts)
        require(revision >= 0 && frameRevision in 0..revision)
        require(gates.all { (key, value) -> key == value.id && key.isNotBlank() })
    }
}

@Serializable
data class OperationCall(
    val id: String,
    val type: String,
    val target: BranchRef,
    val input: JsonElement,
    val handler: PackageLease,
    /** Null means the trusted host/user invoked the operation. */
    val caller: PackageLease? = null,
    val run: RunRef? = null
) {
    init {
        require(run == null || (run.conversationId == target.conversationId && run.branchId == target.branchId)) {
            "Operation target and owning run disagree"
        }
    }
}

/** An omission set, not a contiguous range or a replacement algorithm. */
@Serializable
data class ProjectionPlan(val omit: Set<String> = emptySet())

@Serializable
data class ArtifactDraft(val type: String, val content: JsonElement)

@Serializable
data class OperationResult(val projection: ProjectionPlan? = null, val artifacts: List<ArtifactDraft> = emptyList())

data class ContextItem(val id: String, val text: String, val protected: Boolean = false, val toolGroup: String? = null)
