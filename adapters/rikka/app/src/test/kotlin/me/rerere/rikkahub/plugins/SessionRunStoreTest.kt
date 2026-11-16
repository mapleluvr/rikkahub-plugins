package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.Gate
import dev.rikkahub.plugins.protocol.GateKind
import dev.rikkahub.plugins.protocol.RunCheckpoint
import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.RunState
import dev.rikkahub.plugins.protocol.RunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SessionRunStoreTest {
    @Test
    fun restartPreservesIndependentWaitsLogicalBudgetAndCursor() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("go")))
        val store = FakeConversationStore(initial)
        val runs = SessionRunStore(open(initial, store))
        val ref = RunRef(initial.id.toString(), "branch", "run", 1, "package")
        runs.create(RunCheckpoint(RunState(ref, 2, 3), "BeforeModel", 0))
        assertTrue(runs.admit(ref, 0, 0).allowed)
        runs.checkpoint(ref, "BeforeTool", 1, "tool")
        runs.addGate(ref, Gate("pause", GateKind.USER_PAUSE))
        runs.addGate(ref, Gate("approval", GateKind.TOOL_APPROVAL))

        val restored = SessionRunStore(open(initial, store))
        val remaining = restored.resolveGate(ref, "pause")
        assertEquals(setOf("approval"), remaining.state.gates.keys)
        assertEquals("tool", remaining.toolCallId)
        assertEquals(1, remaining.nextStep)
        assertFalse(restored.admit(ref, 1, remaining.state.frameRevision).allowed)
        val ready = restored.resolveGate(ref, "approval")
        val admitted = restored.admit(ref, 1, ready.state.frameRevision)
        assertTrue(admitted.allowed)
        val exhausted = restored.admit(ref, 2, admitted.state.frameRevision)
        assertFalse(exhausted.allowed)
        assertEquals(2, exhausted.state.stepsUsed)
        val cancelled = restored.cancel(ref)
        assertEquals(RunStatus.CANCELLED, cancelled.state.status)
        assertFalse(restored.admit(ref, 1, cancelled.state.frameRevision).allowed)
    }

    @Test
    fun messageAndCheckpointAreSavedTogetherAndWrongLeaseCannotResolveWaits() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("go")))
        val store = FakeConversationStore(initial)
        val runs = SessionRunStore(open(initial, store))
        val ref = RunRef(initial.id.toString(), "branch", "run", 1, "package")
        runs.create(RunCheckpoint(RunState(ref, 2, 3), "BeforeModel", 0))
        runs.addGate(ref, Gate("input", GateKind.PLUGIN_INPUT))
        runs.checkpoint(ref, "BeforeModel", 0) { it.copy(title = "waiting") }
        assertEquals("waiting", store.persisted!!.title)
        assertEquals(setOf("input"), store.persisted!!.executionLedger().runs.getValue(ref.runId).state.gates.keys)
        assertTrue(runCatching { runs.resolveGate(ref.copy(activationEpoch = 2), "input") }.isFailure)
        assertTrue(runCatching { runs.resolveGate(ref.copy(branchId = "other"), "input") }.isFailure)
        assertEquals(setOf("input"), store.persisted!!.executionLedger().runs.getValue(ref.runId).state.gates.keys)
    }

    private suspend fun CoroutineScope.open(initial: Conversation, store: FakeConversationStore) =
        ConversationSession(initial.id, initial, this, store, {}).also { it.initialize({ store.persisted }) }
}
