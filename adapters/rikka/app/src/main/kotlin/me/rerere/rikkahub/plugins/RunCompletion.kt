package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.RunStatus
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation

data class CompletedRunReply(val conversationId: kotlin.uuid.Uuid, val runId: String, val message: UIMessage)

/** Completion consumers use the confirmed reply owned by a terminal run, never the last visible message. */
fun Conversation.completedRunReply(ref: RunRef): UIMessage? {
    val run = executionLedger().runs[ref.runId] ?: return null
    if (run.state.ref != ref || run.state.status != RunStatus.COMPLETED) return null
    return messageNodes.flatMap { it.messages }.singleOrNull {
        it.id.toString() == run.messageId && it.role == MessageRole.ASSISTANT
    }
}
