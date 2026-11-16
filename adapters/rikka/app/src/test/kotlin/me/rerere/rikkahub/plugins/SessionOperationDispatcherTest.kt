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

    private suspend fun CoroutineScope.session(source: Conversation, store: FakeConversationStore) =
        ConversationSession(source.id, source, this, store, {}).also { it.initialize({ store.persisted }) }
}
