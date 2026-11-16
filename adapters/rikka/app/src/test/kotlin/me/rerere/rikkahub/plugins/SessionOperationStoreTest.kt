package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.AttemptStatus
import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.BranchRef
import dev.rikkahub.plugins.protocol.PackageLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.selectBranch
import me.rerere.rikkahub.service.ConversationStore
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SessionOperationStoreTest {
    @Test
    fun resultAndReceiptPersistTogetherAndDuplicateDeliveryDoesNotApplyTwice() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val operations = SessionOperationStore(session)
        val call = call(initial)
        operations.prepare(call, initial.pluginSources())
        operations.start(call.id, "attempt")
        operations.settle(call, "attempt", OperationResult()) { it.copy(title = "adopted") }
        assertEquals("adopted", store.persisted!!.title)
        val restored = open(initial, store)
        assertEquals(AttemptStatus.SETTLED, restored.state.value.executionLedger().operations.getValue(call.id).status)
        SessionOperationStore(restored).settle(call, "attempt", OperationResult()) { error("duplicate adoption") }
    }

    @Test
    fun committedAttemptIsUnknownAfterProcessRecoveryAndCannotExecuteAgain() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(initial)
        val operations = SessionOperationStore(open(initial, store))
        val call = call(initial)
        operations.prepare(call, initial.pluginSources())
        operations.start(call.id, "attempt")
        val recovered = SessionOperationStore(open(initial, store))
        recovered.recover()
        assertTrue(runCatching { recovered.start(call.id, "new-attempt") }.isFailure)
        assertEquals(AttemptStatus.UNKNOWN, store.persisted!!.executionLedger().operations.getValue(call.id).status)
    }

    @Test
    fun viewingAnotherBranchDoesNotInvalidateSourcesButDeletionDoes() = runBlocking {
        val first = UIMessage.user("source")
        val node = MessageNode(messages = listOf(first, UIMessage.user("alternate")))
        val initial = Conversation.ofId(Uuid.random(), messages = listOf(node))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val operations = SessionOperationStore(session)
        val call = call(initial)
        operations.prepare(call, initial.pluginSources().filter { it.id == first.id.toString() })
        operations.start(call.id, "attempt")
        session.update { it.selectBranch(node.id, 1) }
        operations.settle(call, "attempt", OperationResult())
        val secondCall = call.copy(id = "second")
        operations.prepare(secondCall, initial.pluginSources().filter { it.id == first.id.toString() })
        operations.start(secondCall.id, "second-attempt")
        session.update { it.copy(messageNodes = listOf(node.copy(messages = listOf(node.messages[1])))) }
        assertTrue(runCatching { operations.settle(secondCall, "second-attempt", OperationResult()) }.isFailure)
    }

    @Test
    fun failedCommitDoesNotReturnPermissionToStartAnEffectAndRetryFlushesReceipt() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val operations = SessionOperationStore(session)
        val call = call(initial)
        store.beforeWrite = { error("disk unavailable") }
        assertTrue(runCatching { operations.prepare(call, initial.pluginSources()) }.isFailure)
        assertTrue(store.persisted!!.executionLedger().operations.isEmpty())
        store.beforeWrite = {}
        operations.prepare(call, initial.pluginSources())
        assertEquals(AttemptStatus.PREPARED, store.persisted!!.executionLedger().operations.getValue(call.id).status)
    }

    @Test
    fun sourceValidationRunsAgainAfterSuspendingPreparation() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saves = 0
        val store = object : ConversationStore {
            override suspend fun prepareWrite(): (Conversation) -> Conversation {
                entered.complete(Unit)
                release.await()
                return { it }
            }
            override suspend fun write(previous: Conversation?, current: Conversation) { saves++ }
        }
        val session = ConversationSession(initial.id, initial, this, store, {})
        session.initialize({ initial })
        val pending = async {
            runCatching { SessionOperationStore(session).prepare(call(initial), initial.pluginSources()) }
        }
        entered.await()
        session.update { it.copy(messageNodes = emptyList()) }
        release.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertTrue(session.state.value.executionLedger().operations.isEmpty())
        assertEquals(0, saves)
    }

    @Test
    fun deletedSessionRejectsCallbacksFromItsPreviousIncarnation() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val old = SessionOperationStore(session)
        session.replace(Conversation.ofId(initial.id), false) { store.persisted = null }
        assertTrue(runCatching { old.prepare(call(initial), emptyList()) }.isFailure)
        assertTrue(session.state.value.pluginState.isEmpty())
    }

    private suspend fun CoroutineScope.open(initial: Conversation, store: FakeConversationStore): ConversationSession =
        ConversationSession(initial.id, initial, this, store, {}).also { it.initialize({ store.persisted }) }

    private fun call(conversation: Conversation) = OperationCall(
        "command",
        "test/report",
        BranchRef(conversation.id.toString(), "branch"),
        JsonNull,
        PackageLease("test", "package", 1)
    )
}
