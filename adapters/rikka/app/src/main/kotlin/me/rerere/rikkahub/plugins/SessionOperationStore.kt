package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.OperationLedger
import dev.rikkahub.plugins.protocol.ExecutionLedger
import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationRecord
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.SourceStamp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest

private const val HOST_LEDGER = "host.execution"

fun Conversation.executionLedger(): ExecutionLedger {
    val payload = pluginState[HOST_LEDGER] ?: return ExecutionLedger()
    val ledger = JsonInstant.decodeFromJsonElement<ExecutionLedger>(payload)
    require(ledger.version == 3) { "Unsupported execution ledger; preserved without execution" }
    return ledger
}

fun Conversation.withExecutionLedger(ledger: ExecutionLedger): Conversation = copy(
    pluginState = JsonObject(pluginState + (HOST_LEDGER to JsonInstant.encodeToJsonElement(ledger)))
)

/** All branches are addressed by stable message identity, independently of the visible selection. */
fun UIMessage.sourceStamp(): SourceStamp {
    val content = JsonInstant.encodeToString(parts)
    val digest = MessageDigest.getInstance("SHA-256").digest(
        "$role:$content".toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }
    return SourceStamp(id.toString(), digest)
}

fun Conversation.pluginSources(): List<SourceStamp> = messageNodes.flatMap { node ->
    node.messages.map { it.sourceStamp() }
}

/**
 * Persistent operation receipts on the session's single authoritative Conversation.
 * The dispatcher owns cancellation and permission checks; this class owns commit ordering.
 */
class SessionOperationStore(private val session: ConversationSession) {
    private val incarnation = session.incarnation
    suspend fun prepare(call: OperationCall, sources: List<SourceStamp>): OperationRecord {
        require(call.target.conversationId == session.id.toString())
        val saved = session.commitExtension(incarnation) {
            val index = it.pluginSources().associateBy { stamp -> stamp.id }
            require(sources.all { stamp -> index[stamp.id] == stamp }) { "Source is stale" }
            it.withExecutionLedger(OperationLedger.prepare(it.executionLedger(), call, sources))
        }
        return saved.executionLedger().operations.getValue(call.id)
    }

    suspend fun start(id: String, attemptId: String): OperationRecord {
        val saved = session.commitExtension(incarnation) {
            val record = it.executionLedger().operations.getValue(id)
            val index = it.pluginSources().associateBy { stamp -> stamp.id }
            require(record.sources.all { stamp -> index[stamp.id] == stamp }) { "Source is stale" }
            it.withExecutionLedger(OperationLedger.start(it.executionLedger(), id, attemptId))
        }
        return saved.executionLedger().operations.getValue(id)
    }

    suspend fun settle(
        call: OperationCall,
        attemptId: String,
        result: OperationResult,
        displaySourceIds: List<String> = call.frame.map { it.id },
        applyResult: (Conversation) -> Conversation = { it }
    ) {
        session.commitExtension(incarnation) { current ->
            val ledger = current.executionLedger()
            val settled = OperationLedger.settle(ledger, call.id, attemptId, call, current.pluginSources(), result)
            // Duplicate delivery must not apply message/state changes for a second time.
            if (settled === ledger) {
                current
            } else {
                val adopted = applyResult(current)
                val existing = current.messageNodes.flatMap { node -> node.messages }
                    .mapTo(HashSet()) { message -> message.id.toString() }
                val inserted = adopted.messageNodes.flatMap { node -> node.messages }
                    .map { message -> message.id.toString() }.filter { id -> id !in existing }
                adopted.withExecutionLedger(settled.copy(
                    operations = settled.operations + (call.id to settled.operations.getValue(call.id)
                        .copy(adoptedMessageIds = inserted)),
                    messageLineages = settled.messageLineages + inserted.associateWith { displaySourceIds },
                ))
            }
        }
    }

    /** One model job per operation; each actual network retry is separately charged. */
    suspend fun chargeModelAttempt(call: OperationCall, validate: (Conversation) -> Unit) {
        session.commitExtension(incarnation) { current ->
            validate(current)
            val ledger = current.executionLedger()
            val record = ledger.operations.getValue(call.id)
            check(record.status == dev.rikkahub.plugins.protocol.AttemptStatus.RUNNING)
            current.requireOperationEffectAllowed(call)
            requireOperationAdmission(record.modelAttempts < record.maxModelAttempts, "Operation request budget exhausted")
            var runs = ledger.runs
            call.run?.let { ref ->
                val run = runs.getValue(ref.runId)
                val state = run.state
                requireOperationAdmission(state.attemptsUsed < state.maxAttempts, "Owning run request budget exhausted")
                val newStep = if (record.modelAttempts == 0) 1 else 0
                requireOperationAdmission(state.stepsUsed + state.operationStepsUsed + newStep <= state.maxSteps,
                    "Owning run model budget exhausted")
                runs = runs + (ref.runId to run.copy(state = state.copy(
                    attemptsUsed = state.attemptsUsed + 1,
                    operationStepsUsed = state.operationStepsUsed + newStep,
                    revision = state.revision + 1,
                )))
            }
            current.withExecutionLedger(ledger.copy(
                runs = runs,
                operations = ledger.operations + (call.id to record.copy(
                    modelAttempts = record.modelAttempts + 1, effectAdmissions = record.effectAdmissions + 1)),
            ))
        }
    }

    suspend fun admitEffect(call: OperationCall, validate: (Conversation) -> Unit) {
        session.commitExtension(incarnation) { current ->
            validate(current)
            current.requireOperationEffectAllowed(call)
            val ledger = current.executionLedger()
            val record = ledger.operations.getValue(call.id)
            check(record.status == dev.rikkahub.plugins.protocol.AttemptStatus.RUNNING)
            current.withExecutionLedger(ledger.copy(operations = ledger.operations +
                (call.id to record.copy(effectAdmissions = record.effectAdmissions + 1))))
        }
    }

    /** A host rejection may reset pure preparation only when no effect permit was ever handed out. */
    suspend fun refusedBeforeEffect(id: String, waiting: Boolean) {
        session.commitExtension(incarnation) { current ->
            val ledger = current.executionLedger()
            val record = ledger.operations.getValue(id)
            if (record.effectAdmissions == 0 && record.modelAttempts == 0 &&
                record.status == dev.rikkahub.plugins.protocol.AttemptStatus.RUNNING) {
                current.withExecutionLedger(ledger.copy(operations = ledger.operations + (id to record.copy(
                    status = if (waiting) dev.rikkahub.plugins.protocol.AttemptStatus.PREPARED
                        else dev.rikkahub.plugins.protocol.AttemptStatus.CANCELLED,
                    attemptId = null,
                ))))
            } else current.withExecutionLedger(OperationLedger.cancel(ledger, id))
        }
    }

    suspend fun recover() {
        session.commitExtension(incarnation) {
            it.withExecutionLedger(OperationLedger.recover(it.executionLedger()))
        }
    }

    suspend fun cancel(id: String) {
        session.commitExtension(incarnation) {
            it.withExecutionLedger(OperationLedger.cancel(it.executionLedger(), id))
        }
    }
}
