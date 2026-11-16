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
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest

private const val HOST_LEDGER = "host.execution"

fun Conversation.executionLedger(): ExecutionLedger {
    val payload = pluginState[HOST_LEDGER] ?: return ExecutionLedger()
    val ledger = JsonInstant.decodeFromJsonElement<ExecutionLedger>(payload)
    require(ledger.version == 1) { "Unsupported execution ledger; preserved without execution" }
    return ledger
}

fun Conversation.withExecutionLedger(ledger: ExecutionLedger): Conversation = copy(
    pluginState = JsonObject(pluginState + (HOST_LEDGER to JsonInstant.encodeToJsonElement(ledger)))
)

/** All branches are addressed by stable message identity, independently of the visible selection. */
fun Conversation.pluginSources(): List<SourceStamp> = messageNodes.flatMap { node ->
    node.messages.map { message ->
        val content = JsonInstant.encodeToString(message.parts)
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "${message.role}:$content".toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
        SourceStamp(message.id.toString(), digest)
    }
}

/**
 * Persistent operation receipts on the session's single authoritative Conversation.
 * The dispatcher owns cancellation and permission checks; this class owns commit ordering.
 */
class SessionOperationStore(private val session: ConversationSession) {
    private val incarnation = session.incarnation
    suspend fun prepare(call: OperationCall, sources: List<SourceStamp>): OperationRecord {
        require(call.ref.conversationId == session.id.toString())
        val saved = session.commitExtension(incarnation) {
            require(sources.all { stamp -> stamp in it.pluginSources() }) { "Source is stale" }
            it.withExecutionLedger(OperationLedger.prepare(it.executionLedger(), call, sources))
        }
        return saved.executionLedger().operations.getValue(call.id)
    }

    suspend fun start(id: String, attemptId: String): OperationRecord {
        val saved = session.commitExtension(incarnation) {
            val record = it.executionLedger().operations.getValue(id)
            require(record.sources.all { stamp -> stamp in it.pluginSources() }) { "Source is stale" }
            it.withExecutionLedger(OperationLedger.start(it.executionLedger(), id, attemptId))
        }
        return saved.executionLedger().operations.getValue(id)
    }

    suspend fun settle(
        call: OperationCall,
        attemptId: String,
        result: OperationResult,
        applyResult: (Conversation) -> Conversation = { it }
    ) {
        session.commitExtension(incarnation) { current ->
            val ledger = current.executionLedger()
            val settled = OperationLedger.settle(ledger, call.id, attemptId, call.ref, current.pluginSources(), result)
            // Duplicate delivery must not apply message/state changes for a second time.
            if (settled === ledger) {
                current
            } else {
                applyResult(current).withExecutionLedger(settled)
            }
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
