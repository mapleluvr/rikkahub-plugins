package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.ExecutionLedger
import dev.rikkahub.plugins.protocol.RunCheckpoint
import dev.rikkahub.plugins.protocol.RunStatus
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession

class RunHistoryConflict :
    IllegalStateException(
        "An unfinished run owns this history. Resume or Stop it before changing these messages."
    )

/** User history commands validate and publish inside the same confirmed Session transaction. */
internal suspend fun ConversationSession.commitHistoryMutation(
    requireIdleRun: Boolean = false,
    transform: (Conversation) -> Conversation,
): Conversation = commitExtension { current ->
    val ledger = runCatching { current.executionLedger() }.getOrElse { throw RunHistoryConflict() }
    val active = ledger.runs.values.filter { it.state.status == RunStatus.ACTIVE }
    if (requireIdleRun && active.isNotEmpty()) throw RunHistoryConflict()
    val proposed = transform(current)
    require(proposed.pluginState == current.pluginState) { "History commands cannot change execution state" }
    if (active.isNotEmpty() && proposed.messageNodes != current.messageNodes) {
        val before = current.messageNodes.flatMap { it.messages }.associateBy { it.id.toString() }
        val after = proposed.messageNodes.flatMap { it.messages }.associateBy { it.id.toString() }
        val ownedIds = active.flatMap { it.requiredHistoryIds(ledger) }.toSet()
        if (ownedIds.any { id ->
                val original = before[id]
                original != null && after[id]?.sourceStamp() != original.sourceStamp()
            }) {
            throw RunHistoryConflict()
        }
    }
    proposed
}

internal const val MISSING_RUN_HISTORY_GATE = "host.history-missing"

/** Include adopted results before their dependency gate has been consumed on recovery. */
internal fun RunCheckpoint.requiredHistoryIds(ledger: ExecutionLedger): Set<String> = buildSet {
    addAll(inputIds)
    messageId?.let(::add)
    addAll(approvalRequests.values.filter { it.decision == null }.map { it.messageId })
    pendingOperation?.let { call ->
        addAll(call.frame.map { it.id })
        addAll(ledger.operations[call.id]?.adoptedMessageIds ?: ledger.messageLineages.filterValues {
            it == call.frame.map { source -> source.id }
        }.keys)
    }
}
