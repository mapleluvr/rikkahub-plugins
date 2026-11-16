package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.ConversationStore
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConfirmedSessionCommitTest {
    private val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
    private val call =
        OperationCall(
            "settle",
            "test/report",
            BranchRef(initial.id.toString(), "branch"),
            JsonNull,
            PackageLease("test", "v1", 1)
        )

    @Test
    fun failedSettlementNeverPublishesAndOrdinaryFlushCannotAdoptIt() = runBlocking {
        val store = Store(initial)
        val session = open(store)
        val operations = SessionOperationStore(session)
        operations.prepare(call, initial.pluginSources())
        operations.start(call.id, "attempt")
        val observed = mutableListOf<Conversation>()
        val observer = launch(Dispatchers.Unconfined) { session.state.collect { observed.add(it) } }
        store.beforeWrite = { if (it.title == "uncommitted result") error("settlement disk failure") }
        assertTrue(runCatching {
            operations.settle(call, "attempt", OperationResult()) { it.copy(title = "uncommitted result") }
        }.isFailure)
        assertTrue(observed.none { it.title == "uncommitted result" })
        assertEquals(AttemptStatus.RUNNING, session.state.value.executionLedger().operations.getValue(call.id).status)
        session.update { it.copy(messageNodes = emptyList()) }
        store.beforeWrite = {}
        session.flush()
        assertEquals("", store.saved.title)
        assertEquals(AttemptStatus.RUNNING, store.saved.executionLedger().operations.getValue(call.id).status)
        observer.cancelAndJoin()
    }

    @Test
    fun concurrentEditWaitsForConfirmedCommitAndIsNotOverwritten() = runBlocking {
        val store = Store(initial)
        val session = open(store)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeWrite = {
            entered.complete(Unit)
            release.await()
        }
        val commit = async { session.commitExtension { it.copy(title = "committed") } }
        entered.await()
        assertEquals("", session.state.value.title)
        val edit = async(start = CoroutineStart.UNDISPATCHED) { session.update { it.copy(isPinned = true) } }
        assertFalse(edit.isCompleted)
        release.complete(Unit)
        commit.await()
        edit.await()
        assertEquals("committed", session.state.value.title)
        assertTrue(session.state.value.isPinned)
        session.flush()
        assertEquals(session.state.value, store.saved)
    }

    @Test
    fun failedDraftCommitDoesNotLeaveAWaitForOrdinaryFlushToPublish() = runBlocking {
        val draft = Conversation.ofId(Uuid.random())
        val store = Store(draft)
        val session = ConversationSession(draft.id, draft, this, store, {})
        session.initialize({ null }, { draft })
        store.beforeWrite = { error("draft disk failure") }
        val ref = RunRef(draft.id.toString(), "draft", "run")
        assertTrue(runCatching {
            SessionRunStore(session).create(RunCheckpoint(RunState(ref, 5, 10), "BeforeModel", 0))
        }.isFailure)
        assertTrue(session.state.value.pluginState.isEmpty())
        store.beforeWrite = {}
        session.flush()
        assertTrue(store.saved.pluginState.isEmpty())
    }

    private suspend fun CoroutineScope.open(store: Store) =
        ConversationSession(initial.id, initial, this, store, {}).also { it.initialize({ store.saved }) }

    private class Store(var saved: Conversation) : ConversationStore {
        var beforeWrite: suspend (Conversation) -> Unit = {}
        override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
        override suspend fun write(previous: Conversation?, current: Conversation) {
            beforeWrite(current)
            saved =
                current
        }
        override suspend fun writeDraft(current: Conversation) {
            beforeWrite(current)
            saved = current
        }
    }
}
