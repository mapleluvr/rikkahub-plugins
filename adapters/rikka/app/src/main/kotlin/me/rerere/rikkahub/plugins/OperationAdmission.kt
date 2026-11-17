package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.GateKind
import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.RunStatus
import me.rerere.rikkahub.data.model.Conversation

/** Only host admission can claim that an effect was definitely refused before dispatch. */
internal open class OperationAdmissionDenied(message: String) : IllegalStateException(message)
internal class OperationWaiting(message: String = "Owning run has another unresolved gate") :
    OperationAdmissionDenied(message)

internal fun requireOperationAdmission(allowed: Boolean, message: String) {
    if (!allowed) throw OperationAdmissionDenied(message)
}

internal fun Conversation.requireOperationEffectAllowed(call: OperationCall) {
    call.run?.let { ref ->
        val run = executionLedger().runs[ref.runId]
        requireOperationAdmission(
            run?.state?.ref == ref && run.state.status == RunStatus.ACTIVE,
            "Owning run ended or changed"
        )
        if (run!!.state.gates.values.any { it.id != call.id || it.kind != GateKind.OPERATION_DEPENDENCY }) {
            throw OperationWaiting()
        }
    }
}
