package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.ModelAttemptPhase
import dev.rikkahub.plugins.protocol.ModelAttemptRecord
import dev.rikkahub.plugins.protocol.RunCheckpoint

/** Exact accounting for the latest request; older dispatched retries remain charged and uncertain. */
object ModelAttemptLedger {
    fun reserve(checkpoint: RunCheckpoint, step: Int, preparedFrame: Long, id: String): Pair<RunCheckpoint, Admission> {
        require(id.isNotBlank())
        check(checkpoint.modelAttempt?.phase != ModelAttemptPhase.RESERVED) { "Previous reservation is unresolved" }
        val admission = RunControl.admit(checkpoint.state, step, preparedFrame)
        if (!admission.allowed) return checkpoint to admission
        val next = checkpoint.copy(
            state = admission.state,
            modelAttempt = ModelAttemptRecord(
                id,
                step,
                chargedStep = step == checkpoint.state.stepsUsed,
                priorDispatchUnconfirmed = needsReconciliation(checkpoint),
            ),
        )
        return next to admission
    }

    fun start(checkpoint: RunCheckpoint, id: String, preparedFrame: Long): Pair<RunCheckpoint, Admission> {
        val reservation = checkNotNull(checkpoint.modelAttempt)
        require(reservation.id == id && reservation.phase == ModelAttemptPhase.RESERVED)
        val reason = when {
            checkpoint.state.status != dev.rikkahub.plugins.protocol.RunStatus.ACTIVE -> "Run is terminal"
            checkpoint.state.gates.isNotEmpty() -> "Run has unresolved gates"
            checkpoint.state.frameRevision != preparedFrame -> "Prepared request is stale"
            else -> null
        }
        if (reason != null) return checkpoint to Admission(checkpoint.state, false, reason)
        val next = checkpoint.copy(
            state = checkpoint.state.copy(revision = checkpoint.state.revision + 1),
            modelAttempt = reservation.copy(phase = ModelAttemptPhase.DISPATCH_INTENT),
        )
        return next to Admission(next.state, true)
    }

    /** Only the caller still before Provider invocation may confirm that this intent never dispatched. */
    fun refuse(checkpoint: RunCheckpoint, id: String): RunCheckpoint {
        val attempt = checkpoint.modelAttempt ?: return checkpoint
        val refused = refusedTicket(attempt, id) ?: return checkpoint
        return checkpoint.copy(
            state = checkpoint.state.copy(
                stepsUsed = checkpoint.state.stepsUsed - if (attempt.chargedStep) 1 else 0,
                attemptsUsed = checkpoint.state.attemptsUsed - 1,
                revision = checkpoint.state.revision + 1,
            ),
            modelAttempt = refused,
        )
    }

    /** Shared by main requests and operation jobs; stale or already settled tickets cannot refund. */
    fun refusedTicket(attempt: ModelAttemptRecord?, id: String): ModelAttemptRecord? = attempt?.takeIf {
        it.id == id && it.phase in setOf(ModelAttemptPhase.RESERVED, ModelAttemptPhase.DISPATCH_INTENT)
    }?.copy(phase = ModelAttemptPhase.REFUSED)

    fun confirm(checkpoint: RunCheckpoint, nextStep: Int): RunCheckpoint {
        val attempt = checkpoint.modelAttempt ?: return checkpoint
        return if (nextStep > attempt.step && attempt.phase == ModelAttemptPhase.DISPATCH_INTENT) {
            checkpoint.copy(modelAttempt = attempt.copy(phase = ModelAttemptPhase.COMPLETED))
        } else {
            checkpoint
        }
    }

    fun recover(checkpoint: RunCheckpoint): RunCheckpoint = checkpoint.modelAttempt?.let { attempt ->
        if (attempt.phase == ModelAttemptPhase.RESERVED) refuse(checkpoint, attempt.id) else checkpoint
    } ?: checkpoint

    fun needsReconciliation(checkpoint: RunCheckpoint): Boolean {
        val attempt = checkpoint.modelAttempt ?: return checkpoint.state.stepsUsed > checkpoint.nextStep
        return attempt.step >= checkpoint.nextStep &&
            (attempt.phase == ModelAttemptPhase.DISPATCH_INTENT || attempt.priorDispatchUnconfirmed)
    }
}
