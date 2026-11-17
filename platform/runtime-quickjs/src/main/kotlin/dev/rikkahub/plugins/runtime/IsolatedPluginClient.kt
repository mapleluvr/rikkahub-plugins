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
import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.core.VerifiedPluginPackage
import dev.rikkahub.plugins.protocol.PackageLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
) {
    private val handle = UUID.randomUUID().toString()
    private val connected = CompletableDeferred<Messenger>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val sequence = AtomicInteger()
    private val calls = Mutex()
    private val closing = Mutex()

    @Volatile private var closed = false
    private var bound = false
    private var closeConfirmed = false
    var processId: Int = -1
        private set
    var processUid: Int = -1
        private set

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        val waiting = pending.remove(message.arg1)
        if (waiting == null || !waiting.complete(message.data)) {
            runCatching { RuntimeTransport.descriptor(message.data).close() }
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
            closed = true
            val error = IllegalStateException("Plugin runtime disconnected; uncertain work must be reconciled")
            connected.completeExceptionally(error)
            pending.values.forEach { it.completeExceptionally(error) }
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
            dev.rikkahub.plugins.core.PluginToolSchema.validate(pkg.manifest.tools.single { it.id == id }.parameters, input)
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
            throw error
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
        check(!closed && authorized(lease, pkg.manifest.capabilities)) { "Plugin permission or lease was revoked" }
    }

    private suspend fun exchange(action: Int, bytes: ByteArray? = null): String = withTimeout(15_000) {
        val remote = connected.await()
        val id = sequence.incrementAndGet()
        val response = CompletableDeferred<Bundle>()
        pending[id] = response
        try {
            val file = bytes?.let { RuntimeTransport.outgoing(context.cacheDir, it) }
            file.use { descriptor ->
                remote.send(Message.obtain().apply {
                    what = action
                    arg1 = id
                    replyTo = replies
                    data = Bundle().apply {
                        putString("handle", handle)
                        putString("hash", pkg.hash)
                        putLong("epoch", lease.activationEpoch)
                        if (descriptor != null) putParcelable("payload", descriptor)
                    }
                })
            }
            val bundle = response.await()
            bundle.getString("error")?.let { error(it) }
            RuntimeTransport.incoming(RuntimeTransport.descriptor(bundle)).toString(Charsets.UTF_8)
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
        ): IsolatedPluginClient {
            val snapshot = bytes.copyOf()
            val pkg = PluginArchive.decode(snapshot, lease.packageHash)
            require(lease.pluginId == pkg.manifest.id && lease.activationEpoch > 0)
            val client = IsolatedPluginClient(context.applicationContext, pkg, lease, authorized)
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
                throw error
            }
        }
    }
}
