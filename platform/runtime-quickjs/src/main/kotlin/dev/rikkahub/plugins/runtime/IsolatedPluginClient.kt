package dev.rikkahub.plugins.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import dev.rikkahub.plugins.core.ExecutionAuthority
import dev.rikkahub.plugins.core.ExecutionResultUnavailable
import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.core.VerifiedPluginPackage
import dev.rikkahub.plugins.protocol.PackageLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One version/scope lease in the isolated service. App-side checks remain authoritative. */
class IsolatedPluginClient private constructor(
    private val context: Context,
    val pkg: VerifiedPluginPackage,
    val lease: PackageLease,
    private val authorized: (PackageLease, Set<String>) -> Boolean,
    private val execution: ExecutionAuthority.Scope?,
) {
    private val handle = UUID.randomUUID().toString()
    private val connected = CompletableDeferred<Messenger>()
    private val pending = ConcurrentHashMap<Int, Channel<Bundle>>()
    private val sequence = AtomicInteger()
    private val calls = Mutex()
    private val closing = Mutex()

    @Volatile private var closed = false

    @Volatile private var disconnection: ExecutionResultUnavailable? = null
    private var bound = false
    private var closeConfirmed = false
    var processId: Int = -1
        private set
    var processUid: Int = -1
        private set

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        val waiting = pending[message.arg1]
        if (waiting == null || waiting.trySend(message.data).isFailure) {
            RuntimeTransport.discard(message.data)
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            connected.complete(Messenger(binder))
        }
        override fun onServiceDisconnected(name: ComponentName) = failed()
        override fun onBindingDied(name: ComponentName) = failed()
        override fun onNullBinding(name: ComponentName) = failed()
        private fun failed() {
            val error = ExecutionResultUnavailable("Plugin runtime disconnected; uncertain work must be reconciled")
            disconnection = error
            closed = true
            connected.completeExceptionally(error)
            pending.values.forEach { it.close(error) }
        }
    }

    suspend fun invoke(
        kind: String,
        id: String,
        input: JsonElement,
        readContext: suspend () -> JsonElement = { JsonNull },
    ): String = calls.withLock {
        checkAuthorized()
        val ids = when (kind) {
            "operation" -> pkg.manifest.operations.map { it.id }
            "tool" -> pkg.manifest.tools.map { it.id }
            "panel" -> pkg.manifest.panels.map { it.id }
            else -> error("Unsupported invocation")
        }
        require(id in ids)
        if (kind == "tool") {
            dev.rikkahub.plugins.core.PluginToolSchema.validate(pkg.manifest.tools.single {
                it.id == id
            }.parameters, input)
        }
        try {
            val messages = if (kind != "panel" && "context.read" in pkg.manifest.capabilities) {
                readContext()
            } else {
                JsonNull
            }
            checkAuthorized()
            val body = PluginContract.json.encodeToString(JsonObject(mapOf(
                "kind" to JsonPrimitive(kind),
                "id" to JsonPrimitive(id),
                "input" to input,
                "context" to messages,
            )))
            val result = exchange(RuntimeTransport.CALL, body.toByteArray())
            checkAuthorized()
            when (kind) {
                "operation" -> PluginContract.result(pkg.manifest, result)
                "tool" -> PluginContract.toolResult(result)
                "panel" -> PluginContract.ui(pkg.manifest, result)
            }
            result
        } catch (error: Throwable) {
            close()
            rethrowRuntimeFailure(error)
        }
    }

    private suspend fun rethrowRuntimeFailure(error: Throwable): Nothing {
        // OPEN and CALL have internal deadlines; only the actual caller can supply a Stop signal.
        // Recheck after cleanup as Stop may have won while the runtime was being drained.
        currentCoroutineContext().ensureActive()
        throw if (error is TimeoutCancellationException) {
            ExecutionResultUnavailable("Plugin runtime request timed out; uncertain work must be reconciled", error)
        } else {
            error
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        closing.withLock {
            val wasClosed = closed
            closed = true
            pending.values.forEach { it.cancel() }
            pending.clear()
            if (!wasClosed && connected.isCompleted && !connected.isCancelled) {
                closeConfirmed = runCatching {
                    withTimeout(5_000) { exchange(RuntimeTransport.CLOSE) == "closed" }
                }.getOrDefault(false)
            }
            withContext(Dispatchers.Main.immediate) {
                if (bound) {
                    context.unbindService(connection)
                    bound = false
                }
            }
            closeConfirmed
        }
    }

    private fun checkAuthorized() {
        execution?.check()
        disconnection?.let { throw it }
        check(!closed && authorized(lease, pkg.manifest.capabilities)) { "Plugin permission or lease was revoked" }
    }

    private suspend fun exchange(action: Int, bytes: ByteArray? = null): String = withTimeout(15_000) {
        val remote = connected.await()
        val id = sequence.incrementAndGet()
        val response = RuntimeTransport.replies()
        pending[id] = response
        try {
            RuntimeTransport.withOutgoing(context.cacheDir, bytes) { descriptor ->
                if (action == RuntimeTransport.CALL) {
                    checkAuthorized()
                    execution?.binding?.beforeDispatch?.invoke()
                    checkAuthorized()
                }
                val message = Message.obtain().apply {
                    what = action
                    arg1 = id
                    replyTo = replies
                    data = Bundle().apply {
                        putString("handle", handle)
                        putString("hash", pkg.hash)
                        putLong("epoch", lease.activationEpoch)
                        if (descriptor != null) putParcelable("payload", descriptor)
                    }
                }
                // Payload preparation and durable admission stay outside the lock; CLOSE is cleanup only.
                try {
                    if (action == RuntimeTransport.CLOSE || execution == null) {
                        remote.send(message)
                    } else {
                        execution.dispatch { remote.send(message) }
                    }
                } catch (error: RemoteException) {
                    throw ExecutionResultUnavailable(
                        "Plugin runtime transport failed; uncertain work must be reconciled",
                        error
                    )
                }
            }
            val bundle = response.receive()
            try {
                bundle.getString("error")?.let {
                    if (bundle.getBoolean(RuntimeTransport.RESULT_UNAVAILABLE)) throw ExecutionResultUnavailable(it)
                    error(it)
                }
                RuntimeTransport.incoming(RuntimeTransport.descriptor(bundle)).toString(Charsets.UTF_8)
            } finally {
                RuntimeTransport.discard(bundle)
            }
        } finally {
            pending.remove(id)
            response.cancel()
        }
    }

    companion object {
        suspend fun prepare(
            context: Context,
            bytes: ByteArray,
            lease: PackageLease,
            authorized: (PackageLease, Set<String>) -> Boolean,
        ): IsolatedPluginClient = prepare(context, bytes, lease, authorized, null)

        internal suspend fun prepare(
            context: Context,
            bytes: ByteArray,
            lease: PackageLease,
            authorized: (PackageLease, Set<String>) -> Boolean,
            execution: ExecutionAuthority.Scope?,
        ): IsolatedPluginClient {
            val snapshot = bytes.copyOf()
            val pkg = PluginArchive.decode(snapshot, lease.packageHash)
            require(lease.pluginId == pkg.manifest.id && lease.activationEpoch > 0)
            val client = IsolatedPluginClient(context.applicationContext, pkg, lease, authorized, execution)
            try {
                withContext(Dispatchers.Main.immediate) {
                    client.bound = client.context.bindService(
                        Intent(client.context, IsolatedPluginService::class.java),
                        client.connection,
                        Context.BIND_AUTO_CREATE,
                    )
                    check(client.bound) { "Unable to bind plugin runtime" }
                }
                val info = PluginContract.json.parseToJsonElement(
                    client.exchange(RuntimeTransport.OPEN, snapshot)
                ).jsonObject
                client.processId = info.getValue("pid").jsonPrimitive.int
                client.processUid = info.getValue("uid").jsonPrimitive.int
                return client
            } catch (error: Throwable) {
                client.close()
                client.rethrowRuntimeFailure(error)
            }
        }
    }
}
