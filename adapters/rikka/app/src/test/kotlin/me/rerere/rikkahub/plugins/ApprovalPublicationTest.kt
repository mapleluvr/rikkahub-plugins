package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import me.rerere.ai.agent.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.plugins.builtin.*
import me.rerere.rikkahub.service.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Start at real model output, and reopen every confirmed write rather than hand-building approved records. */
class ApprovalPublicationTest {
    private val provider = object : Provider<ProviderSetting> {
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
        ): TextGenerationResult = error("no dispatch")
    }

    @Test fun normalizedPublicationAndEveryPendingWriteAreRespondable() = runBlocking {
        for (normalize in listOf(false, true)) {
            val model = Model(modelId = "approval-fixture")
            val settings =
                Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))), chatModelId = model.id)
            val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("input")))
            val disk = FakeConversationStore(initial)
            val store = object : ConversationStore by disk {
                override suspend fun prepareWrite(): (Conversation) -> Conversation = if (normalize) {
                    { it.bindConfig(settings).fillModelSnapshots(settings) }
                } else {
                    { it }
                }
            }
            val session = ConversationSession(initial.id, initial, this, store, {}).also { it.initialize({ initial }) }
            var modelCalls = 0
            var effects = 0
            val asks = object : Provider<ProviderSetting> by provider {
                override suspend fun generateText(
                    providerSetting: ProviderSetting,
                    messages: List<UIMessage>,
                    params: TextGenerationParams
                ): TextGenerationResult {
                    val parts = if (modelCalls++ ==
                        0) {
                        listOf(UIMessagePart.Tool("call", "confirm", "{}"))
                    } else {
                        listOf(UIMessagePart.Text("done"))
                    }
                    return TextGenerationResult(
                        "response",
                        "model",
                        UIMessage(role = MessageRole.ASSISTANT, parts = parts)
                    )
                }
            }
            val tool =
                Tool(name = "confirm", description = "confirm", needsApproval = { true }, execute = {
                    effects++
                    listOf(UIMessagePart.Text("ok"))
                })
            val hooks = object : AgentHooks {
                override suspend fun prepareStep(
                    messages: List<UIMessage>
                ) = StepRequest(messages, TextGenerationParams(model))
            }
            suspend fun segment(): SessionAgentRun {
                val run = SessionAgentRun.open(session, session.state.value.currentMessages) { _, _ -> null }
                val plan = run.resumePlan()
                runAgent(
                    run.bind(
                        AgentRequest(asks, ProviderSetting.OpenAI(), plan.history, listOf(tool), stream = false),
                        plan
                    ),
                    run.hooks(hooks)
                ).collect { event ->
                    run.accept(event)
                    if (event is AgentEvent.MessageUpdated) {
                        session.update {
                            it.upsertMessage(plan.replyNodeId, event.message)
                        }
                    }
                }
                return run
            }
            val first = segment()
            assertEquals(1, modelCalls)
            assertEquals(0, effects)
            val pendingWrites = disk.writes.map {
                it.second
            }.filter { it.currentMessages.last().getTools().any { t -> t.isPending } }
            assertTrue(pendingWrites.isNotEmpty())
            for (cut in pendingWrites) {
                val record = cut.executionLedger().runs.getValue(first.ref.runId)
                assertEquals("ToolPreflight", record.boundary)
                val pending = cut.currentMessages.last().getTools().single()
                assertNotNull("No committed Pending may lack its public handle", pending.approvalId)
                assertTrue(TOOL_APPROVAL_GATE in record.state.gates)
                val reopened = ConversationSession(cut.id, cut, this, FakeConversationStore(cut), {
                }).also { it.initialize({ cut }) }
                assertTrue(reopened.respondToToolApproval(pending.approvalId!!, ToolApprovalState.Approved))
                assertTrue(reopened.state.value.executionLedger().runs.getValue(first.ref.runId).state.gates.isEmpty())
                // Old releases could persist Pending before publishing its handle. Never invent approval on load.
                val legacy = cut.copy(messageNodes = cut.messageNodes.map { node ->
                    node.copy(messages = node.messages.map { message ->
                        message.copy(parts = message.parts.map { part ->
                            if (part is UIMessagePart.Tool) part.copy(approvalId = null) else part
                        })
                    })
                }).withExecutionLedger(cut.executionLedger().copy(
                    runs = mapOf(first.ref.runId to record.copy(approvalRequests = emptyMap()))
                ))
                val incomplete = ConversationSession(legacy.id, legacy, this, FakeConversationStore(legacy), {})
                    .also { it.initialize({ legacy }) }
                val incompleteRun = incomplete.state.value.executionLedger().runs.getValue(first.ref.runId)
                assertTrue("host.approval-publication-incomplete" in incompleteRun.state.gates)
                assertFalse(incomplete.respondToToolApproval(pending.approvalId!!, ToolApprovalState.Approved))
                incomplete.cancelPluginRuns()
                assertTrue(incomplete.canStartNextTurn)
            }
            val id = session.state.value.currentMessages.last().getTools().single().approvalId!!
            assertTrue(session.respondToToolApproval(id, ToolApprovalState.Approved))
            assertEquals(first.ref, segment().ref)
            assertEquals(1, effects)
            assertEquals(2, modelCalls)
        }
    }

    @Test fun ambiguousRegistrationIsRejectedBeforeAnyModelCall() = runBlocking {
        var calls = 0
        val duplicate = listOf("left", "right").map {
            Tool(name = "same", description = it, executionVersion = it, execute = { error("no effect") })
        }
        val failure = runCatching {
            val request = AgentRequest(provider, ProviderSetting.OpenAI(), listOf(UIMessage.user("input")), duplicate)
            runAgent(request, object : AgentHooks {
                override suspend fun prepareStep(
                    messages: List<UIMessage>
                ): StepRequest {
                    calls++
                    error("no model preparation")
                }
            }).collect {}
        }.exceptionOrNull()
        assertEquals(0, calls)
        assertTrue(
            failure?.message.orEmpty(),
            failure is IllegalArgumentException && failure.message!!.contains("same")
        )
    }

    @Test fun revokedBeforeFirstPermitIsRejectedWithoutUnknownEffect() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("input")))
        val session = ConversationSession(initial.id, initial, this, FakeConversationStore(initial), {
        }).also { it.initialize({ initial }) }
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var enabled = true
        var calls = 0
        val feature =
            BuiltinContextFeature { _, _, _, admit ->
                ready.complete(Unit)
                release.await()
                admit()
                calls++
                "summary"
            }
        val call = initial.operationCall(feature.operation, feature.input())
        val dispatcher = SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> enabled }
        val work = async { dispatcher.dispatch(session, call) }
        ready.await()
        enabled = false
        release.complete(Unit)
        assertTrue(work.await() is OperationDispatch.Rejected)
        val receipt = session.state.value.executionLedger().operations.getValue(call.id)
        assertEquals(0, calls)
        assertEquals(0, receipt.effectAdmissions)
        assertEquals(AttemptStatus.CANCELLED, receipt.status)
    }
}
