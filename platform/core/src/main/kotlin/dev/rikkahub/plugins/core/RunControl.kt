package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.Gate
import dev.rikkahub.plugins.protocol.RunState
import dev.rikkahub.plugins.protocol.RunStatus

data class Admission(val state: RunState, val allowed: Boolean, val reason: String? = null)

/** Pure transitions. The session owner persists and serializes their application. */
object RunControl {
    fun addGate(state: RunState, gate: Gate): RunState {
        check(state.status == RunStatus.ACTIVE) { "Run is terminal" }
        val existing = state.gates[gate.id]
        require(existing == null || existing == gate) { "Gate identity cannot change kind" }
        return if (existing != null) {
            state
        } else {
            state.copy(
                gates = state.gates + (gate.id to gate),
                revision = state.revision + 1,
                frameRevision = state.frameRevision + 1
            )
        }
    }

    fun resolveGate(state: RunState, id: String): RunState =
        if (id !in state.gates || state.status != RunStatus.ACTIVE) {
            state
        } else {
            state.copy(gates = state.gates - id, revision = state.revision + 1, frameRevision = state.frameRevision + 1)
        }

    fun cancel(state: RunState): RunState =
        if (state.status != RunStatus.ACTIVE) {
            state
        } else {
            state.copy(status = RunStatus.CANCELLED, revision = state.revision + 1, frameRevision =
                state.frameRevision + 1)
        }

    fun admit(state: RunState, step: Int, preparedFrameRevision: Long): Admission {
        val reason = when {
            state.status != RunStatus.ACTIVE -> "Run is terminal"
            state.gates.isNotEmpty() -> "Run has unresolved gates"
            preparedFrameRevision != state.frameRevision -> "Prepared request is stale"
            step < 0 || step < state.stepsUsed - 1 || step > state.stepsUsed -> "Invalid step cursor"
            step == state.stepsUsed && state.stepsUsed + state.operationStepsUsed >= state.maxSteps -> "Model step budget exhausted"
            state.attemptsUsed >= state.maxAttempts -> "Request attempt budget exhausted"
            else -> null
        }
        if (reason != null) return Admission(state, false, reason)
        return Admission(state.copy(
            stepsUsed = maxOf(state.stepsUsed, step + 1),
            attemptsUsed = state.attemptsUsed + 1,
            revision = state.revision + 1
        ), true)
    }
}
