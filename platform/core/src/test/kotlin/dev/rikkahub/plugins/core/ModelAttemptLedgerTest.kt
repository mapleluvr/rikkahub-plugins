package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.Gate
import dev.rikkahub.plugins.protocol.GateKind
import dev.rikkahub.plugins.protocol.ModelAttemptPhase
import dev.rikkahub.plugins.protocol.RunCheckpoint
import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.RunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAttemptLedgerTest {
    private val initial = RunCheckpoint(RunState(RunRef("session", "branch", "run"), 3, 6), "BeforeModel", 0)

    @Test fun reservationRecoveryIsKnownNonDispatchAndRefundsOnlyOnce() {
        val (reserved, admission) = ModelAttemptLedger.reserve(initial, 0, 0, "request")
        assertTrue(admission.allowed)
        assertEquals(ModelAttemptPhase.RESERVED, reserved.modelAttempt!!.phase)
        assertFalse(ModelAttemptLedger.needsReconciliation(reserved))
        val recovered = ModelAttemptLedger.recover(reserved)
        assertEquals(0, recovered.state.stepsUsed)
        assertEquals(0, recovered.state.attemptsUsed)
        assertEquals(ModelAttemptPhase.REFUSED, recovered.modelAttempt!!.phase)
        assertEquals(recovered, ModelAttemptLedger.recover(recovered))
    }

    @Test fun dispatchIntentRecoveryKeepsBudgetAndRequiresReconciliation() {
        val reserved = ModelAttemptLedger.reserve(initial, 0, 0, "request").first
        val started = ModelAttemptLedger.start(reserved, "request", 0).first
        assertEquals(ModelAttemptPhase.DISPATCH_INTENT, started.modelAttempt!!.phase)
        assertEquals(started, ModelAttemptLedger.recover(started))
        assertTrue(ModelAttemptLedger.needsReconciliation(started))
    }

    @Test fun definitelyUndispatchedIntentCanBeRefundedByItsOwner() {
        val reserved = ModelAttemptLedger.reserve(initial, 0, 0, "request").first
        val started = ModelAttemptLedger.start(reserved, "request", 0).first
        val refused = ModelAttemptLedger.refuse(started, "request")
        assertEquals(0, refused.state.attemptsUsed)
        assertEquals(0, refused.state.stepsUsed)
        assertFalse(ModelAttemptLedger.needsReconciliation(refused))
        assertEquals(refused, ModelAttemptLedger.refuse(refused, "request"))
    }

    @Test fun refusedRetryCannotEraseAnEarlierUnconfirmedDispatch() {
        val reserved = ModelAttemptLedger.reserve(initial, 0, 0, "first").first
        val started = ModelAttemptLedger.start(reserved, "first", 0).first
        val retry = ModelAttemptLedger.reserve(started, 0, 0, "retry").first
        val refused = ModelAttemptLedger.refuse(retry, "retry")
        assertEquals(1, refused.state.stepsUsed)
        assertEquals(1, refused.state.attemptsUsed)
        assertTrue(ModelAttemptLedger.needsReconciliation(refused))
    }

    @Test fun confirmationClosesUncertaintyAndNextStepHasItsOwnReservation() {
        val reserved = ModelAttemptLedger.reserve(initial, 0, 0, "first").first
        val started = ModelAttemptLedger.start(reserved, "first", 0).first
        val completed = ModelAttemptLedger.confirm(started, 1).copy(nextStep = 1)
        assertEquals(ModelAttemptPhase.COMPLETED, completed.modelAttempt!!.phase)
        assertFalse(ModelAttemptLedger.needsReconciliation(completed))
        val next = ModelAttemptLedger.reserve(completed, 1, 0, "next").first
        val refused = ModelAttemptLedger.refuse(next, "next")
        assertEquals(1, refused.state.stepsUsed)
        assertEquals(1, refused.state.attemptsUsed)
        assertFalse(ModelAttemptLedger.needsReconciliation(refused))
    }

    @Test fun refundPreservesIndependentGatesAndConcurrentOperationCharges() {
        val reserved = ModelAttemptLedger.reserve(initial, 0, 0, "request").first
        val paused = reserved.copy(state = RunControl.addGate(reserved.state, Gate("user", GateKind.USER_PAUSE)))
        val withOperation = paused.copy(state = paused.state.copy(
            operationStepsUsed = 1,
            attemptsUsed = paused.state.attemptsUsed + 1,
        ))
        val refused = ModelAttemptLedger.refuse(withOperation, "request")
        assertEquals(0, refused.state.stepsUsed)
        assertEquals(1, refused.state.operationStepsUsed)
        assertEquals(1, refused.state.attemptsUsed)
        assertEquals(paused.state.gates, refused.state.gates)
        assertEquals(paused.state.frameRevision, refused.state.frameRevision)
    }

    @Test fun gateBeforeFinalIntentRefusesDispatchWithoutAnotherCharge() {
        val reserved = ModelAttemptLedger.reserve(initial, 0, 0, "request").first
        val paused = reserved.copy(state = RunControl.addGate(reserved.state, Gate("user", GateKind.USER_PAUSE)))
        val (next, admission) = ModelAttemptLedger.start(paused, "request", 0)
        assertFalse(admission.allowed)
        assertEquals(paused, next)
        assertEquals(1, next.state.attemptsUsed)
    }

    @Test fun legacyCountersStillRequireConservativeRecovery() {
        val legacy = initial.copy(state = RunControl.admit(initial.state, 0, 0).state)
        assertTrue(ModelAttemptLedger.needsReconciliation(legacy))
        assertEquals(legacy, ModelAttemptLedger.recover(legacy))
    }
}
