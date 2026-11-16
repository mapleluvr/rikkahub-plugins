package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.OperationLedger
import dev.rikkahub.plugins.core.RunControl
import dev.rikkahub.plugins.protocol.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession

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
    val ledger = if (decoded.version == 2) decoded.copy(
        version = 3,
        runs = decoded.runs.mapValues { (_, run) ->
            if (run.state.status == RunStatus.ACTIVE) run.copy(state =
                RunControl.addGate(run.state, Gate("host.legacy-recovery", GateKind.PLUGIN_FAULT))
            ) else run
        },
    ) else decoded
    val recovered = OperationLedger.recover(ledger)
    if (recovered == decoded) return this
    val migrated = withExecutionLedger(recovered)
    return if (decoded.version == 2) migrated.copy(
        pluginState = JsonObject(migrated.pluginState + ("host.execution.previous.v2" to payload))
    ) else migrated
}

/** Bind the first persisted input to an existing empty draft run without changing identity or gates. */
fun Conversation.bindPromotedPluginDraft(): Conversation {
    if (messageNodes.isEmpty()) return this
    val ledger = runCatching { executionLedger() }.getOrElse { return this }
    val runs = ledger.runs.mapValues { (_, run) ->
        if (run.state.status == RunStatus.ACTIVE && run.state.ref.branchId == "draft" &&
            run.inputIds.isEmpty() && run.state.stepsUsed == 0) {
            run.copy(inputIds = currentMessages.map { it.id.toString() })
        } else run
    }
    return if (runs == ledger.runs) this else withExecutionLedger(ledger.copy(runs = runs))
}

/** Explicit user cancellation is independent of a coroutine/Job being present. */
suspend fun ConversationSession.cancelPluginRuns() {
    if (!state.value.hasUnfinishedPluginRun()) return
    commitExtension { current ->
        val ledger = current.executionLedger()
        current.withExecutionLedger(ledger.copy(runs = ledger.runs.mapValues { (_, checkpoint) ->
            checkpoint.copy(state = RunControl.cancel(checkpoint.state))
        }))
    }
}
