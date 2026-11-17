package dev.rikkahub.plugins.runtime

import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.core.VerifiedPluginPackage
import dev.rikkahub.plugins.protocol.OperationResult
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginToolResult
import dev.rikkahub.plugins.protocol.PluginUiNode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Engine-owned functions never cross the wire. Identity and current grants are checked by the host. */
class PluginInstance private constructor(
    val pkg: VerifiedPluginPackage,
    val lease: PackageLease,
    private val authorized: (PackageLease, Set<String>) -> Boolean,
    private val readContext: suspend () -> JsonElement,
    timeoutMillis: Long,
) {
    private val calls = Mutex()

    @Volatile private var phase = Phase.PREPARING
    private val runtime = PluginRuntime(timeoutMillis) { request ->
        check(phase == Phase.EXECUTING) { "Host RPC is unavailable during registration or rendering" }
        val rpc = PluginContract.json.parseToJsonElement(request).jsonObject
        require(rpc.keys == setOf("method")) { "Invalid host RPC" }
        require(rpc.getValue("method").jsonPrimitive.content == "context.read") { "Unsupported host RPC" }
        require("context.read" in pkg.manifest.capabilities)
        requireAuthorized()
        val result = readContext()
        requireAuthorized()
        PluginContract.json.encodeToString(result)
    }

    suspend fun operation(id: String, input: JsonElement): OperationResult =
        PluginContract.result(pkg.manifest, invoke("operation", id, input))

    suspend fun tool(id: String, input: JsonElement): PluginToolResult =
        PluginContract.toolResult(invoke("tool", id, input))

    suspend fun panel(id: String, state: JsonElement = JsonNull): PluginUiNode =
        PluginContract.ui(pkg.manifest, invoke("panel", id, state))

    suspend fun close() {
        phase = Phase.CLOSED
        runtime.close()
    }

    private fun requireAuthorized() {
        check(phase != Phase.CLOSED && authorized(lease, pkg.manifest.capabilities)) {
            "Plugin lease is revoked or missing required permissions"
        }
    }

    private suspend fun invoke(kind: String, id: String, input: JsonElement): String = calls.withLock {
        check(phase == Phase.READY) { "Plugin instance is not ready" }
        requireAuthorized()
        val allowed = when (kind) {
            "operation" -> pkg.manifest.operations.map { it.id }
            "tool" -> pkg.manifest.tools.map { it.id }
            else -> pkg.manifest.panels.map { it.id }
        }
        require(id in allowed) { "Undeclared contribution" }
        if (kind == "tool") {
            dev.rikkahub.plugins.core.PluginToolSchema.validate(
                pkg.manifest.tools.single { it.id == id }.parameters, input
            )
        }
        phase = if (kind == "panel") Phase.RENDERING else Phase.EXECUTING
        try {
            val args = PluginContract.json.encodeToString(
                mapOf("kind" to kind, "id" to id, "input" to PluginContract.json.encodeToString(input))
            )
            val output = runtime.evaluate("await globalThis.__rikkaInvoke(" + args + ")")
            requireAuthorized()
            output
        } catch (error: Throwable) {
            close()
            throw error
        } finally {
            if (phase != Phase.CLOSED) phase = Phase.READY
        }
    }

    private suspend fun initialize() {
        try {
            val registrations = runtime.evaluate(
                BOOTSTRAP + "\n" + pkg.source + "\n;await globalThis.__rikkaInitialize()"
            )
            val actual = PluginContract.json.parseToJsonElement(registrations).jsonObject
            val expected = mapOf(
                "operation" to pkg.manifest.operations.map { it.id },
                "tool" to pkg.manifest.tools.map { it.id },
                "panel" to pkg.manifest.panels.map { it.id },
            )
            require(actual.keys == expected.keys)
            expected.forEach { (kind, ids) ->
                require(actual.getValue(kind).jsonArray.map { it.jsonPrimitive.content }.toSet() == ids.toSet()) {
                    "Registered $kind contributions disagree with manifest"
                }
            }
            phase = Phase.READY
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private enum class Phase { PREPARING, READY, EXECUTING, RENDERING, CLOSED }

    companion object {
        suspend fun prepare(
            pkg: VerifiedPluginPackage,
            lease: PackageLease,
            authorized: (PackageLease, Set<String>) -> Boolean,
            timeoutMillis: Long = 10_000,
            readContext: suspend () -> JsonElement = { JsonNull },
        ): PluginInstance {
            require(lease.pluginId == pkg.manifest.id && lease.packageHash == pkg.hash && lease.activationEpoch > 0)
            return PluginInstance(pkg, lease, authorized, readContext, timeoutMillis).also { it.initialize() }
        }

        private val BOOTSTRAP = """
            (() => {
              const handlers = {operation: new Map(), tool: new Map(), panel: new Map()};
              let sealed = false;
              const register = kind => (id, fn) => {
                if (sealed || typeof id !== 'string' || typeof fn !== 'function' || handlers[kind].has(id))
                  throw new Error('Invalid or duplicate contribution');
                handlers[kind].set(id, fn);
              };
              const api = Object.freeze({
                registerOperation: register('operation'),
                registerTool: register('tool'),
                registerPanel: register('panel')
              });
              const context = Object.freeze({
                readMessages: async () => JSON.parse(await hostRequest(JSON.stringify({method: 'context.read'})))
              });
              Object.defineProperty(globalThis, '__rikkaInitialize', {value: async () => {
                if (sealed || typeof globalThis.rikkaPlugin !== 'function') throw new Error('Missing plugin factory');
                await globalThis.rikkaPlugin(api);
                sealed = true;
                return JSON.stringify(Object.fromEntries(Object.entries(handlers).map(([k,v]) => [k, [...v.keys()]])));
              }});
              Object.defineProperty(globalThis, '__rikkaInvoke', {value: async ({kind, id, input}) => {
                if (!sealed || !handlers[kind]?.has(id)) throw new Error('Unknown contribution');
                return JSON.stringify(await handlers[kind].get(id)(JSON.parse(input), context));
              }});
            })();
        """.trimIndent()
    }
}
