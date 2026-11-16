package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.PackageLease
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

data class OperationContext(
    val call: OperationCall,
    val attemptId: String,
    val capabilities: Set<String>,
    val beforeEffect: suspend () -> Unit = {},
    val beforeModelRequest: suspend () -> Unit = beforeEffect,
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
            val result = operation.execute(OperationContext(
                call,
                attemptId,
                granted.toSet(),
                beforeEffect,
                beforeModelRequest = {
                    currentCoroutineContext().ensureActive()
                    validate(call)
                    chargeModelAttempt()
                    currentCoroutineContext().ensureActive()
                    validate(call)
                },
            ))
            currentCoroutineContext().ensureActive()
            validate(call)
            result
        }
    }
}
