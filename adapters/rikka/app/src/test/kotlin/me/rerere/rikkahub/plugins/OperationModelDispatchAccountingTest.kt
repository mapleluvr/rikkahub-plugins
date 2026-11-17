package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.AttemptStatus
import dev.rikkahub.plugins.protocol.PackageLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.plugins.builtin.BuiltinContextFeature
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.ConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** A model dispatch permission that has not returned is not a Provider attempt. */
class OperationModelDispatchAccountingTest {
    @Test
    fun revocationDuringModelPermissionPersistenceRefundsOnlyTheUndispatchedAttempt() = runBlocking {
        verifyPermission(revoke = true)
    }

    @Test
    fun authorizedModelRequestChargesTheOperationAndOwningRunOnce() = runBlocking {
        verifyPermission(revoke = false)
    }

    private suspend fun verifyPermission(revoke: Boolean) = kotlinx.coroutines.coroutineScope {
        val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("private source")))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var persisted: Conversation = source
        var armed = false
        var authorized = true
        val store = object : ConversationStore {
            override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }

            override suspend fun write(previous: Conversation?, current: Conversation) {
                if (armed && current.executionLedger().operations.values.any { it.modelAttempts == 1 }) {
                    armed = false
                    entered.complete(Unit)
                    release.await()
                }
                persisted = current
            }
        }
        val session = ConversationSession(source.id, source, this, store, {}).also { it.initialize({ persisted }) }
        val issuer = Uuid.random().toString()
        val leases = listOf("a", "b").mapIndexed { index, id ->
            PackageLease("dev.example.$id", (index + 1).toString().repeat(64), 1, issuer)
        }
        val run = SessionAgentRun.open(
            session,
            source.currentMessages,
            pluginLeases = leases,
            packageAuthorization = { authorized }
        ) { _, _ -> null }
        var providerEntries = 0
        val feature = BuiltinContextFeature { _, _, _, beforeDispatch ->
            armed = true
            beforeDispatch.dispatch {
                // Count the actual consumer entry, not the permission callback itself.
                providerEntries++
                "confirmed summary"
            }
        }
        val call = session.state.value.operationCall(feature.operation, feature.input(), run = run.ref)
        val dispatcher = SessionOperationDispatcher(listOf(feature.operation), packageAuthorization = { authorized })
            { _, _ -> true }
        val work = async { dispatcher.dispatch(session, call) }
        try {
            withTimeout(10_000) { entered.await() }
            if (revoke) authorized = false
            release.complete(Unit)
            val outcome = withTimeout(10_000) { work.await() }
            val ledger = persisted.executionLedger()
            val saved = ledger.runs.getValue(run.ref.runId)
            val operation = ledger.operations.getValue(call.id)
            assertEquals(leases, saved.pluginLeases)
            if (revoke) {
                assertEquals("No Provider call was entered", 0, providerEntries)
                assertEquals(
                    "A refused reservation must not consume the owning run attempt",
                    0,
                    saved.state.attemptsUsed
                )
                assertEquals(0, saved.state.operationStepsUsed)
                assertEquals(0, operation.modelAttempts)
                assertEquals(0, operation.effectAdmissions)
                assertEquals(AttemptStatus.PREPARED, operation.status)
                assertTrue(outcome.toString(), outcome is OperationDispatch.Waiting)
                assertTrue("host.package-authorization" in saved.state.gates)
            } else {
                assertEquals(1, providerEntries)
                assertEquals(1, saved.state.attemptsUsed)
                assertEquals(1, saved.state.operationStepsUsed)
                assertEquals(1, operation.modelAttempts)
                assertEquals(1, operation.effectAdmissions)
                assertEquals(AttemptStatus.SETTLED, operation.status)
                assertTrue(outcome is OperationDispatch.Settled)
            }
        } finally {
            release.complete(Unit)
            work.cancel()
        }
    }
}
