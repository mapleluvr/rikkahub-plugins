package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.PackageLease
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class OperationContext(
    val call: OperationCall,
    val attemptId: String,
    val capabilities: Set<String>,
    val beforeEffect: suspend () -> Unit = {},
    val beforeModelRequest: suspend () -> Unit = beforeEffect,
    val modelRequests: ModelRequests = ModelRequests.fromPermission(beforeModelRequest),
    val executionBinding: ExecutionBinding = ExecutionBinding(listOfNotNull(call.caller, call.handler)),
)

data class OperationDefinition(
    val id: String,
    val lease: PackageLease,
    val requiredCapabilities: Set<String>,
    val execute: suspend (OperationContext) -> OperationResult
)

/** Executes a fixed registry snapshot; persistence/deduplication belong to the session owner. */
class OperationRunner(definitions: List<OperationDefinition>) {
    private val operations = definitions.associateBy { it.id to it.lease }

    init {
        require(operations.size == definitions.size) { "Duplicate operation ID" }
        require(definitions.all { it.id.isNotBlank() }) { "Empty operation ID" }
    }

    suspend fun execute(
        call: OperationCall,
        granted: Set<String>,
        attemptId: String,
        timeoutMillis: Long = 30_000,
        chargeModelAttempt: suspend () -> Unit = {},
        admitEffect: suspend () -> Unit = {},
        executionBinding: ExecutionBinding = ExecutionBinding(listOfNotNull(call.caller, call.handler)),
        modelJournal: ModelRequestJournal? = null,
        validateModelDispatch: suspend () -> Unit = {},
        validate: suspend (OperationCall) -> Unit
    ): OperationResult {
        require(call.id.isNotBlank() && attemptId.isNotBlank() && timeoutMillis > 0)
        val operation =
            requireNotNull(operations[call.type to call.handler]) {
                "Unknown operation or expired handler lease: ${call.type}"
            }
        require(granted.containsAll(operation.requiredCapabilities)) { "Missing operation capabilities" }
        return withTimeout(timeoutMillis) {
            validate(call)
            currentCoroutineContext().ensureActive()
            val beforeEffect: suspend () -> Unit = {
                currentCoroutineContext().ensureActive()
                validate(call)
                admitEffect()
                currentCoroutineContext().ensureActive()
                validate(call)
            }
            val validateModel: suspend () -> Unit = {
                currentCoroutineContext().ensureActive()
                validate(call)
                validateModelDispatch()
            }
            // Compatibility has no durable refund protocol, but shares the model job's ownership.
            val ownedModels = JournaledModelRequests(
                modelJournal ?: object : ModelRequestJournal {
                    override suspend fun reserve(id: String) = chargeModelAttempt()
                    override suspend fun start(id: String) { }
                    override suspend fun refuse(id: String) { }
                    override suspend fun confirm(id: String) { }
                },
                validateModel,
            )
            val modelRequests: ModelRequests = ownedModels
            val unfinishedModels: Boolean
            val result = try {
                operation.execute(OperationContext(
                    call,
                    attemptId,
                    granted.toSet(),
                    beforeEffect,
                    beforeModelRequest = modelRequests,
                    modelRequests = modelRequests,
                    executionBinding = executionBinding,
                ))
            } finally {
                // A detached CPU builder need not be joined, but its admitted model work must drain
                // before the dispatcher classifies durable receipts or accepts a returned result.
                unfinishedModels = withContext(NonCancellable) { ownedModels.closeAndDrain() }
            }
            check(!unfinishedModels) { "Operation returned with an unfinished model job" }
            currentCoroutineContext().ensureActive()
            validate(call)
            result
        }
    }
}
