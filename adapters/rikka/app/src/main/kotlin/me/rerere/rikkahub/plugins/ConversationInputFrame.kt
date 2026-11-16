package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.ExecutionLedger
import dev.rikkahub.plugins.protocol.SourceStamp
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode

/** Select a host-captured path without changing the user's browsing selection. */
fun Conversation.forInputPath(ids: List<String>, includeTail: Boolean = false): Conversation {
    require(ids.distinct().size == ids.size) { "Duplicate frame identity" }
    var position = -1
    val selected = ids.map { id ->
        val index = messageNodes.indexOfFirst { node -> node.messages.any { it.id.toString() == id } }
        require(index > position) { "Input path was removed or reordered" }
        position = index
        messageNodes[index].let { node ->
            node.copy(selectIndex = node.messages.indexOfFirst { it.id.toString() == id })
        }
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

/** Fork copies historical display ownership only, never a run, permit or executable operation receipt. */
fun Conversation.inheritPluginHistory(source: Conversation): Conversation {
    val ids = messageNodes.flatMap { node -> node.messages.map { it.id.toString() } }.toSet()
    val inherited = runCatching { source.executionLedger().messageLineages }.getOrDefault(emptyMap())
        .filter { (id, ancestors) -> id in ids && ancestors.all { it in ids } }
    return if (inherited.isEmpty()) this else withExecutionLedger(ExecutionLedger(messageLineages = inherited))
}

fun Conversation.runInputMessages(runId: String): List<UIMessage> =
    forInputPath(executionLedger().runs.getValue(runId).inputIds).currentMessages
