package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.RunControl
import dev.rikkahub.plugins.protocol.Gate
import dev.rikkahub.plugins.protocol.GateKind
import dev.rikkahub.plugins.protocol.RunCheckpoint
import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.RunStatus
import dev.rikkahub.plugins.protocol.ToolApprovalRequestRecord
import dev.rikkahub.plugins.protocol.ToolCallStamp
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

internal const val TOOL_APPROVAL_GATE = "host.tool-approval"

/** Persist requests and their visible handles in the same confirmed transaction. */
internal suspend fun ConversationSession.requestToolApprovals(ref: RunRef, expected: UIMessage) {
    commitExtension { current ->
        val ledger = current.executionLedger()
        val run = ledger.runs.getValue(ref.runId)
        check(run.state.ref == ref && run.state.status == RunStatus.ACTIVE)
        val message = current.messageNodes.flatMap { it.messages }.single { it.id == expected.id }
        check(message == expected) { "Approval source changed before publication" }
        val requests = run.approvalRequests.toMutableMap()
        val updated = message.copy(parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool || !part.isPending) return@map part
            val call = ToolCallStamp(part.toolCallId, part.toolName, part.input)
            val definition = run.toolDefinitions.getValue(part.toolName)
            val existing = part.approvalId?.let(requests::get)?.takeIf {
                it.messageId == message.id.toString() && it.call == call &&
                    it.definition == definition && it.decision == null
            }
            val request = existing ?: ToolApprovalRequestRecord(
                Uuid.random().toString(),
                message.id.toString(),
                call,
                definition
            )
            requests[request.id] = request
            part.copy(approvalId = request.id)
        })
        current.replaceApprovalMessage(updated).withExecutionLedger(ledger.copy(
            runs = ledger.runs +
                (ref.runId to run.copy(
                    approvalRequests = requests,
                    state = RunControl.addGate(run.state, Gate(TOOL_APPROVAL_GATE, GateKind.TOOL_APPROVAL)),
                ))
        ))
    }
}

/** Returns false for stale clicks, another conversation, ended runs, edited calls, or duplicate replies. */
suspend fun ConversationSession.respondToToolApproval(id: String, decision: ToolApprovalState): Boolean {
    require(
        decision is ToolApprovalState.Approved || decision is ToolApprovalState.Denied ||
            decision is ToolApprovalState.Answered
    )
    var accepted = false
    commitExtension { current ->
        val updated = current.withBoundToolApproval(id, decision)
        accepted = updated != null
        updated ?: current
    }
    return accepted
}

internal fun Conversation.withBoundToolApproval(id: String, decision: ToolApprovalState): Conversation? {
    require(decision is ToolApprovalState.Approved || decision is ToolApprovalState.Denied ||
        decision is ToolApprovalState.Answered)
    val ledger = executionLedger()
    val run = ledger.runs.values.singleOrNull {
        it.state.status == RunStatus.ACTIVE && id in it.approvalRequests
    } ?: return null
    val request = run.approvalRequests.getValue(id)
    if (request.decision != null || request.messageId !in run.inputIds ||
        request.definition != run.toolDefinitions[request.call.name]) return null
    val message = messageNodes.flatMap { it.messages }
        .singleOrNull { it.id.toString() == request.messageId } ?: return null
    val tool = message.getTools().singleOrNull { it.toolCallId == request.call.id } ?: return null
    if (!tool.isPending || tool.startedAt != null || tool.approvalId != id ||
        request.call != ToolCallStamp(tool.toolCallId, tool.toolName, tool.input)) return null
    val updated = message.copy(parts = message.parts.map { part ->
        if (part === tool) tool.copy(approvalState = decision) else part
    })
    val state = if (updated.getTools().none { it.isPending }) {
        RunControl.resolveGate(run.state, TOOL_APPROVAL_GATE)
    } else run.state
    return replaceApprovalMessage(updated).withExecutionLedger(ledger.copy(runs = ledger.runs +
        (run.state.ref.runId to run.copy(
            state = state,
            approvalRequests = run.approvalRequests + (id to request.copy(decision = decisionKey(decision))),
        ))))
}

internal fun RunCheckpoint.approvalFailure(message: UIMessage, tool: UIMessagePart.Tool): String? {
    if (tool.approvalState == ToolApprovalState.Auto) return null
    val request = tool.approvalId?.let(approvalRequests::get)
    return if (request == null || request.messageId != message.id.toString() ||
        request.call != ToolCallStamp(tool.toolCallId, tool.toolName, tool.input) ||
        request.definition != toolDefinitions[tool.toolName] ||
        request.decision != decisionKey(tool.approvalState)) {
        "Tool decision is not bound to this run, input and implementation"
    } else {
        null
    }
}

private fun decisionKey(value: ToolApprovalState) = JsonInstant.encodeToString<ToolApprovalState>(value)

/** Preserve the page's selected branch while updating the original request. */
private fun Conversation.replaceApprovalMessage(updated: UIMessage): Conversation = copy(
    messageNodes = messageNodes.map { node ->
        node.copy(messages = node.messages.map { if (it.id == updated.id) updated else it })
    }
)

/** Copying history never transfers executable approval or uncertain side effects. */
fun UIMessagePart.Tool.asHistoricalTool(): UIMessagePart.Tool = if (isExecuted) {
    copy(approvalId = null)
} else {
    copy(
        approvalId = null,
        output = listOf(UIMessagePart.Text(if (startedAt == null) {
            "Tool was not executed. This is inherited history without execution permission."
        } else {
            "Tool execution outcome is unknown in the original conversation. This historical copy cannot replay it."
        })),
    )
}
