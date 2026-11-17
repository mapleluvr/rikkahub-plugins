package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.Admission
import dev.rikkahub.plugins.core.RunControl
import dev.rikkahub.plugins.protocol.Gate
import dev.rikkahub.plugins.protocol.RunCheckpoint
import dev.rikkahub.plugins.protocol.RunRef
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession

/** Persisted run identity and independent gates; coroutine segments never own the budget. */
class SessionRunStore(private val session: ConversationSession) {
    private val incarnation = session.incarnation

    suspend fun create(checkpoint: RunCheckpoint): RunCheckpoint {
        require(checkpoint.state.ref.conversationId == session.id.toString())
        val saved = session.commitExtension(incarnation) { current ->
            val ledger = current.executionLedger()
            val id = checkpoint.state.ref.runId
            val existing = ledger.runs[id]
            require(existing == null || existing == checkpoint) { "Run identity reused" }
            if (existing == null) {
                current.forInputPath(checkpoint.inputIds)
                val present = current.messageNodes.flatMap { node -> node.messages.map { it.id.toString() } }.toSet()
                require(checkpoint.requiredHistoryIds(ledger).all { it in present }) { "Run history was removed" }
            }
            current.withExecutionLedger(ledger.copy(runs = ledger.runs + (id to checkpoint)))
        }
        return saved.executionLedger().runs.getValue(checkpoint.state.ref.runId)
    }

    suspend fun addGate(ref: RunRef, gate: Gate): RunCheckpoint = update(ref) {
        it.copy(state = RunControl.addGate(it.state, gate))
    }

    suspend fun resolveGate(ref: RunRef, id: String): RunCheckpoint = update(ref) {
        it.copy(state = RunControl.resolveGate(it.state, id))
    }

    suspend fun cancel(ref: RunRef): RunCheckpoint = update(ref) {
        it.copy(state = RunControl.cancel(it.state))
    }

    suspend fun admit(ref: RunRef, step: Int, preparedFrameRevision: Long): Admission {
        // commitExtension may re-evaluate after settings preparation; return the final evaluation.
        var admission: Admission? = null
        update(ref) {
            val result = RunControl.admit(it.state, step, preparedFrameRevision)
            admission = result
            it.copy(state = result.state)
        }
        return checkNotNull(admission)
    }

    /** Commit the cursor and corresponding assistant/tool result in one conversation write. */
    suspend fun checkpoint(
        ref: RunRef,
        boundary: String,
        nextStep: Int,
        toolCallId: String? = null,
        updateMessages: (Conversation) -> Conversation = { it }
    ): RunCheckpoint {
        require(boundary.isNotBlank())
        val saved = session.commitExtension(incarnation) { current ->
            val ledger = current.executionLedger()
            val old = requireRun(current, ref)
            require(nextStep in old.nextStep..old.state.stepsUsed) { "Invalid completed-step cursor" }
            val next = old.copy(boundary = boundary, nextStep = nextStep, toolCallId = toolCallId)
            updateMessages(current).withExecutionLedger(ledger.copy(runs = ledger.runs + (ref.runId to next)))
        }
        return saved.executionLedger().runs.getValue(ref.runId)
    }

    suspend fun update(ref: RunRef, transform: (RunCheckpoint) -> RunCheckpoint): RunCheckpoint {
        val saved = session.commitExtension(incarnation) { current ->
            val ledger = current.executionLedger()
            val next = transform(requireRun(current, ref))
            require(next.state.ref == ref) { "Run identity is immutable" }
            current.withExecutionLedger(ledger.copy(runs = ledger.runs + (ref.runId to next)))
        }
        return saved.executionLedger().runs.getValue(ref.runId)
    }

    private fun requireRun(current: Conversation, ref: RunRef): RunCheckpoint {
        require(ref.conversationId == current.id.toString())
        val checkpoint = requireNotNull(current.executionLedger().runs[ref.runId]) { "Unknown run" }
        require(checkpoint.state.ref == ref) { "Run branch, epoch or package lease changed" }
        return checkpoint
    }
}
