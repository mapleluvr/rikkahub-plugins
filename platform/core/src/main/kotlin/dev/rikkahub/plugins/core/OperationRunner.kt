package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.OperationCall
import dev.rikkahub.plugins.protocol.OperationResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement

data class OperationDefinition(
    val id: String,
    val requiredCapabilities: Set<String>,
    val execute: suspend (JsonElement) -> OperationResult
)

/** Executes a fixed registry snapshot; persistence/deduplication belong to the session owner. */
class OperationRunner(definitions: List<OperationDefinition>) {
    private val operations = definitions.associateBy { it.id }

    init {
        require(operations.size == definitions.size) { "Duplicate operation ID" }
        require(definitions.all { it.id.isNotBlank() }) { "Empty operation ID" }
    }

    suspend fun execute(
        call: OperationCall,
        granted: Set<String>,
        timeoutMillis: Long = 30_000,
        validate: suspend (OperationCall) -> Unit
    ): OperationResult {
        require(call.id.isNotBlank() && timeoutMillis > 0)
        val operation = requireNotNull(operations[call.type]) { "Unknown operation: ${call.type}" }
        require(granted.containsAll(operation.requiredCapabilities)) { "Missing operation capabilities" }
        return withTimeout(timeoutMillis) {
            validate(call)
            currentCoroutineContext().ensureActive()
            val result = operation.execute(call.input)
            currentCoroutineContext().ensureActive()
            validate(call)
            result
        }
    }
}
