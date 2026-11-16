package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.OperationContext
import dev.rikkahub.plugins.core.OperationDefinition
import dev.rikkahub.plugins.core.OperationRunner
import dev.rikkahub.plugins.protocol.AttemptStatus
import dev.rikkahub.plugins.protocol.BranchRef
import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.SourceStamp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import kotlin.uuid.Uuid

/** Prepared by the feature from a fixed snapshot; all effects start after the attempt receipt commits. */
data class PreparedConversationOperation(
    val sources: List<SourceStamp>,
    val execute: suspend (OperationContext) -> OperationResult,
    val validateAdoption: (Conversation) -> Unit = {},
    val adopt: (Conversation, OperationResult) -> Conversation = { conversation, _ -> conversation }
)

data class ConversationOperation(
    val id: String,
    val title: String,
    val lease: PackageLease,
    val capabilities: Set<String>,
    val prepare: (Conversation, JsonElement) -> PreparedConversationOperation
)

/** One immutable registry snapshot. Feature policy, model prompts and artifact formats stay outside it. */
class SessionOperationDispatcher(
    definitions: List<ConversationOperation>,
    private val authorized: (PackageLease, Set<String>) -> Boolean,
) {
    private val registry = definitions.associateBy { it.id to it.lease }

    init {
        require(registry.size == definitions.size) { "Duplicate operation registration" }
    }

    suspend fun execute(
        session: ConversationSession,
        call: OperationCall,
        timeoutMillis: Long = 120_000,
    ): OperationResult {
        val definition = requireNotNull(registry[call.type to call.handler]) { "Operation version is unavailable" }
        check(authorized(call.handler, definition.capabilities)) { "Operation is disabled or unauthorized" }
        val snapshot = session.state.value
        require(call.target.conversationId == snapshot.id.toString())
        snapshot.executionLedger().operations[call.id]?.let { prior ->
            require(prior.call == call) { "Command identity reused" }
            if (prior.status == AttemptStatus.SETTLED) {
                session.flush()
                return checkNotNull(prior.result)
            }
            check(prior.status == AttemptStatus.PREPARED) { "Previous attempt needs reconciliation" }
        }
        val prepared = definition.prepare(snapshot, call.input)
        val store = SessionOperationStore(session)
        val record = store.prepare(call, prepared.sources)
        if (record.status == AttemptStatus.SETTLED) return checkNotNull(record.result)
        check(record.status == AttemptStatus.PREPARED) {
            "Previous attempt needs reconciliation; refusing automatic replay"
        }
        val attempt = Uuid.random().toString()
        store.start(call.id, attempt)
        try {
            val runner = OperationRunner(listOf(OperationDefinition(
                definition.id,
                definition.lease,
                definition.capabilities,
                prepared.execute
            )))
            fun validate(current: Conversation) {
                check(authorized(call.handler, definition.capabilities)) { "Operation authorization was revoked" }
                call.run?.let { ref ->
                    val run = requireNotNull(current.executionLedger().runs[ref.runId]) { "Owning run is missing" }
                    check(run.state.ref == ref && run.state.status == dev.rikkahub.plugins.protocol.RunStatus.ACTIVE) {
                        "Owning run ended or changed"
                    }
                }
                val sources = current.pluginSources().associateBy { it.id }
                check(prepared.sources.all { sources[it.id] == it }) { "Operation source changed" }
                prepared.validateAdoption(current)
            }
            val result = runner.execute(call, definition.capabilities, attempt, timeoutMillis) {
                validate(session.state.value)
            }
            currentCoroutineContext().ensureActive()
            store.settle(call, attempt, result) { current ->
                validate(current)
                current.validateOperationOutput(result, prepared.sources, definition.capabilities)
                prepared.adopt(current, result)
            }
            return result
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching { store.cancel(call.id) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }
}

fun Conversation.operationCall(
    definition: ConversationOperation,
    input: JsonElement,
    commandId: String = Uuid.random().toString(),
    run: RunRef? = null,
    caller: PackageLease? = null,
): OperationCall = OperationCall(
    commandId,
    definition.id,
    BranchRef(id.toString(), run?.branchId ?: currentMessages.joinToString("/") { it.id.toString() }),
    input,
    definition.lease,
    caller,
    run,
)
