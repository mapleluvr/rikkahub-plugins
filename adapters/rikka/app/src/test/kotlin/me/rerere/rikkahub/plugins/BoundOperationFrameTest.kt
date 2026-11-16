package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.plugins.builtin.BuiltinContextFeature
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class BoundOperationFrameTest {
    @Test
    fun runCommandKeepsItsSourceWhileUserBrowsesSibling() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("A")))
        val session = open(source)
        val run = SessionAgentRun.open(session, source.currentMessages) { _, _ -> null }
        val seen = mutableListOf<String>()
        val feature = BuiltinContextFeature { _, messages, _, _ ->
            seen.add(messages.single().toText())
            "summary A"
        }
        val call = source.operationCall(feature.operation, feature.input(), run = run.ref)
        session.update { it.editMessage(source.currentMessages.single().id, listOf(UIMessagePart.Text("B"))) }
        SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }.execute(session, call)
        assertEquals(listOf("A"), seen)
        assertEquals(listOf("B"), session.state.value.currentMessages.map { it.toText() })
        session.update { it.selectBranch(source.messageNodes.single().id, 0) }
        assertEquals(listOf("A", "summary A"), session.state.value.currentMessages.map { it.toText() })
    }

    @Test
    fun revokedCallerCannotBorrowHandlersCapabilities() = runBlocking {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val session = open(source)
        var requests = 0
        val feature = BuiltinContextFeature { _, _, _, _ ->
            requests++
            "summary"
        }
        val caller = PackageLease("revoked", "v1", 1)
        val call = source.operationCall(feature.operation, feature.input(), caller = caller)
        assertTrue(runCatching {
            SessionOperationDispatcher(listOf(feature.operation)) { lease, _ -> lease != caller }.execute(session, call)
        }.isFailure)
        assertEquals(0, requests)
    }

    @Test
    fun reductionDoesNotLeakToSiblingOrBreakShorterReaderFrame() = runBlocking {
        val x = UIMessage.user("common")
        val a = UIMessage.user("A")
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(x, a))
        val session = open(source)
        val operation =
            ConversationOperation("reduce", "reduce", PackageLease("test", "v1", 1), setOf("context.write")) { _, _ ->
                PreparedConversationOperation(listOf(x.sourceStamp()), execute = {
                    OperationResult(projection = ProjectionPlan(setOf(x.id.toString())))
                })
            }
        SessionOperationDispatcher(listOf(operation)) { _, _ ->
            true
        }.execute(session, source.operationCall(operation, JsonNull))
        assertEquals(listOf(a), session.state.value.projectedContext(source.currentMessages))
        session.update { it.editMessage(a.id, listOf(UIMessagePart.Text("B"))) }
        val sibling = session.state.value
        assertEquals(sibling.currentMessages, sibling.projectedContext(sibling.currentMessages))
        assertEquals(listOf(x), sibling.projectedContext(listOf(x)))
    }

    @Test
    fun sameIdSourceEditBeforeExecutionInvalidatesCreatedCommand() = runBlocking {
        val message = UIMessage.user("summary source").copy(isContextCheckpoint = true)
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(message, UIMessage.user("tail")))
        val session = open(source)
        var requests = 0
        val feature = BuiltinContextFeature { _, _, _, _ ->
            requests++
            "summary"
        }
        val call = source.operationCall(feature.operation, feature.input())
        session.update { it.editMessage(message.id, listOf(UIMessagePart.Text("changed"))) }
        assertTrue(runCatching {
            SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }.execute(session, call)
        }.isFailure)
        assertEquals(0, requests)
    }

    @Test
    fun forkInheritsVisibleHistoryAndLineageButNoExecutableState() = runBlocking {
        val a = UIMessage.user("A")
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(a))
        val session = open(source)
        val run = SessionAgentRun.open(session, source.currentMessages) { _, _ -> null }
        val feature = BuiltinContextFeature { _, _, _, _ -> "summary A" }
        SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }.execute(
            session,
            session.state.value.operationCall(feature.operation, feature.input(), run = run.ref)
        )
        session.update { it.editMessage(a.id, listOf(UIMessagePart.Text("B"))) }
        val tail = UIMessage.user("tail B")
        session.update { it.copy(messageNodes = it.messageNodes + tail.toMessageNode()) }
        val forkB = session.state.value.fork(tail.id, emptySet()) { it }!!
        assertEquals(listOf("B", "tail B"), forkB.currentMessages.map { it.toText() })
        session.update { it.selectBranch(source.messageNodes.single().id, 0) }
        val summary = session.state.value.currentMessages.first { it.isContextCheckpoint }
        val forkA = session.state.value.fork(summary.id, emptySet()) { it }!!
        assertTrue(forkA.executionLedger().runs.isEmpty())
        assertTrue(forkA.executionLedger().operations.isEmpty())
        assertEquals(listOf("A", "summary A"), forkA.currentMessages.map { it.toText() })
        val other = forkA.selectBranch(forkA.messageNodes.first().id, 1)
        assertEquals(listOf("B"), other.currentMessages.map { it.toText() })
    }

    private suspend fun CoroutineScope.open(source: Conversation) =
        ConversationSession(source.id, source, this, FakeConversationStore(source), {
        }).also { it.initialize({ source }) }
}
