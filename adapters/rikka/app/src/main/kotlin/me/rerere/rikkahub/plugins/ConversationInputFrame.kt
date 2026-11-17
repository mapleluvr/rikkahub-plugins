package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.ExecutionLedger
import dev.rikkahub.plugins.protocol.SourceStamp
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode

/** Select a host-captured path without changing the user's browsing selection. */
fun Conversation.forInputPath(ids: List<String>, includeTail: Boolean = false): Conversation {
    require(ids.distinct().size == ids.size) { "Duplicate frame identity" }
    val indexByMessage = buildMap<String, Pair<Int, Int>> {
        messageNodes.forEachIndexed { nodeIndex, node ->
            node.messages.forEachIndexed { messageIndex, message ->
                require(put(message.id.toString(), nodeIndex to messageIndex) == null) { "Duplicate message identity" }
            }
        }
    }
    var position = -1
    val selected = ids.map { id ->
        val (nodeIndex, messageIndex) = requireNotNull(indexByMessage[id]) { "Input path was removed" }
        require(nodeIndex > position) { "Input path was reordered" }
        position = nodeIndex
        messageNodes[nodeIndex].copy(selectIndex = messageIndex)
    }
    return copy(messageNodes = if (includeTail) {
        val byNode = selected.associateBy { it.id }
        messageNodes.map { byNode[it.id] ?: it }
    } else {
        selected
    })
}

fun Conversation.forOperationFrame(frame: List<SourceStamp>, includeTail: Boolean = false): Conversation {
    val view = forInputPath(frame.map { it.id }, includeTail)
    val sources = view.pluginSources().associateBy { it.id }
    require(frame.all { sources[it.id] == it }) { "Operation input frame changed" }
    return view
}

/** New feature-created nodes retain a source lineage; browsing a sibling does not reveal them. */
fun Conversation.visiblePluginNodes(): List<MessageNode> {
    val lineages = runCatching { executionLedger().messageLineages }.getOrDefault(emptyMap())
    if (lineages.isEmpty()) return messageNodes
    val selected = messageNodes.mapTo(HashSet()) { it.currentMessage.id.toString() }
    return messageNodes.filter { node -> lineages[node.currentMessage.id.toString()]?.all { it in selected } != false }
}

fun Conversation.restoreBrowsingSelection(previous: Conversation): Conversation {
    val selected = previous.messageNodes.associate { it.id to it.currentMessage.id }
    return copy(messageNodes = messageNodes.map { node ->
        val index = node.messages.indexOfFirst { it.id == selected[node.id] }
        if (index >= 0) node.copy(selectIndex = index) else node
    })
}

/** Fork inherits version configuration and display ownership, never a run, permit or executable receipt. */
fun Conversation.inheritPluginHistory(source: Conversation): Conversation {
    val ids = messageNodes.flatMap { node -> node.messages.map { it.id.toString() } }.toSet()
    val sourceLineages = runCatching { source.executionLedger().messageLineages }.getOrDefault(emptyMap())
    val inherited = sourceLineages.filter { (id, ancestors) -> id in ids && ancestors.all { it in ids } }
    // An older receipt may depend on a tail cut away by this fork. Never turn lost ownership into global visibility.
    val safe = copy(messageNodes = messageNodes.filter { node ->
        node.currentMessage.id.toString().let { it !in sourceLineages || it in inherited }
    })
    val history = if (inherited.isEmpty()) {
        safe
    } else {
        safe.withExecutionLedger(
            ExecutionLedger(messageLineages = inherited)
        )
    }
    return source.packageSelection()?.let(history::withPackageSelection) ?: history
}

fun Conversation.runInputMessages(runId: String): List<UIMessage> =
    forInputPath(executionLedger().runs.getValue(runId).inputIds).currentMessages
