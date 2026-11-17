package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.ModelAttemptPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import me.rerere.ai.agent.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.editMessage
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PersistedExecutionBoundaryTest {
    private val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start")))
    private val hooks = object : AgentHooks {
        override suspend fun prepareStep(
            messages: List<UIMessage>
        ) = StepRequest(messages, TextGenerationParams(Model()))
    }

    @Test
    fun reopenEveryToolWritePreservesResultsAndNeverReplaysUnknownEffects() = runBlocking {
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val run = controller(session)
        val provider = ProviderProbe()
        val effects = mutableListOf<String>()
        val tools = listOf("one", "two").map { name ->
            Tool(name = name, description = name, execute = {
                effects.add(name)
                listOf(UIMessagePart.Text("result:$name"))
            })
        }
        val node = Uuid.random()
        runAgent(
            run.bind(request(provider, initial.currentMessages, tools), node),
            run.hooks(hooks)
        ).collect(run::accept)
        assertEquals(listOf("one", "two"), effects)
        val cuts = store.writes.map { it.second }.filter { snapshot ->
            snapshot.currentMessages.lastOrNull()?.getTools()?.size == 2 &&
                snapshot.currentMessages.last().parts.lastOrNull() is UIMessagePart.Tool &&
                snapshot.executionLedger().runs.values.single().state.status ==
                dev.rikkahub.plugins.protocol.RunStatus.ACTIVE
        }.distinct()
        assertTrue(cuts.size >= 4)
        assertTrue("Exercise a reservation before final dispatch", cuts.any {
            it.executionLedger().runs.values.single().modelAttempt?.phase == ModelAttemptPhase.RESERVED
        })
        assertTrue("Exercise an unconfirmed dispatch intent too", cuts.any {
            it.executionLedger().runs.values.single().modelAttempt?.phase == ModelAttemptPhase.DISPATCH_INTENT
        })
        for (cut in cuts) {
            val persistedTools = cut.currentMessages.last().getTools()
            val checkpoint = cut.executionLedger().runs.getValue(run.ref.runId)
            val attempt = checkpoint.modelAttempt
            val unknownModel = if (attempt == null) {
                checkpoint.state.stepsUsed > checkpoint.nextStep // Conservative legacy checkpoint.
            } else {
                attempt.step >= checkpoint.nextStep &&
                    (attempt.phase == ModelAttemptPhase.DISPATCH_INTENT || attempt.priorDispatchUnconfirmed)
            }
            val unknown = persistedTools.any { it.startedAt != null && !it.isExecuted } || unknownModel
            val restored = open(cut, FakeConversationStore(cut))
            val resumed = controller(restored)
            val nextProvider = ProviderProbe(offerTools = false)
            val nextEffects = mutableListOf<String>()
            val nextTools = tools.map { tool ->
                tool.copy(execute = {
                    nextEffects.add(tool.name)
                    listOf(UIMessagePart.Text("result:${tool.name}"))
                })
            }
            val events = runAgent(
                resumed.bind(request(nextProvider, cut.currentMessages, nextTools), node),
                resumed.hooks(hooks)
            ).toList()
            assertEquals(run.ref, resumed.ref)
            if (unknown) {
                assertTrue("Expected wait at ${checkpoint.boundary}: $events", events.last() is AgentEvent.Yielded)
                assertEquals(0, nextProvider.calls)
                assertTrue(nextEffects.isEmpty())
            } else {
                assertTrue(
                    "Expected continuation at ${checkpoint.boundary}: $events",
                    events.last() is AgentEvent.Finished
                )
                assertEquals(persistedTools.filter { !it.isExecuted }.map { it.toolName }, nextEffects)
                assertEquals(1, nextProvider.calls)
                val finished = (events.last() as AgentEvent.Finished).message
                assertEquals(listOf("result:one", "result:two"), finished.getTools().map {
                    it.output.single().let { part -> (part as UIMessagePart.Text).text }
                })
            }
        }
    }

    @Test
    fun editBeforePrepareCannotPairOldRequestContentsWithNewFingerprint() = runBlocking {
        val summary = UIMessage.user("old summary").copy(isContextCheckpoint = true)
        val source = initial.updateCurrentMessages(listOf(summary, UIMessage.user("next")))
        val session = open(source, FakeConversationStore(source))
        val run = controller(session)
        session.update { it.editMessage(summary.id, listOf(UIMessagePart.Text("new summary"))) }
        val provider = ProviderProbe(offerTools = false)
        val events = runAgent(
            run.bind(request(provider, source.currentMessages), Uuid.random()),
            run.hooks(hooks)
        ).toList()
        assertTrue(events.last() is AgentEvent.Yielded)
        assertEquals(0, provider.calls)
        assertEquals("new summary", session.state.value.currentMessages.first().toText())
    }

    @Test
    fun changedToolImplementationCannotConsumeAnApprovedBatch() = runBlocking {
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val run = controller(session)
        val provider = ProviderProbe()
        var effects = 0
        val tools = listOf("one", "two").map { name ->
            Tool(name = name, description = name, executionVersion = "v1", execute = {
                effects++
                listOf(UIMessagePart.Text("ok"))
            })
        }
        runAgent(
            run.bind(request(provider, initial.currentMessages, tools), Uuid.random()),
            run.hooks(hooks)
        ).collect(run::accept)
        val cut = store.writes.map { it.second }.first {
            it.executionLedger().runs.values.single().boundary ==
                "ToolPreflight"
        }
        val restored = open(cut, FakeConversationStore(cut))
        val resumed = controller(restored)
        val changed = tools.map { it.copy(executionVersion = "v2") }
        val before = effects
        val events = runAgent(
            resumed.bind(request(provider, cut.currentMessages, changed), Uuid.random()),
            resumed.hooks(hooks)
        ).toList()
        assertTrue(events.last() is AgentEvent.Yielded)
        assertEquals(before, effects)
    }

    @Test
    fun approvalChangedDuringStartPreparationYieldsWithoutConsumingPermit() = runBlocking {
        val storage = FakeConversationStore(initial)
        lateinit var session: ConversationSession
        var revoked = false
        val store = object : me.rerere.rikkahub.service.ConversationStore by storage {
            override suspend fun prepareWrite(): (Conversation) -> Conversation {
                val saved = session.state.value.executionLedger().runs.values.singleOrNull()
                if (!revoked && saved?.boundary == "BeforeTool") {
                    revoked = true
                    session.update { current ->
                        current.copy(messageNodes = current.messageNodes.map { node ->
                            node.copy(messages = node.messages.map { message ->
                                message.copy(parts = message.parts.map { part ->
                                    if (part is UIMessagePart.Tool && part.toolCallId == "one") {
                                        part.copy(approvalState = ToolApprovalState.Pending)
                                    } else {
                                        part
                                    }
                                })
                            })
                        })
                    }
                }
                return { it }
            }
        }
        session = ConversationSession(initial.id, initial, this, store, {}).also { it.initialize({ initial }) }
        val run = controller(session)
        var effects = 0
        val tools = listOf("one", "two").map { name ->
            Tool(name = name, description = name, execute = {
                effects++
                listOf(UIMessagePart.Text("ok"))
            })
        }
        val events = runAgent(
            run.bind(request(ProviderProbe(), initial.currentMessages, tools), Uuid.random()),
            run.hooks(hooks)
        ).toList()
        assertTrue(revoked)
        assertTrue(events.last() is AgentEvent.Yielded)
        assertEquals(0, effects)
        val saved = session.state.value.executionLedger().runs.getValue(run.ref.runId)
        assertTrue(saved.consumedToolPermits.isEmpty())
        assertTrue(saved.state.gates.isNotEmpty())
        assertTrue(session.state.value.currentMessages.last().getTools().all { it.startedAt == null })
    }

    private fun request(provider: ProviderProbe, messages: List<UIMessage>, tools: List<Tool> = emptyList()) =
        AgentRequest(provider, ProviderSetting.OpenAI(), messages, tools = tools, stream = false)
    private suspend fun controller(
        session: ConversationSession
    ) = SessionAgentRun.open(session, session.state.value.currentMessages) { _, _ -> null }
    private suspend fun CoroutineScope.open(source: Conversation, store: FakeConversationStore) =
        ConversationSession(source.id, source, this, store, {}).also { it.initialize({ store.persisted }) }

    private class ProviderProbe(private val offerTools: Boolean = true) : Provider<ProviderSetting> {
        var calls = 0
        override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams
        ): Flow<StreamChunk> = error("nonstream")
        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams
        ): TextGenerationResult {
            calls++
            val parts = if (offerTools &&
                calls == 1) {
                listOf("one", "two").map { UIMessagePart.Tool(toolCallId = it, toolName = it, input = "{}") }
            } else {
                listOf(UIMessagePart.Text("answer"))
            }
            return TextGenerationResult("id", "model", UIMessage(role = MessageRole.ASSISTANT, parts = parts))
        }
    }
}
