package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.AttemptStatus
import dev.rikkahub.plugins.protocol.BranchRef
import dev.rikkahub.plugins.protocol.ExecutionLedger
import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationRecord
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.RunCheckpoint
import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.RunState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.deleteMessage
import me.rerere.rikkahub.data.model.editMessage
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.ConversationStore
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SessionHistoryMutationTest {
    private val user = UIMessage.user("owned source")
    private val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(user))
    private val ref = RunRef(initial.id.toString(), user.id.toString(), "run")
    private val checkpoint = RunCheckpoint(
        RunState(ref, 5, 10),
        "BeforeModel",
        0,
        inputIds = listOf(user.id.toString())
    )
    private val owned = initial.withExecutionLedger(ExecutionLedger(runs = mapOf(ref.runId to checkpoint)))

    @Test
    fun ownedDeletionIsRejectedButUnrelatedBranchesAndStoppedHistoryRemainEditable() = runBlocking {
        val disk = FakeConversationStore(owned)
        val session = ConversationSession(owned.id, owned, this, disk, {}).also { it.initialize({ owned }) }
        val rejection = runCatching {
            session.commitHistoryMutation { checkNotNull(it.deleteMessage(user.id)) }
        }.exceptionOrNull()
        assertTrue(rejection is RunHistoryConflict)
        assertEquals(owned, session.state.value)
        assertEquals(owned, disk.persisted)
        assertTrue(disk.writes.isEmpty())
        // A normal edit appends a sibling; neither changing selection nor removing that sibling owns the old input.
        session.commitHistoryMutation { it.editMessage(user.id, listOf(UIMessagePart.Text("sibling"))) }
        val sibling = session.state.value.currentMessages.single()
        assertEquals("sibling", sibling.toText())
        session.commitHistoryMutation { checkNotNull(it.deleteMessage(sibling.id)) }
        assertEquals(listOf(user), session.state.value.currentMessages)
        assertEquals(checkpoint, session.state.value.executionLedger().runs.getValue(ref.runId))
        session.cancelPluginRuns()
        session.commitHistoryMutation { checkNotNull(it.deleteMessage(user.id)) }
        assertTrue(session.state.value.messageNodes.isEmpty())
        assertTrue(session.canStartNextTurn)
    }

    @Test
    fun adoptedSummaryCannotBeDeletedOrRewrittenBeforeRecoveryConsumesItsReceipt() = runBlocking {
        val summary = UIMessage.user("adopted summary").copy(isContextCheckpoint = true)
        val call = OperationCall(
            "adopted",
            "test/result",
            BranchRef(initial.id.toString(), ref.branchId),
            JsonNull,
            PackageLease("test", "v1", 1),
            run = ref
        )
        val savedRun = checkpoint.copy(pendingOperation = call)
        val source = initial.updateCurrentMessages(listOf(user, summary)).withExecutionLedger(ExecutionLedger(
            runs = mapOf(ref.runId to savedRun),
            operations = mapOf(call.id to OperationRecord(
                call,
                emptyList(),
                status = AttemptStatus.SETTLED,
                adoptedMessageIds = listOf(summary.id.toString())
            )),
        ))
        val disk = FakeConversationStore(source)
        val session = ConversationSession(source.id, source, this, disk, {}).also { it.initialize({ source }) }
        for (remove in listOf(false, true)) {
            val rejection = runCatching {
                session.commitHistoryMutation {
                    if (remove) {
                        checkNotNull(it.deleteMessage(summary.id))
                    } else {
                        it.editMessage(summary.id, listOf(UIMessagePart.Text("rewritten")))
                    }
                }
            }.exceptionOrNull()
            assertTrue(rejection is RunHistoryConflict)
            assertEquals(source, session.state.value)
            assertEquals(source, disk.persisted)
        }
        val damaged = checkNotNull(source.deleteMessage(summary.id)).recoverPluginExecution()
        assertTrue(MISSING_RUN_HISTORY_GATE in damaged.executionLedger().runs.getValue(ref.runId).state.gates)
    }

    @Test
    fun regenerationRequiresIdleOwnershipEvenWhenItsTruncationWouldRemoveNothing() = runBlocking {
        val disk = FakeConversationStore(owned)
        val session = ConversationSession(owned.id, owned, this, disk, {}).also { it.initialize({ owned }) }
        val rejection = runCatching {
            session.commitHistoryMutation(requireIdleRun = true) { it }
        }.exceptionOrNull()
        assertTrue(rejection is RunHistoryConflict)
        assertEquals(owned, disk.persisted)
    }

    @Test
    fun ownershipCreatedDuringStoragePreparationIsCheckedAgainBeforePublication() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val disk = FakeConversationStore(initial)
        val store = object : ConversationStore by disk {
            override suspend fun prepareWrite(): (Conversation) -> Conversation {
                entered.complete(Unit)
                release.await()
                return { it }
            }
        }
        val session = ConversationSession(initial.id, initial, this, store, {}).also { it.initialize({ initial }) }
        val deletion = async {
            runCatching { session.commitHistoryMutation { checkNotNull(it.deleteMessage(user.id)) } }.exceptionOrNull()
        }
        entered.await()
        session.update { owned }
        release.complete(Unit)
        assertTrue(deletion.await() is RunHistoryConflict)
        assertEquals(owned, session.state.value)
        assertEquals(initial, disk.persisted)
        assertTrue(disk.writes.isEmpty())
    }

    @Test
    fun runCreationCannotAcquireAnInputDeletedBeforeItsCommit() = runBlocking {
        val disk = FakeConversationStore(initial)
        val session = ConversationSession(initial.id, initial, this, disk, {}).also { it.initialize({ initial }) }
        session.commitHistoryMutation { checkNotNull(it.deleteMessage(user.id)) }
        val rejection = runCatching { SessionRunStore(session).create(checkpoint) }.exceptionOrNull()
        assertTrue("Creating a run from deleted input must fail before publishing ownership", rejection != null)
        assertTrue(session.state.value.executionLedger().runs.isEmpty())
        assertTrue(disk.persisted!!.executionLedger().runs.isEmpty())
    }

    @Test
    fun failedHistoryCommitCannotBePublishedOrAdoptedByALaterFlush() = runBlocking {
        val disk = FakeConversationStore(initial)
        val session = ConversationSession(initial.id, initial, this, disk, {}).also { it.initialize({ initial }) }
        disk.beforeWrite = { error("storage failure") }
        val failure = runCatching {
            session.commitHistoryMutation { checkNotNull(it.deleteMessage(user.id)) }
        }.exceptionOrNull()
        assertEquals("storage failure", failure?.message)
        assertEquals(initial, session.state.value)
        disk.beforeWrite = {}
        session.flush()
        assertEquals(initial, disk.persisted)
    }
}
