package me.rerere.rikkahub.plugins

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.agent.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.FakeMemoryDAO
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryTargetAdmissionTest {
    @Test fun changingToGlobalMemoryWhileBatchAwaitsApprovalCannotRedirectTheWrite() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDAO())
        val assistant = Assistant(useGlobalMemory = false)
        val global = assistant.copy(useGlobalMemory = true)
        val input = buildJsonObject {
            put("path", "/fixture.md")
            put("content", "private fact")
            put("if_version", "new")
        }.toString()
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
                val parts = if (calls++ ==
                    0) {
                    listOf(
                        UIMessagePart.Tool("memory", "memory_write", input),
                        UIMessagePart.Tool("ask", "confirm", "{}")
                    )
                } else {
                    listOf(UIMessagePart.Text("done"))
                }
                return TextGenerationResult("response", "model", UIMessage(role = MessageRole.ASSISTANT, parts = parts))
            }
        }
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("remember")))
        val session = ConversationSession(initial.id, initial, this, FakeConversationStore(initial), {
        }).also { it.initialize({ initial }) }
        suspend fun segment(owner: Assistant): SessionAgentRun {
            val tools =
                buildMemoryTools(repository, owner.memoryId) +
                    Tool(name = "confirm", description = "confirm", needsApproval = {
                        true
                    }, execute = { listOf(UIMessagePart.Text("ok")) })
            val run = SessionAgentRun.open(session, session.state.value.currentMessages) { _, _ -> null }
            val plan = run.resumePlan()
            val hooks = object : AgentHooks {
                override suspend fun prepareStep(
                    messages: List<UIMessage>
                ) = StepRequest(messages, TextGenerationParams(Model()))
            }
            runAgent(
                run.bind(AgentRequest(provider, ProviderSetting.OpenAI(), plan.history, tools, stream = false), plan),
                run.hooks(hooks)
            ).collect(run::accept)
            return run
        }
        val run = segment(assistant)
        val pending = session.state.value.currentMessages.last().getTools().single { it.isPending }
        assertTrue(session.respondToToolApproval(pending.approvalId!!, ToolApprovalState.Approved))
        assertEquals(run.ref, segment(global).ref)
        assertNull(repository.getFile(assistant.memoryId, "/fixture.md"))
        assertNull(
            "old batch must not write to a newly selected global database",
            repository.getFile(global.memoryId, "/fixture.md")
        )
        assertEquals(1, calls)
        assertTrue(
            "host.tool-version" in session.state.value.executionLedger().runs.getValue(run.ref.runId).state.gates
        )
    }
}
