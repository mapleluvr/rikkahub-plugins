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
    val continueRunAfterFailure: Boolean = false,
    val prepare: (Conversation, JsonElement) -> PreparedConversationOperation
)

sealed interface OperationDispatch {
    data class Settled(val result: OperationResult) : OperationDispatch
    data class Rejected(val error: Throwable) : OperationDispatch
    data class NeedsReconciliation(val error: Throwable) : OperationDispatch
    data class StorageBlocked(val error: Throwable) : OperationDispatch
}

/** One immutable registry snapshot. Feature policy, model prompts and artifact formats stay outside it. */
class SessionOperationDispatcher(
    definitions: List<ConversationOperation>,
    private val authorized: (PackageLease, Set<String>) -> Boolean,
) {
    private val registry = definitions.associateBy { it.id to it.lease }

    init {
        require(registry.size == definitions.size) { "Duplicate operation registration" }
    }

    fun label(call: OperationCall): String = registry[call.type to call.handler]?.title ?: call.type

    fun continueRunAfterFailure(call: OperationCall): Boolean =
        registry[call.type to call.handler]?.continueRunAfterFailure == true

    fun mayResumeAfter(call: OperationCall, outcome: OperationDispatch): Boolean =
        outcome is OperationDispatch.Settled ||
            (outcome is OperationDispatch.Rejected && continueRunAfterFailure(call))

    /** Typed scheduling result: only a durable rejection can use a feature's failure fallback. */
    suspend fun dispatch(
        session: ConversationSession,
        call: OperationCall,
        timeoutMillis: Long = 120_000,
    ): OperationDispatch = try {
        OperationDispatch.Settled(execute(session, call, timeoutMillis))
    } catch (error: Exception) {
        // A deadline owned by this operation is not the user's cancellation of the owning run.
        currentCoroutineContext().ensureActive()
        val prior = session.state.value.executionLedger().operations[call.id]
        if (prior != null && prior.call != call) {
            OperationDispatch.NeedsReconciliation(error)
        } else when (prior?.status) {
            AttemptStatus.RUNNING, AttemptStatus.UNKNOWN -> OperationDispatch.NeedsReconciliation(error)
            AttemptStatus.CANCELLED -> OperationDispatch.Rejected(error)
            // A valid settled receipt returns from execute. An exception here failed current admission.
            AttemptStatus.SETTLED -> OperationDispatch.NeedsReconciliation(error)
            else -> {
                try {
                    val store = SessionOperationStore(session)
                    if (prior == null) store.prepare(call, emptyList())
                    store.cancel(call.id)
                    OperationDispatch.Rejected(error)
                } catch (storage: Exception) {
                    currentCoroutineContext().ensureActive()
                    storage.addSuppressed(error)
                    OperationDispatch.StorageBlocked(storage)
                }
            }
        }
    }

    suspend fun execute(
        session: ConversationSession,
        call: OperationCall,
        timeoutMillis: Long = 120_000,
    ): OperationResult {
        val definition = requireNotNull(registry[call.type to call.handler]) { "Operation version is unavailable" }
        check(authorized(call.handler, definition.capabilities) &&
            (call.caller?.let { authorized(it, definition.capabilities) } != false)) { "Operation is disabled or unauthorized" }
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
        require(call.frame.isNotEmpty() || snapshot.messageNodes.isEmpty()) { "Operation has no bound input frame" }
        val bound = snapshot.forOperationFrame(call.frame)
        val prepared = definition.prepare(bound, call.input)
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
                check(authorized(call.handler, definition.capabilities) &&
                    (call.caller?.let { authorized(it, definition.capabilities) } != false)) { "Operation authorization was revoked" }
                call.run?.let { ref ->
                    val run = requireNotNull(current.executionLedger().runs[ref.runId]) { "Owning run is missing" }
                    check(run.state.ref == ref && run.state.status == dev.rikkahub.plugins.protocol.RunStatus.ACTIVE) {
                        "Owning run ended or changed"
                    }
                }
                val view = current.forOperationFrame(call.frame)
                val sources = view.pluginSources().associateBy { it.id }
                check(prepared.sources.all { sources[it.id] == it }) { "Operation source changed" }
                prepared.validateAdoption(view)
            }
            val result = runner.execute(call, definition.capabilities, attempt, timeoutMillis,
                chargeModelAttempt = { store.chargeModelAttempt(call, ::validate) },
            ) {
                validate(session.state.value)
            }
            currentCoroutineContext().ensureActive()
            store.settle(call, attempt, result) { current ->
                validate(current)
                current.forOperationFrame(call.frame).validateOperationOutput(result, prepared.sources, definition.capabilities)
                prepared.adopt(current.forOperationFrame(call.frame, includeTail = true), result)
                    .restoreBrowsingSelection(current)
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
    frame = (run?.let { executionLedger().runs[it.runId]?.let { saved -> forInputPath(saved.inputIds).currentMessages } }
        ?: currentMessages).map { it.sourceStamp() },
)
