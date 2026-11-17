package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.agent.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.ConversationStore
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PersistentRunLifecycleTest {
    @Test
    fun draftPromotionAndOrdinaryInputRetainPausedRunIdentity() = runBlocking {
        for (empty in listOf(true, false)) {
            val original = Conversation.ofId(Uuid.random()).let {
                if (empty) it else it.updateCurrentMessages(listOf(UIMessage.user("original")))
            }
            val store = Store(original, empty)
            val first = ConversationSession(original.id, original, this, store, {})
            first.initialize({ if (empty) null else original }, { original })
            val run = SessionAgentRun.open(first, original.currentMessages) { _, _ -> null }
            SessionRunStore(first).addGate(run.ref, Gate("user", GateKind.USER_PAUSE))
            assertFalse(first.canStartNextTurn)
            // Direct promotion also must preserve identity, even if another trusted producer appended input.
            first.update { it.updateCurrentMessages(it.currentMessages + UIMessage.user("next")) }
            first.flush()
            val restarted = ConversationSession(original.id, original, this, store, {})
            restarted.initialize({ store.saved })
            val resumed = SessionAgentRun.open(restarted, restarted.state.value.currentMessages) { _, _ -> null }
            assertEquals(run.ref, resumed.ref)
            val saved = restarted.state.value.executionLedger().runs.values.single()
            assertEquals(RunStatus.ACTIVE, saved.state.status)
            assertEquals(setOf("user"), saved.state.gates.keys)
            assertFalse(restarted.canStartNextTurn)
        }
    }

    @Test
    fun loadingAnUnsettledOperationRecoversItBeforeSchedulingIsExposed() = runBlocking {
        val original = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
        val store = Store(original, false)
        val session = ConversationSession(original.id, original, this, store, {})
        session.initialize({ original })
        val run = SessionAgentRun.open(session, original.currentMessages) { _, _ -> null }
        val call =
            OperationCall(
                "work",
                "test/work",
                BranchRef(original.id.toString(), run.ref.branchId),
                JsonNull,
                PackageLease("test", "v1", 1),
                run = run.ref
            )
        SessionRunStore(session).update(run.ref) {
            it.copy(
                pendingOperation = call,
                state = dev.rikkahub.plugins.core.RunControl.addGate(
                    it.state,
                    Gate(call.id, GateKind.OPERATION_DEPENDENCY)
                )
            )
        }
        val operations = SessionOperationStore(session)
        operations.prepare(call, original.pluginSources())
        operations.start(call.id, "attempt")
        val restarted = ConversationSession(original.id, original, this, store, {})
        restarted.initialize({ store.saved })
        assertEquals(AttemptStatus.UNKNOWN, restarted.state.value.executionLedger().operations.getValue(call.id).status)
        assertEquals(AttemptStatus.UNKNOWN, store.saved.executionLedger().operations.getValue(call.id).status)
        assertFalse(restarted.canStartNextTurn)
        val reopened = SessionAgentRun.open(restarted, restarted.state.value.currentMessages) { _, _ -> null }
        assertEquals(run.ref, reopened.ref)
        assertEquals(call, reopened.pending)
    }

    @Test
    fun stoppedSuspendedRunDoesNotNeedAnInMemoryJob() = runBlocking {
        val original = Conversation.ofId(Uuid.random())
        val store = Store(original, true)
        val session = ConversationSession(original.id, original, this, store, {})
        session.initialize({ null }, { original })
        val run = SessionAgentRun.open(session, emptyList()) { _, _ -> null }
        SessionRunStore(session).addGate(run.ref, Gate("user", GateKind.USER_PAUSE))
        assertNull(session.getJob())
        session.cancelPluginRuns()
        assertEquals(RunStatus.CANCELLED, store.saved.executionLedger().runs.getValue(run.ref.runId).state.status)
        assertTrue(session.canStartNextTurn)
    }

    @Test
    fun v2EnvelopeIsPreservedAndActiveRunWaitsForExplicitReconciliation() = runBlocking {
        val original = Conversation.ofId(Uuid.random())
        val ref = RunRef(original.id.toString(), "old", "old-run")
        val old =
            ExecutionLedger(
                version = 2,
                runs = mapOf(ref.runId to RunCheckpoint(RunState(ref, 5, 10), "BeforeModel", 0))
            )
        val source = original.withExecutionLedger(old).copy(pluginDraft = true)
        val store = Store(source, true)
        val session = ConversationSession(source.id, source, this, store, {})
        session.initialize({ source })
        assertEquals(3, session.state.value.executionLedger().version)
        assertEquals(
            source.pluginState["host.execution"],
            session.state.value.pluginState["host.execution.previous.v2"]
        )
        assertTrue("host.legacy-recovery" in session.state.value.executionLedger().runs.getValue(ref.runId).state.gates)
        assertFalse(session.canStartNextTurn)
    }

    @Test
    fun missingRunInputOrCursorSurvivesColdLoadAsExplicitReconciliation() = runBlocking {
        for (missingCursor in listOf(false, true)) {
            val user = UIMessage.user("surviving input")
            val original = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(user))
            val ref = RunRef(original.id.toString(), user.id.toString(), "damaged")
            val missing = Uuid.random().toString()
            val checkpoint = RunCheckpoint(
                RunState(ref, 5, 10, gates = mapOf("user" to Gate("user", GateKind.USER_PAUSE))),
                "BeforeModel",
                0,
                inputIds = if (missingCursor) listOf(user.id.toString()) else listOf(user.id.toString(), missing),
                messageId = missing.takeIf { missingCursor },
            )
            val source = original.withExecutionLedger(ExecutionLedger(runs = mapOf(ref.runId to checkpoint)))
            val store = Store(source, false)
            val session = ConversationSession(source.id, source, this, store, {})
            session.initialize({ source })
            val recovered = session.state.value.executionLedger().runs.getValue(ref.runId)
            assertTrue(
                "Missing input must have a durable reconciliation reason",
                "host.history-missing" in recovered.state.gates
            )
            assertEquals(checkpoint.inputIds, recovered.inputIds)
            assertEquals(checkpoint.messageId, recovered.messageId)
            assertTrue("user" in recovered.state.gates)
            assertEquals(session.state.value, store.saved)
            assertFalse(session.canStartNextTurn)
            session.cancelPluginRuns()
            assertTrue(session.canStartNextTurn)
        }
    }

    private class Store(var saved: Conversation, var draft: Boolean) : ConversationStore {
        override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
        override suspend fun write(previous: Conversation?, current: Conversation) {
            saved = current
            draft = false
        }
        override suspend fun writeDraft(
            current: Conversation
        ) {
            saved = current.copy(pluginDraft = true)
            draft = true
        }
    }
}
