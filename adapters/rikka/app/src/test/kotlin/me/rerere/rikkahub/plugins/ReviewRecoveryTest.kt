package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.RunControl
import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.plugins.builtin.*
import me.rerere.rikkahub.service.*
import me.rerere.rikkahub.web.dto.toDto
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ReviewRecoveryTest {
    @Test
    fun exhaustedBudgetBeforeFirstDispatchIsDefiniteRejection() = runBlocking {
        val session = open(source())
        val run = SessionAgentRun.open(session, session.state.value.currentMessages) { _, _ -> null }
        SessionRunStore(session).update(run.ref) { it.copy(state = it.state.copy(maxAttempts = 1, attemptsUsed = 1)) }
        var requests = 0
        val feature = BuiltinContextFeature { _, _, _, admit ->
            admit()
            requests++
            "summary"
        }
        val call = session.state.value.operationCall(feature.operation, feature.input(), run = run.ref)
        val outcome = SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }.dispatch(session, call)
        assertEquals(0, requests)
        assertEquals(0, session.state.value.executionLedger().operations.getValue(call.id).modelAttempts)
        assertTrue(
            "an explicitly refused first request has no unknown external outcome",
            outcome is OperationDispatch.Rejected
        )
    }

    @Test
    fun independentPauseBlocksNonModelEffectsAndResumeExecutesOnce() = runBlocking {
        val session = open(source())
        val run = SessionAgentRun.open(session, session.state.value.currentMessages) { _, _ -> null }
        var effects = 0
        val operation =
            ConversationOperation("test/effect", "effect", PackageLease("test", "v1", 1), emptySet()) { c, _ ->
                PreparedConversationOperation(c.pluginSources(), execute = { invocation ->
                    invocation.beforeEffect()
                    effects++
                    OperationResult()
                })
            }
        val call = session.state.value.operationCall(operation, JsonNull, run = run.ref)
        SessionRunStore(session).update(run.ref) {
            it.copy(
                pendingOperation = call,
                state = RunControl.addGate(it.state, Gate(call.id, GateKind.OPERATION_DEPENDENCY))
            )
        }
        SessionRunStore(session).addGate(run.ref, Gate("user", GateKind.USER_PAUSE))
        val dispatcher = SessionOperationDispatcher(listOf(operation)) { _, _ -> true }
        val paused = dispatcher.dispatch(session, call)
        assertEquals(0, effects)
        assertFalse(dispatcher.mayResumeAfter(call, paused))
        SessionRunStore(session).resolveGate(run.ref, "user")
        assertTrue(dispatcher.dispatch(session, call) is OperationDispatch.Settled)
        assertEquals(1, effects)
    }

    @Test
    fun pauseDuringPreparationDefersEveryEffectButNeverReplaysAnAdmittedEffect() = runBlocking {
        for (modelRequest in listOf(false, true)) {
            for (effectBeforePause in listOf(false, true)) {
                val session = open(source())
                val run = SessionAgentRun.open(session, session.state.value.currentMessages) { _, _ -> null }
                val ready = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var effects = 0
                val operation = ConversationOperation(
                    "test/race", "Race", PackageLease("test", "v1", 1), emptySet()
                ) { c, _ ->
                    PreparedConversationOperation(c.pluginSources(), execute = { invocation ->
                        suspend fun effect() {
                            if (modelRequest) invocation.beforeModelRequest() else invocation.beforeEffect()
                            effects++
                        }
                        if (effectBeforePause) effect()
                        ready.complete(Unit)
                        release.await()
                        effect()
                        OperationResult()
                    })
                }
                val call = session.state.value.operationCall(operation, JsonNull, run = run.ref)
                val dispatcher = SessionOperationDispatcher(listOf(operation)) { _, _ -> true }
                val work = async { dispatcher.dispatch(session, call) }
                ready.await()
                SessionRunStore(session).addGate(run.ref, Gate("pause", GateKind.USER_PAUSE))
                release.complete(Unit)
                val outcome = work.await()
                assertEquals(if (effectBeforePause) 1 else 0, effects)
                val receipt = session.state.value.executionLedger().operations.getValue(call.id)
                assertEquals(if (effectBeforePause) 1 else 0, receipt.effectAdmissions)
                SessionRunStore(session).resolveGate(run.ref, "pause")
                if (effectBeforePause) {
                    assertTrue(outcome is OperationDispatch.NeedsReconciliation)
                    assertTrue(dispatcher.dispatch(session, call) is OperationDispatch.NeedsReconciliation)
                    assertEquals(1, effects)
                } else {
                    assertEquals(OperationDispatch.Waiting, outcome)
                    assertTrue(dispatcher.dispatch(session, call) is OperationDispatch.Settled)
                    assertEquals(1, effects)
                }
            }
        }
    }

    @Test
    fun truncatedForkAndWebUseSameSummaryVisibilityAsAndroid() = runBlocking {
        val a = UIMessage.user("A")
        val b = UIMessage.user("B")
        val initial = Conversation.ofId(
            Uuid.random(),
            messages = listOf(MessageNode(messages = listOf(a, b)), UIMessage.user("tail").toMessageNode())
        )
        val session = open(initial)
        val feature = BuiltinContextFeature { _, messages, _, _ ->
            assertEquals(listOf(a), messages)
            "only A"
        }
        SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }.execute(
            session,
            initial.operationCall(feature.operation, feature.input(CompactInput(keepRecentMessages = 1)))
        )
        val current = session.state.value
        val summary = current.currentMessages.single { it.isContextCheckpoint }
        val sibling = current.selectBranch(initial.messageNodes.first().id, 1)
        assertFalse(sibling.currentMessages.contains(summary))
        assertFalse(sibling.toDto().messages.any { it.messages[it.selectIndex].id == summary.id.toString() })
        val fork = current.fork(summary.id, emptySet()) { it }!!
        assertTrue(fork.currentMessages.any { it.isContextCheckpoint })
        assertFalse(fork.selectBranch(fork.messageNodes.first().id, 1).currentMessages.any { it.isContextCheckpoint })
    }

    @Test
    fun completedToolOutputChangesAutomaticRejectionIdentity() = runBlocking {
        val settings = Settings(enableAutoCompress = true, autoCompressThreshold = .8f)
        val model = Model(contextLength = 100)
        val tool = UIMessagePart.Tool("call", "effect", "{}", approvalState = ToolApprovalState.Approved)
        val reply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(tool),
            lastUsage = TokenUsage(promptTokens = 80),
            stepCount = 1
        )
        val initial = source().updateCurrentMessages(listOf(UIMessage.user("start"), reply))
        val session = open(initial)
        val feature = BuiltinContextFeature { _, _, _, _ -> "summary" }
        val dispatcher = SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }
        val id = feature.automaticCommand(initial, settings, model)!!
        assertTrue(
            dispatcher.dispatch(
                session,
                initial.operationCall(feature.operation, feature.input(), id)
            ) is OperationDispatch.Rejected
        )
        assertNull(feature.automaticCommand(session.state.value, settings, model))
        val changed = session.update {
            it.upsertMessage(
                initial.messageNodes.last().id,
                reply.copy(parts = listOf(tool.copy(output = listOf(UIMessagePart.Text("done")))))
            )
        }
        val nextId = feature.automaticCommand(changed, settings, model)
        assertNotNull(nextId)
        assertNotEquals(id, nextId)
        assertTrue(
            dispatcher.dispatch(
                session,
                changed.operationCall(feature.operation, feature.input(), nextId!!)
            ) is OperationDispatch.Settled
        )
        assertNull(feature.automaticCommand(session.state.value, settings, model))
    }

    private fun source() = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
    private suspend fun CoroutineScope.open(c: Conversation) =
        ConversationSession(c.id, c, this, FakeConversationStore(c), {}).also { it.initialize({ c }) }
}
