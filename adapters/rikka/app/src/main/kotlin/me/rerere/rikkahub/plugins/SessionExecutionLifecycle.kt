package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.ModelAttemptLedger
import dev.rikkahub.plugins.core.OperationLedger
import dev.rikkahub.plugins.core.RunControl
import dev.rikkahub.plugins.protocol.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.utils.JsonInstant

/** Unknown host envelopes are preserved and block scheduling until an explicit migration is available. */
fun Conversation.hasUnfinishedPluginRun(): Boolean = runCatching {
    executionLedger().runs.values.any { it.state.status == RunStatus.ACTIVE }
}.getOrDefault(true)

/** Called once during cold initialization, before a loaded session is made schedulable. */
fun Conversation.recoverPluginExecution(): Conversation {
    val payload = pluginState["host.execution"] ?: return this
    val decoded = runCatching { JsonInstant.decodeFromJsonElement<ExecutionLedger>(payload) }.getOrElse { return this }
    if (decoded.version !in setOf(2, 3)) return this
    // Old cursors lack a trusted input path. Preserve the original envelope and require reconciliation.
    val ledger = if (decoded.version == 2) {
        decoded.copy(
            version = 3,
            runs = decoded.runs.mapValues { (_, run) ->
                if (run.state.status == RunStatus.ACTIVE) {
                    run.copy(
                        state =
                        RunControl.addGate(run.state, Gate("host.legacy-recovery", GateKind.PLUGIN_FAULT))
                    )
                } else {
                    run
                }
            },
        )
    } else {
        decoded
    }
    val recovered = OperationLedger.recover(ledger).let { records ->
        val messages = messageNodes.flatMap { it.messages }.associateBy { it.id.toString() }
        records.copy(runs = records.runs.mapValues { (_, original) ->
            val run = ModelAttemptLedger.recover(original)
            val unboundPending = run.inputIds.mapNotNull(messages::get).any { message ->
                message.getTools().any { tool ->
                    val request = tool.approvalId?.let(run.approvalRequests::get)
                    tool.isPending && (request == null || request.decision != null ||
                        request.messageId != message.id.toString() ||
                        request.call != ToolCallStamp(tool.toolCallId, tool.toolName, tool.input) ||
                        request.definition != run.toolDefinitions[tool.toolName])
                }
            }
            val missingHistory = run.requiredHistoryIds(records).any { it !in messages }
            val reason = when {
                missingHistory -> MISSING_RUN_HISTORY_GATE
                unboundPending -> "host.approval-publication-incomplete"
                ModelAttemptLedger.needsReconciliation(run) -> "host.recovery"
                else -> null
            }
            if (run.state.status == RunStatus.ACTIVE && reason != null) {
                run.copy(state = RunControl.addGate(run.state, Gate(reason, GateKind.PLUGIN_FAULT)))
            } else {
                run
            }
        })
    }
    if (recovered == decoded) return this
    val migrated = withExecutionLedger(recovered)
    return if (decoded.version == 2) {
        migrated.copy(
            pluginState = JsonObject(migrated.pluginState + ("host.execution.previous.v2" to payload))
        )
    } else {
        migrated
    }
}

/** Bind the first persisted input to an existing empty draft run without changing identity or gates. */
fun Conversation.bindPromotedPluginDraft(): Conversation {
    if (messageNodes.isEmpty()) return this
    val ledger = runCatching { executionLedger() }.getOrElse { return this }
    val runs = ledger.runs.mapValues { (_, run) ->
        if (run.state.status == RunStatus.ACTIVE && run.state.ref.branchId == "draft" &&
            run.inputIds.isEmpty() && run.state.stepsUsed == 0) {
            run.copy(inputIds = currentMessages.map { it.id.toString() })
        } else {
            run
        }
    }
    return if (runs == ledger.runs) this else withExecutionLedger(ledger.copy(runs = runs))
}

/** Explicit user cancellation is independent of a coroutine/Job being present. */
suspend fun ConversationSession.cancelPluginRuns() {
    commitExtension { current ->
        val ledger = current.executionLedger()
        val cancelled = ledger.runs.values.filter { it.state.status != RunStatus.COMPLETED }
        val ownedMessages = cancelled.flatMap { it.inputIds + listOfNotNull(it.messageId) }.toSet()
        val messages = current.messageNodes.map { node ->
            node.copy(messages = node.messages.map { message ->
                if (message.id.toString() !in
                    ownedMessages) {
                    message
                } else {
                    message.copy(parts = message.parts.map { part ->
                        if (part is UIMessagePart.Tool && !part.isExecuted && part.startedAt == null) {
                            part.copy(
                                approvalId = null,
                                approvalState = ToolApprovalState.Denied("Run stopped"),
                                output = listOf(UIMessagePart.Text("Tool cancelled before execution by user Stop.")),
                            )
                        } else {
                            part
                        }
                    })
                }
            })
        }
        current.copy(
            messageNodes = messages
        ).withExecutionLedger(ledger.copy(runs = ledger.runs.mapValues { (_, checkpoint) ->
            checkpoint.copy(state = RunControl.cancel(checkpoint.state))
        }))
    }
}
