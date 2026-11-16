package me.rerere.rikkahub.plugins.builtin

import dev.rikkahub.plugins.protocol.ArtifactDraft
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.PackageLease
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.agent.AgentBoundary
import me.rerere.ai.agent.AgentBoundaryContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.buildCompressPrompt
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.insertContextCheckpoint
import me.rerere.rikkahub.data.model.lastContextTokens
import me.rerere.rikkahub.plugins.BoundaryOperation
import me.rerere.rikkahub.plugins.ConversationOperation
import me.rerere.rikkahub.plugins.PreparedConversationOperation
import me.rerere.rikkahub.plugins.executionLedger
import me.rerere.rikkahub.plugins.forInputPath
import me.rerere.rikkahub.plugins.pluginSources
import me.rerere.rikkahub.utils.JsonInstant
import java.util.Locale

@Serializable
data class CompactInput(
    val additionalPrompt: String = "",
    val keepRecentMessages: Int = 0,
    val clearSuggestions: Boolean = false,
    val midTurn: Boolean = false,
)

/** Policy and artifact interpretation for the shipped context feature, outside the execution kernel. */
class BuiltinContextFeature(
    private val summarize: suspend (Conversation, List<UIMessage>, String, suspend () -> Unit) -> String,
) {
    val operation =
        ConversationOperation(
            ID,
            "压缩上下文",
            LEASE,
            setOf("context.write", "model.request"),
            continueRunAfterFailure = true
        ) {
                source,
                input
            ->
            prepare(source, JsonInstant.decodeFromJsonElement<CompactInput>(input))
        }

    fun input(options: CompactInput = CompactInput()): JsonElement = JsonInstant.encodeToJsonElement(options)

    suspend fun boundaryDecision(
        session: me.rerere.rikkahub.service.ConversationSession,
        ref: dev.rikkahub.plugins.protocol.RunRef,
        boundary: AgentBoundaryContext,
        settings: Settings,
        model: Model,
        allowHistoryRewrite: Boolean,
        afterOperation: Boolean,
    ): BoundaryOperation? {
        val saved = session.state.value.executionLedger().runs.getValue(ref.runId)
        val noBenefit = "$FEATURE/no-benefit"
        if (noBenefit in saved.featureFlags) return null
        val reply = boundary.message
        if (afterOperation && boundary.cursor.boundary == AgentBoundary.AfterTools &&
            (reply?.stepCount ?: 0) <= 1 &&
            (reply?.contextTokens() ?: 0) >= model.contextLength * settings.autoCompressThreshold) {
            me.rerere.rikkahub.plugins.SessionRunStore(session).update(ref) {
                it.copy(featureFlags = it.featureFlags + noBenefit)
            }
            return null
        }
        val source = session.state.value.forInputPath(saved.inputIds)
        return boundaryOperation(boundary, source, settings, model, allowHistoryRewrite, afterOperation)
    }

    fun boundaryOperation(
        boundary: AgentBoundaryContext,
        conversation: Conversation,
        settings: Settings,
        model: Model,
        allowHistoryRewrite: Boolean,
        afterOperation: Boolean,
    ): BoundaryOperation? {
        if (!allowHistoryRewrite || boundary.cursor.boundary != AgentBoundary.AfterTools) return null
        return automaticCommand(conversation, settings, model, boundary.message, afterOperation)?.let {
            BoundaryOperation(operation, input(CompactInput(midTurn = true)), it)
        }
    }

    fun automaticCommand(
        conversation: Conversation,
        settings: Settings,
        model: Model,
        reply: UIMessage? = null,
        afterOperation: Boolean = false,
    ): String? {
        if (FEATURE in settings.disabledPluginFeatures || !settings.enableAutoCompress ||
            model.contextLength <= 0) {
            return null
        }
        val last = reply ?: conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return null
        val tokens = if (reply != null) reply.contextTokens() else conversation.lastContextTokens()
        if (tokens == null || tokens < model.contextLength * settings.autoCompressThreshold) return null
        // A fresh summary that already fills the context cannot be improved by immediately summarizing it again.
        if (afterOperation && (last.stepCount ?: 0) <= 1) return null
        val command = "$ID/auto/${last.id}"
        return command.takeUnless { it in conversation.executionLedger().operations }
    }

    private fun prepare(source: Conversation, input: CompactInput): PreparedConversationOperation {
        val nodes = source.visibleMessageNodes
        val checkpoint = nodes.indexOfLast { it.currentMessage.isContextCheckpoint }
        val end = nodes.size - input.keepRecentMessages.coerceAtLeast(0)
        require(end > checkpoint + 1) { "没有可压缩的消息" }
        check(nodes.subList(checkpoint + 1, end).none { node ->
            node.currentMessage.getTools().any { it.isPending || it.canResumeExecution }
        }) { "请先处理待完成的工具调用，或将其保留在压缩范围之外" }
        val selected = source.currentMessages.take(end)
        val ids = selected.map { it.id.toString() }
        val stamps = source.pluginSources().associateBy { it.id }
        val after = nodes[end - 1].id
        val prompt = buildCompressPrompt(input.additionalPrompt, Locale.getDefault().displayName, input.midTurn)
        return PreparedConversationOperation(
            sources = ids.map(stamps::getValue),
            execute = { invocation ->
                val text = summarize(source, selected, prompt, invocation.beforeModelRequest)
                require(text.isNotBlank()) { "摘要为空" }
                OperationResult(artifacts = listOf(ArtifactDraft("context.summary", JsonPrimitive(text))))
            },
            validateAdoption = { latest ->
                check(latest.currentMessages.take(end).map { it.id.toString() } == ids) { "压缩来源分支已变更" }
            },
            adopt = { latest, result ->
                val summary = result.artifacts.single { it.type == "context.summary" }.content.jsonPrimitive.content
                val adopted = checkNotNull(latest.insertContextCheckpoint(after, summary)) { "压缩来源已删除" }
                if (input.clearSuggestions) adopted.copy(chatSuggestions = emptyList()) else adopted
            },
        )
    }

    companion object {
        const val FEATURE = "builtin.context"
        const val ID = "$FEATURE/compact"
        val LEASE = PackageLease(FEATURE, "builtin-context-v1", 1)
    }
}
