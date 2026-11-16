package me.rerere.rikkahub.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.agent.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ApprovalLifecycleTest {
    private val hooks = object : AgentHooks {
        override suspend fun prepareStep(
            messages: List<UIMessage>
        ) = StepRequest(messages, TextGenerationParams(Model()))
    }

    @Test
    fun stopRevokesUnstartedApprovalAndUnblocksOrdinaryInput() = runBlocking {
        val session = open(Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start"))))
        val effects = mutableListOf<String>()
        val run = execute(session, effects, offerTools = true)
        val tool = session.state.value.currentMessages.last().getTools().first()
        assertTrue(tool.isPending)
        session.cancelPluginRuns()
        assertTrue("stopped approvals must not block the next ordinary input", session.canStartNextTurn)
        assertFalse(session.respondToToolApproval(checkNotNull(tool.approvalId), ToolApprovalState.Approved))
        assertEquals(
            dev.rikkahub.plugins.protocol.RunStatus.CANCELLED,
            session.state.value.executionLedger().runs.getValue(run.ref.runId).state.status
        )
        assertTrue(effects.isEmpty())
    }

    @Test
    fun forkCopiesReadableToolHistoryWithoutApprovalOrExecutionRights() = runBlocking {
        val session = open(Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start"))))
        val effects = mutableListOf<String>()
        execute(session, effects, offerTools = true)
        val approval = session.state.value.currentMessages.last().getTools().first().approvalId!!
        assertTrue(session.respondToToolApproval(approval, ToolApprovalState.Approved))
        val reply = session.state.value.currentMessages.last()
        val fork = session.state.value.fork(reply.id, emptySet()) { it }!!
        assertTrue(fork.executionLedger().runs.isEmpty())
        assertFalse(
            "inherited pending tools are readable facts, not new approval requests",
            fork.isAwaitingToolApproval
        )
        assertTrue(fork.currentMessages.last().getTools().all { it.isExecuted })
        execute(open(fork), effects, offerTools = false)
        assertTrue("fork must not execute either inherited tool", effects.isEmpty())
    }

    @Test
    fun delayedDecisionStaysOnOriginalMessageAndDuplicateDecisionIsRejected() = runBlocking {
        val session = open(Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start"))))
        val effects = mutableListOf<String>()
        execute(session, effects, offerTools = true)
        val original = session.state.value.currentMessages.last()
        val approval = original.getTools().first().approvalId!!
        val node = session.state.value.getMessageNodeByMessageId(original.id)!!
        val sibling = original.copy(id = Uuid.random(), parts = original.parts.map { part ->
            if (part is UIMessagePart.Tool) part.copy(input = "{\"sibling\":true}", approvalId = null) else part
        })
        session.update { it.upsertMessage(node.id, sibling) }
        assertTrue(session.respondToToolApproval(approval, ToolApprovalState.Approved))
        assertEquals(sibling, session.state.value.currentMessages.last())
        val old = session.state.value.messageNodes.last().messages.first { it.id == original.id }
        assertEquals(ToolApprovalState.Approved, old.getTools().first().approvalState)
        assertFalse(session.respondToToolApproval(approval, ToolApprovalState.Approved))
        assertTrue(effects.isEmpty())
    }

    @Test
    fun stopPreservesStartedUnknownEffectButRevokesUnstartedBatchMembers() = runBlocking {
        val session = open(Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start"))))
        execute(session, mutableListOf(), offerTools = true)
        val original = session.state.value.currentMessages.last()
        val node = session.state.value.getMessageNodeByMessageId(original.id)!!
        val started = original.getTools().first().copy(startedAt = kotlin.time.Clock.System.now())
        session.update { it.upsertMessage(node.id, original.copy(parts = listOf(started, original.getTools()[1]))) }
        session.cancelPluginRuns()
        val stopped = session.state.value.currentMessages.last().getTools()
        assertEquals(started.startedAt, stopped.first().startedAt)
        assertTrue(stopped.first().output.isEmpty())
        assertTrue(stopped[1].isExecuted)
        assertFalse(session.respondToToolApproval(started.approvalId!!, ToolApprovalState.Approved))
    }

    private suspend fun CoroutineScope.open(source: Conversation) =
        ConversationSession(source.id, source, this, FakeConversationStore(source), {
        }).also { it.initialize({ source }) }

    @Test
    fun changedResourceCannotConsumeDecisionEvenWithSameToolSchema() = runBlocking {
        val session = open(Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start"))))
        val effects = mutableListOf<String>()
        execute(session, effects, offerTools = true, resource = "server-a")
        for (tool in session.state.value.currentMessages.last().getTools()) {
            assertTrue(session.respondToToolApproval(tool.approvalId!!, ToolApprovalState.Approved))
        }
        execute(session, effects, offerTools = false, resource = "server-b")
        assertTrue(effects.isEmpty())
        assertTrue("host.tool-version" in session.state.value.executionLedger().runs.values.single().state.gates)
    }

    private suspend fun execute(
        session: ConversationSession,
        effects: MutableList<String>,
        offerTools: Boolean,
        resource: String = "host-v1",
    ): SessionAgentRun {
        var calls = 0
        val provider = object : Provider<ProviderSetting> {
            override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
            override suspend fun streamText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams
            ): Flow<StreamChunk> = error("non-streaming")
            override suspend fun generateText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams
            ): TextGenerationResult {
                val parts = if (offerTools && calls++ == 0) {
                    listOf("A", "B").map {
                        UIMessagePart.Tool(it, it, "{}")
                    }
                } else {
                    listOf(UIMessagePart.Text("done"))
                }
                return TextGenerationResult("response", "model", UIMessage(role = MessageRole.ASSISTANT, parts = parts))
            }
        }
        val tools = listOf("A", "B").map { name ->
            Tool(name = name, description = name, executionVersion = resource, needsApproval = { true }, execute = {
                effects.add(name)
                listOf(UIMessagePart.Text("done"))
            })
        }
        val history = session.state.value.currentMessages
        val run = SessionAgentRun.open(session, history) { _, _ -> null }
        val nodeId = history.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
            ?.let { session.state.value.getMessageNodeByMessageId(it.id)?.id } ?: Uuid.random()
        runAgent(
            run.bind(AgentRequest(provider, ProviderSetting.OpenAI(), history, tools, stream = false), nodeId),
            run.hooks(hooks)
        ).collect(run::accept)
        return run
    }
}
