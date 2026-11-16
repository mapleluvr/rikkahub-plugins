package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.Gate
import dev.rikkahub.plugins.protocol.GateKind
import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.RunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunControlTest {
    private val ref = RunRef("conversation", "branch", "run", 1, "package-hash")
    private val initial = RunState(ref, maxSteps = 2, maxAttempts = 3)

    @Test
    fun independentGatesMustAllBeResolvedBeforeAdmission() {
        var state = RunControl.addGate(initial, Gate("pause", GateKind.USER_PAUSE))
        state = RunControl.addGate(state, Gate("approval", GateKind.TOOL_APPROVAL))
        assertFalse(RunControl.admit(state, 0, state.revision).allowed)
        state = RunControl.resolveGate(state, "pause")
        assertFalse(RunControl.admit(state, 0, state.revision).allowed)
        state = RunControl.resolveGate(state, "approval")
        assertTrue(RunControl.admit(state, 0, state.revision).allowed)
    }

    @Test
    fun budgetsPersistAcrossSegmentsAndRetriesHaveTheirOwnLimit() {
        var state = RunControl.admit(initial, 0, initial.revision).state
        state = RunControl.admit(state, 0, state.revision).state
        assertEquals(1, state.stepsUsed)
        assertEquals(2, state.attemptsUsed)
        state = RunControl.admit(state, 1, state.revision).state
        val reconstructed = state.copy()
        assertFalse(RunControl.admit(reconstructed, 1, reconstructed.revision).allowed)
        assertFalse(RunControl.admit(reconstructed, 2, reconstructed.revision).allowed)
    }

    @Test
    fun preparationRevisionAndTerminalStateAreRechecked() {
        val preparedAt = initial.revision
        var state = RunControl.addGate(initial, Gate("pause", GateKind.USER_PAUSE))
        state = RunControl.resolveGate(state, "pause")
        assertFalse(RunControl.admit(state, 0, preparedAt).allowed)
        state = RunControl.cancel(state)
        assertFalse(RunControl.admit(state, 0, state.revision).allowed)
        assertEquals(state, RunControl.resolveGate(state, "missing"))
    }

    @Test
    fun repeatedGateUpdatesDoNotResetStateOrIncrementRevision() {
        val gate = Gate("pause", GateKind.USER_PAUSE)
        val state = RunControl.addGate(initial, gate)
        assertEquals(state, RunControl.addGate(state, gate))
        assertThrows(IllegalArgumentException::class.java) {
            RunControl.addGate(state, Gate("pause", GateKind.PLUGIN_INPUT))
        }
    }
}
