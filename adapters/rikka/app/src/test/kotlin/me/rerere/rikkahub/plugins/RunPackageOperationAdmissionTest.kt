package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.AttemptStatus
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.PackageLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class RunPackageOperationAdmissionTest {
    @Test fun revocationBeforeEffectBlocksOperationEffectsAndPersistsPackageFault() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("input")))
        val store = FakeConversationStore(initial)
        val session = ConversationSession(initial.id, initial, this, store, {}).also {
            it.initialize({ store.persisted })
        }
        val lease = PackageLease("dev.example.old-plugin", "a".repeat(64), 1, Uuid.random().toString())
        var authorized = true
        val run = SessionAgentRun.open(
            session,
            initial.currentMessages,
            pluginLeases = listOf(lease),
            packageAuthorization = { authorized }
        ) { _, _ -> null }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var effects = 0
        val operation = ConversationOperation(
            "trusted/report",
            "Report",
            PackageLease("builtin.test", "v1", 1),
            emptySet()
        ) { _, _ ->
            PreparedConversationOperation(emptyList(), execute = { context ->
                entered.complete(Unit)
                release.await()
                context.beforeEffect()
                effects++
                OperationResult()
            })
        }
        val call = session.state.value.operationCall(operation, JsonNull, run = run.ref)
        val dispatcher =
            SessionOperationDispatcher(listOf(operation), packageAuthorization = { authorized }) { _, _ ->
                true
            }
        val execution = async { dispatcher.dispatch(session, call) }
        entered.await()
        authorized = false
        release.complete(Unit)
        assertTrue(execution.await() is OperationDispatch.Waiting)
        assertEquals(0, effects)
        val saved = store.persisted!!.executionLedger()
        assertEquals(AttemptStatus.PREPARED, saved.operations.getValue(call.id).status)
        assertEquals(0, saved.operations.getValue(call.id).effectAdmissions)
        assertTrue("host.package-authorization" in saved.runs.getValue(run.ref.runId).state.gates)
    }

    @Test fun trustedOperationCannotBypassUnverifiedRunPackagesWithoutAPauseGate() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("input")))
        val store = FakeConversationStore(initial)
        val session = ConversationSession(initial.id, initial, this, store, {}).also {
            it.initialize({ store.persisted })
        }
        val lease = PackageLease("dev.example.old-plugin", "a".repeat(64), 1, Uuid.random().toString())
        val run = SessionAgentRun.open(
            session,
            initial.currentMessages,
            pluginLeases = listOf(lease),
            packageAuthorization = { true }
        ) { _, _ -> null }
        var effects = 0
        val operation = ConversationOperation(
            "trusted/report",
            "Report",
            PackageLease("builtin.test", "v1", 1),
            emptySet()
        ) { _, _ ->
            PreparedConversationOperation(emptyList(), execute = { context ->
                context.beforeEffect()
                effects++
                OperationResult()
            })
        }
        val call = session.state.value.operationCall(operation, JsonNull, run = run.ref)
        assertTrue(session.state.value.executionLedger().runs.getValue(run.ref.runId).state.gates.isEmpty())
        val outcome = SessionOperationDispatcher(listOf(operation)) { _, _ -> true }.dispatch(session, call)
        assertEquals(0, effects)
        assertTrue(outcome is OperationDispatch.Waiting)
        val saved = store.persisted!!.executionLedger().runs.getValue(run.ref.runId)
        assertEquals(listOf(lease), saved.pluginLeases)
        assertEquals(0, saved.state.attemptsUsed)
        assertTrue("host.package-authorization" in saved.state.gates)
    }
}
