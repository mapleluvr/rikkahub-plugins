package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.ContextProjection
import dev.rikkahub.plugins.protocol.AttemptStatus
import dev.rikkahub.plugins.protocol.ContextItem
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.SourceStamp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation

/** Validate output capabilities and project only the request copy. Original message nodes remain untouched. */
fun Conversation.validateOperationOutput(
    result: OperationResult,
    sources: List<SourceStamp>,
    capabilities: Set<String>
) {
    val plan = result.projection ?: return
    require("context.write" in capabilities) { "Projection requires context.write" }
    require(sources.map { it.id }.containsAll(plan.omit)) { "Projection refers outside its source snapshot" }
    ContextProjection.apply(currentMessages.contextItems(), plan)
}

fun Conversation.projectedContext(messages: List<UIMessage>): List<UIMessage> {
    val input = messages.map { it.sourceStamp() }
    val sources = input.associateBy { it.id }
    val items = messages.contextItems()
    val protected = items.filter { it.protected }.mapTo(HashSet()) { it.id }
    val omit = executionLedger().operations.values.filter {
        it.status == AttemptStatus.SETTLED && it.result?.projection != null &&
            it.call.frame.isNotEmpty() && input.take(it.call.frame.size) == it.call.frame &&
            it.sources.all { stamp -> sources[stamp.id] == stamp } &&
            it.result?.projection?.omit?.none { id -> id in protected } == true
    }.flatMap { it.result!!.projection!!.omit }.toSet()
    if (omit.isEmpty()) return messages
    val projected = ContextProjection.apply(messages.contextItems(), dev.rikkahub.plugins.protocol.ProjectionPlan(omit))
        .mapTo(HashSet()) { it.id }
    return messages.filter { it.id.toString() in projected }
}

private fun List<UIMessage>.contextItems(): List<ContextItem> {
    val lastUser = lastOrNull { it.role == MessageRole.USER }?.id
    val last = lastOrNull()?.id
    return map { message ->
        ContextItem(message.id.toString(), "", protected =
            message.role == MessageRole.SYSTEM || message.id == lastUser || message.id == last)
    }
}
