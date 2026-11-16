package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.plugins.builtin.BuiltinContextFeature
import me.rerere.rikkahub.plugins.builtin.CompactInput
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class SessionOperationDispatcherTest {
    @Test
    fun summaryAdoptionRetainsOriginalHistoryAndCommitsReceiptExactlyOnce() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(source)
        val session = session(source, store)
        var calls = 0
        val feature = BuiltinContextFeature { _, messages, _, _ ->
            calls++
            assertEquals(source.currentMessages, messages)
            assertEquals(AttemptStatus.RUNNING, store.persisted!!.executionLedger().operations.values.single().status)
            "summary"
        }
        val dispatcher = SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }
        val call = source.operationCall(feature.operation, feature.input())
        dispatcher.execute(session, call)
        assertEquals(source.currentMessages.first(), store.persisted!!.currentMessages.first())
        assertTrue(store.persisted!!.currentMessages.last().isContextCheckpoint)
        assertEquals(AttemptStatus.SETTLED, store.persisted!!.executionLedger().operations.getValue(call.id).status)
        dispatcher.execute(session, call)
        assertEquals(1, calls)
        assertEquals(2, store.persisted!!.messageNodes.size)
        assertTrue(store.persisted!!.executionLedger().runs.isEmpty())
    }

    @Test
    fun revokeOrSourceEditWhileSummaryRunsPreventsLateAdoption() = runBlocking {
        for (editSource in listOf(false, true)) {
            val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
            val store = FakeConversationStore(source)
            val session = session(source, store)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var enabled = true
            val feature = BuiltinContextFeature { _, _, _, _ ->
                entered.complete(Unit)
                release.await()
                "late summary"
            }
            val dispatcher = SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> enabled }
            val call = source.operationCall(feature.operation, feature.input())
            val work = async { runCatching { dispatcher.execute(session, call) } }
            entered.await()
            if (editSource) {
                session.update { it.copy(messageNodes = emptyList()) }
            } else {
                enabled = false
            }
            release.complete(Unit)
            assertTrue(work.await().isFailure)
            assertTrue(session.state.value.currentMessages.none { it.isContextCheckpoint })
            assertEquals(AttemptStatus.UNKNOWN, store.persisted!!.executionLedger().operations.getValue(call.id).status)
        }
    }

    @Test
    fun stopCancelsModelWorkAndDoesNotPublishSummary() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(source)
        val session = session(source, store)
        val entered = CompletableDeferred<Unit>()
        var drained = false
        val feature = BuiltinContextFeature { _, _, _, _ ->
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                drained = true
            }
        }
        val dispatcher = SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }
        val call = source.operationCall(feature.operation, feature.input(CompactInput(midTurn = true)))
        val work = launch { dispatcher.execute(session, call) }
        entered.await()
        work.cancelAndJoin()
        assertTrue(drained)
        assertEquals(source.currentMessages, session.state.value.currentMessages)
        assertEquals(AttemptStatus.UNKNOWN, store.persisted!!.executionLedger().operations.getValue(call.id).status)
        assertTrue(runCatching { dispatcher.execute(session, call) }.isFailure)
    }

    @Test
    fun disabledFeatureCannotStartManualOperationOrInvokeModel() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(source)
        val session = session(source, store)
        val feature = BuiltinContextFeature { _, _, _, _ -> error("disabled model call") }
        val dispatcher = SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> false }
        assertTrue(runCatching {
            dispatcher.execute(session, source.operationCall(feature.operation, feature.input()))
        }.isFailure)
        assertTrue(session.state.value.executionLedger().operations.isEmpty())
    }

    @Test
    fun reportOnlyOperationUsesSameDispatcherWithoutCreatingRunOrChangingMessages() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(source)
        val session = session(source, store)
        val operation =
            ConversationOperation("review/report", "Review", PackageLease("review", "v1", 1), emptySet()) {
                    snapshot,
                    _
                ->
                PreparedConversationOperation(snapshot.pluginSources(), execute = { context ->
                    assertNotNull(context.attemptId)
                    assertNull(context.call.run)
                    OperationResult(artifacts = listOf(ArtifactDraft("review", JsonPrimitive("findings"))))
                })
            }
        val result = SessionOperationDispatcher(listOf(operation)) { _, _ -> true }
            .execute(session, source.operationCall(operation, JsonNull))
        assertEquals("findings", result.artifacts.single().content.jsonPrimitive.content)
        assertEquals(source.currentMessages, store.persisted!!.currentMessages)
        assertTrue(store.persisted!!.executionLedger().runs.isEmpty())
    }

    @Test
    fun revocationDuringPreparationPreventsModelDispatch() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(source)
        val session = session(source, store)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var enabled = true
        var requests = 0
        val feature = BuiltinContextFeature { _, _, _, beforeEffect ->
            entered.complete(Unit)
            release.await()
            beforeEffect()
            requests++
            "summary"
        }
        val dispatcher = SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> enabled }
        val work =
            async {
                runCatching { dispatcher.execute(session, source.operationCall(feature.operation, feature.input())) }
            }
        entered.await()
        enabled = false
        release.complete(Unit)
        assertTrue(work.await().isFailure)
        assertEquals(0, requests)
    }

    @Test
    fun staleIdleCommandCannotRetargetAnotherSelectedBranch() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(source)
        val session = session(source, store)
        var calls = 0
        val feature = BuiltinContextFeature { _, _, _, _ ->
            calls++
            "summary"
        }
        val call = source.operationCall(feature.operation, feature.input())
        session.update {
            it.copy(messageNodes = emptyList()).updateCurrentMessages(listOf(UIMessage.user("other branch")))
        }
        assertTrue(runCatching {
            SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }.execute(session, call)
        }.isFailure)
        assertEquals(0, calls)
    }

    @Test
    fun tailAppendDuringSummaryKeepsTailAndAdoptsOnlyOriginalPrefix() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(source)
        val session = session(source, store)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val feature = BuiltinContextFeature { _, _, _, _ ->
            entered.complete(Unit)
            release.await()
            "summary"
        }
        val call = source.operationCall(feature.operation, feature.input())
        val work =
            async { SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }.execute(session, call) }
        entered.await()
        val tail = UIMessage.user("tail")
        session.update { it.updateCurrentMessages(it.currentMessages + tail) }
        release.complete(Unit)
        work.await()
        assertEquals(tail, store.persisted!!.currentMessages.last())
        assertTrue(store.persisted!!.currentMessages[1].isContextCheckpoint)
    }

    @Test
    fun timeoutAndRecoveredAttemptCannotUseDefaultFailureFallback() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(source)
        val session = session(source, store)
        val feature = BuiltinContextFeature { _, _, _, _ -> awaitCancellation() }
        val dispatcher = SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }
        val call = source.operationCall(feature.operation, feature.input())
        val outcome = dispatcher.dispatch(session, call, timeoutMillis = 20)
        assertTrue(outcome is OperationDispatch.NeedsReconciliation)
        assertFalse(dispatcher.mayResumeAfter(call, outcome))
        assertEquals(AttemptStatus.UNKNOWN, store.persisted!!.executionLedger().operations.getValue(call.id).status)
        val reopened = session(source, store)
        val recovered = dispatcher.dispatch(reopened, call)
        assertTrue(recovered is OperationDispatch.NeedsReconciliation)
        assertFalse(dispatcher.mayResumeAfter(call, recovered))
    }

    @Test
    fun preflightRejectionIsDurableAndStableButStorageFailureRequiresExplicitRetry() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(source)
        val session = session(source, store)
        var preparations = 0
        val operation =
            ConversationOperation("reject", "reject", PackageLease("test", "v1", 1), emptySet(), true) { _, _ ->
                preparations++
                error("unchanged invalid input")
            }
        val dispatcher = SessionOperationDispatcher(listOf(operation)) { _, _ -> true }
        val call = source.operationCall(operation, JsonNull)
        repeat(3) {
            val outcome = dispatcher.dispatch(session, call)
            assertTrue(outcome is OperationDispatch.Rejected)
            assertTrue(dispatcher.mayResumeAfter(call, outcome))
        }
        assertEquals(1, preparations)
        assertEquals(AttemptStatus.CANCELLED, store.persisted!!.executionLedger().operations.getValue(call.id).status)
        store.beforeWrite = { error("disk unavailable") }
        val blocked = dispatcher.dispatch(session, source.operationCall(operation, JsonNull, "next"))
        assertTrue(blocked is OperationDispatch.StorageBlocked)
        assertFalse(dispatcher.mayResumeAfter(call, blocked))
        assertFalse("next" in session.state.value.executionLedger().operations)
    }

    @Test
    fun everySummaryNetworkAttemptConsumesOperationAndOwningRunBudget() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(source)
        val session = session(source, store)
        val run = SessionAgentRun.open(session, source.currentMessages) { _, _ -> null }
        SessionRunStore(session).update(run.ref) { it.copy(state = it.state.copy(maxAttempts = 2)) }
        var dispatched = 0
        val feature = BuiltinContextFeature { _, _, _, beforeDispatch ->
            repeat(3) {
                beforeDispatch()
                dispatched++
            }
            "should not settle"
        }
        val call = session.state.value.operationCall(feature.operation, feature.input(), run = run.ref)
        val dispatcher = SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }
        val outcome = dispatcher.dispatch(session, call)
        assertTrue(outcome is OperationDispatch.NeedsReconciliation)
        assertEquals(2, dispatched)
        assertEquals(2, store.persisted!!.executionLedger().runs.getValue(run.ref.runId).state.attemptsUsed)
    }

    @Test
    fun settledReceiptCannotBeReadThroughRevokedAuthorityOrReusedIdentity() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val session = session(source, FakeConversationStore(source))
        var allowed = true
        val operation =
            ConversationOperation("report", "report", PackageLease("test", "v1", 1), emptySet()) { snapshot, _ ->
                PreparedConversationOperation(snapshot.pluginSources(), { OperationResult() })
            }
        val dispatcher = SessionOperationDispatcher(listOf(operation)) { _, _ -> allowed }
        val call = source.operationCall(operation, JsonNull)
        assertTrue(dispatcher.dispatch(session, call) is OperationDispatch.Settled)
        allowed = false
        assertTrue(dispatcher.dispatch(session, call) is OperationDispatch.NeedsReconciliation)
        allowed = true
        assertTrue(
            dispatcher.dispatch(
                session,
                call.copy(input = JsonPrimitive("different"))
            ) is OperationDispatch.NeedsReconciliation
        )
        assertEquals(call, session.state.value.executionLedger().operations.getValue(call.id).call)
    }

    private suspend fun CoroutineScope.session(source: Conversation, store: FakeConversationStore) =
        ConversationSession(source.id, source, this, store, {}).also { it.initialize({ store.persisted }) }
}
