package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.agent.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.withToolApproval
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class SessionAgentRunTest {
    private val hooks = object : AgentHooks {
        override suspend fun prepareStep(
            messages: List<UIMessage>
        ) = StepRequest(messages, TextGenerationParams(Model()))
    }
    private fun request(provider: Provider<ProviderSetting>, messages: List<UIMessage>) =
        AgentRequest(provider, ProviderSetting.OpenAI(), messages, stream = false)

    @Test
    fun reopenedWaitBeforeFirstReplyPreservesRunPauseAndBudget() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start")))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val run = SessionAgentRun.open(session, initial.currentMessages) { _, _ -> null }
        SessionRunStore(session).addGate(run.ref, Gate("user", GateKind.USER_PAUSE))
        val provider = ScriptedProvider()
        val events = runAgent(
            run.bind(request(provider, initial.currentMessages), Uuid.random()),
            run.hooks(hooks)
        ).toList()
        run.accept(events.last())
        assertTrue(events.single() is AgentEvent.Yielded)
        val restored = open(initial, store)
        val resumed = SessionAgentRun.open(restored, restored.state.value.currentMessages) { _, _ -> null }
        val next = runAgent(
            resumed.bind(request(provider, restored.state.value.currentMessages), Uuid.random()),
            resumed.hooks(hooks)
        ).toList()
        assertEquals(run.ref, resumed.ref)
        assertTrue(next.single() is AgentEvent.Yielded)
        assertEquals(0, provider.calls)
        assertEquals(1, restored.state.value.executionLedger().runs.size)
    }

    @Test
    fun pauseBetweenResumePlanAndBindingWaitsWithoutCancellingTheRun() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start")))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val run = SessionAgentRun.open(session, initial.currentMessages) { _, _ -> null }
        val plan = run.resumePlan()
        SessionRunStore(session).addGate(run.ref, Gate("user", GateKind.USER_PAUSE))
        val provider = ScriptedProvider()
        val events = runAgent(run.bind(request(provider, plan.history), plan), run.hooks(hooks)).toList()
        events.forEach { run.accept(it) }
        assertTrue(events.single() is AgentEvent.Yielded)
        assertEquals(0, provider.calls)
        assertEquals(RunStatus.ACTIVE, session.state.value.executionLedger().runs.getValue(run.ref.runId).state.status)
    }

    @Test
    fun sourceEditDuringAsyncRequestPreparationPreventsProviderDispatch() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("original")))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val run = SessionAgentRun.open(session, initial.currentMessages) { _, _ -> null }
        val provider = ScriptedProvider()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val preparing = object : AgentHooks by hooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                entered.complete(Unit)
                release.await()
                return hooks.prepareStep(messages)
            }
        }
        val work =
            async {
                runAgent(
                    run.bind(request(provider, initial.currentMessages), Uuid.random()),
                    run.hooks(preparing)
                ).toList()
            }
        entered.await()
        session.update {
            it.updateCurrentMessages(
                listOf(initial.currentMessages[0].copy(parts = listOf(UIMessagePart.Text("edited"))))
            )
        }
        release.complete(Unit)
        assertTrue(work.await().last() is AgentEvent.Yielded)
        assertEquals(0, provider.calls)
    }

    @Test
    fun toolApprovalRecoveryUsesSameRunAndPersistsStartedStateBeforeEffect() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start")))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val nodeId = Uuid.random()
        val provider = ScriptedProvider().apply { firstTool = true }
        var effects = 0
        val tool = Tool(name = "effect", description = "effect", needsApproval = { true }, execute = {
            effects++
            assertNotNull(store.persisted!!.currentMessages.last().getTools().single().startedAt)
            listOf(UIMessagePart.Text("done"))
        })
        val run = SessionAgentRun.open(session, initial.currentMessages) { _, _ -> null }
        runAgent(
            run.bind(request(provider, initial.currentMessages).copy(tools = listOf(tool)), nodeId),
            run.hooks(hooks)
        ).collect(run::accept)
        assertEquals(1, provider.calls)
        assertEquals(0, effects)
        val restored = open(initial, store)
        val approvalId = restored.state.value.currentMessages.last().getTools().single().approvalId!!
        assertTrue(restored.respondToToolApproval(approvalId, ToolApprovalState.Approved))
        val resumed = SessionAgentRun.open(restored, restored.state.value.currentMessages) { _, _ -> null }
        runAgent(
            resumed.bind(request(provider, restored.state.value.currentMessages).copy(tools = listOf(tool)), nodeId),
            resumed.hooks(hooks)
        ).collect(resumed::accept)
        assertEquals(run.ref, resumed.ref)
        assertEquals(2, provider.calls)
        assertEquals(1, effects)
        val saved = store.persisted!!.executionLedger().runs.getValue(run.ref.runId)
        assertEquals(2, saved.state.stepsUsed)
        assertEquals(2, saved.state.attemptsUsed)
        assertEquals(RunStatus.COMPLETED, saved.state.status)
    }

    @Test
    fun operationCompletionResolvesOnlyItsOwnGateAndCannotRestartPausedModel() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start")))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val provider = ScriptedProvider().apply { firstTool = true }
        val operation =
            ConversationOperation("test/report", "Report", PackageLease("test", "v1", 1), emptySet()) { _, _ ->
                PreparedConversationOperation(emptyList(), execute = {
                    val owner = session.state.value.executionLedger().runs.values.single().state.ref
                    SessionRunStore(session).addGate(owner, Gate("user", GateKind.USER_PAUSE))
                    OperationResult()
                })
            }
        val run = SessionAgentRun.open(session, initial.currentMessages) { point, _ ->
            if (point.cursor.boundary ==
                AgentBoundary.AfterTools) {
                BoundaryOperation(operation, JsonNull, "operation")
            } else {
                null
            }
        }
        val tool = Tool(name = "effect", description = "effect", execute = { listOf(UIMessagePart.Text("done")) })
        val node = Uuid.random()
        runAgent(
            run.bind(request(provider, initial.currentMessages).copy(tools = listOf(tool)), node),
            run.hooks(hooks)
        ).collect(run::accept)
        assertNotNull(run.pending)
        SessionOperationDispatcher(listOf(operation)) { _, _ -> true }.execute(session, run.pending!!)
        run.operationFinished()
        val events = runAgent(
            run.bind(request(provider, session.state.value.currentMessages), node),
            run.hooks(hooks)
        ).toList()
        assertTrue(events.single() is AgentEvent.Yielded)
        assertEquals(1, provider.calls)
        assertEquals(setOf("user"), store.persisted!!.executionLedger().runs.getValue(run.ref.runId).state.gates.keys)
    }

    @Test
    fun operationWaitAndSettledAdoptionRecoverWithoutResettingRunOrRepeatingWork() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start")))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        var summaries = 0
        val feature = me.rerere.rikkahub.plugins.builtin.BuiltinContextFeature { _, _, _, _ ->
            summaries++
            "summary"
        }
        val provider = ScriptedProvider().apply { firstTool = true }
        val run = SessionAgentRun.open(session, initial.currentMessages) { point, _ ->
            if (point.cursor.boundary ==
                AgentBoundary.AfterTools) {
                BoundaryOperation(feature.operation, feature.input(), "operation")
            } else {
                null
            }
        }
        val tool = Tool(name = "effect", description = "effect", execute = { listOf(UIMessagePart.Text("done")) })
        runAgent(
            run.bind(request(provider, initial.currentMessages).copy(tools = listOf(tool)), Uuid.random()),
            run.hooks(hooks)
        ).collect(run::accept)
        val restored = open(initial, store)
        val pending = SessionAgentRun.open(restored, restored.state.value.currentMessages) { _, _ -> null }
        assertEquals(run.ref, pending.ref)
        assertEquals(run.pending, pending.pending)
        assertNotNull(pending.pending)
        SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }.execute(restored, pending.pending!!)
        // Simulate process death after adopting the summary but before clearing the dependency gate.
        val afterAdoption = open(initial, store)
        val recovered = SessionAgentRun.open(afterAdoption, afterAdoption.state.value.currentMessages) { _, _ -> null }
        assertEquals(run.ref, recovered.ref)
        assertNull(recovered.pending)
        assertEquals(1, summaries)
        assertTrue(afterAdoption.state.value.executionLedger().runs.getValue(run.ref.runId).state.gates.isEmpty())
        runAgent(
            recovered.bind(
                request(provider, afterAdoption.state.value.currentMessages).copy(tools = listOf(tool)),
                Uuid.random()
            ),
            recovered.hooks(hooks)
        ).collect(recovered::accept)
        val final = store.persisted!!.executionLedger().runs.getValue(run.ref.runId)
        assertEquals(2, final.state.stepsUsed)
        assertEquals(2, provider.calls)
    }

    @Test
    fun zeroAndPartialOutputWaitsPublishCompletionOnlyAfterResume() = runBlocking {
        for (partial in listOf(false, true)) {
            val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("user text")))
            val store = FakeConversationStore(initial)
            val session = open(initial, store)
            val run = SessionAgentRun.open(session, initial.currentMessages) { _, _ -> null }
            val provider = ScriptedProvider()
            if (!partial) SessionRunStore(session).addGate(run.ref, Gate("pause", GateKind.USER_PAUSE))
            var completions = 0
            val waitingHooks = object : AgentHooks by hooks {
                override suspend fun finishStep(message: UIMessage, newPartsFrom: Int): UIMessage {
                    if (partial) SessionRunStore(session).addGate(run.ref, Gate("pause", GateKind.USER_PAUSE))
                    return message
                }
            }
            runAgent(
                run.bind(request(provider, initial.currentMessages), Uuid.random()),
                run.hooks(waitingHooks)
            ).collect {
                run.accept(it)
                if (session.state.value.completedRunReply(run.ref) != null) completions++
            }
            assertEquals(0, completions)
            SessionRunStore(session).resolveGate(run.ref, "pause")
            val resumed = SessionAgentRun.open(session, session.state.value.currentMessages) { _, _ -> null }
            runAgent(
                resumed.bind(request(provider, session.state.value.currentMessages), Uuid.random()),
                resumed.hooks(hooks)
            ).collect {
                resumed.accept(it)
                if (it is AgentEvent.Finished && session.state.value.completedRunReply(run.ref) != null) completions++
            }
            assertEquals(1, completions)
            assertEquals("answer", session.state.value.completedRunReply(run.ref)!!.toText())
            assertEquals(1, provider.calls)
        }
    }

    @Test fun reopenedRunRetainsItsPackageSelectionIncludingAnEmptySelection() = runBlocking {
        val old = PackageLease("dev.example.plugin", "a".repeat(64), 1)
        val updated = old.copy(packageHash = "b".repeat(64), activationEpoch = 2)
        for (selection in listOf(emptyList(), listOf(old))) {
            val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start")))
            val store = FakeConversationStore(initial)
            val session = open(initial, store)
            val first = SessionAgentRun.open(session, initial.currentMessages, pluginLeases = selection) { _, _ -> null }
            SessionRunStore(session).addGate(first.ref, Gate("pause", GateKind.USER_PAUSE))
            val restored = open(initial, store)
            val resumed = SessionAgentRun.open(restored, restored.state.value.currentMessages,
                pluginLeases = listOf(updated)) { _, _ -> null }
            assertEquals(first.ref, resumed.ref)
            assertEquals(selection, resumed.pluginLeases)
            assertEquals(selection, store.persisted!!.executionLedger().runs.getValue(first.ref.runId).pluginLeases)
        }
    }

    private suspend fun CoroutineScope.open(initial: Conversation, store: FakeConversationStore) =
        ConversationSession(initial.id, initial, this, store, {}).also { it.initialize({ store.persisted }) }

    private class ScriptedProvider : Provider<ProviderSetting> {
        var calls = 0
        var firstTool = false
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
            val parts = if (firstTool &&
                calls == 1) {
                listOf(UIMessagePart.Tool(toolCallId = "call", toolName = "effect", input = "{}"))
            } else {
                listOf(UIMessagePart.Text("answer"))
            }
            return TextGenerationResult("id", "model", UIMessage(role = MessageRole.ASSISTANT, parts = parts))
        }
    }
}
