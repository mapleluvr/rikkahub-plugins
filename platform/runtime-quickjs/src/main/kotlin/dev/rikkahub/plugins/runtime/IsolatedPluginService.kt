package dev.rikkahub.plugins.runtime

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.PackageLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** No app UID, storage or network permissions. The host supplies only authorized invocation snapshots. */
class IsolatedPluginService : Service() {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val pending = ConcurrentHashMap<String, Job>()
    private val registration = Mutex()
    private class Entry(val instance: PluginInstance) {
        val lock = Mutex()
        var context: JsonElement = JsonNull
    }

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        val handle = message.data.getString("handle")
        val response = message.replyTo
        val request = message.arg1
        val action = message.what
        val payload = message.data
        if (handle == null || handle.length > 80 || response == null) {
            runCatching { RuntimeTransport.descriptor(payload).close() }
        } else if (action == RuntimeTransport.CLOSE) {
            val work = pending[handle]
            work?.cancel()
            scope.launch {
                work?.join()
                entries.remove(handle)?.instance?.close()
                respond(response, request, Result.success("closed"))
            }
        } else if (pending.containsKey(handle)) {
            runCatching { RuntimeTransport.descriptor(payload).close() }
            scope.launch { respond(response, request, Result.failure(IllegalStateException("Instance is busy"))) }
        } else {
            val work = scope.launch(start = CoroutineStart.LAZY) {
                val result = runCatching {
                    val bytes = RuntimeTransport.incoming(RuntimeTransport.descriptor(payload))
                    when (action) {
                        RuntimeTransport.OPEN -> registration.withLock {
                            check(entries.size < 4 && !entries.containsKey(handle)) { "Runtime capacity exceeded" }
                            val pkg = PluginArchive.decode(bytes, payload.getString("hash"))
                            val lease = PackageLease(pkg.manifest.id, pkg.hash, payload.getLong("epoch"))
                            lateinit var entry: Entry
                            val instance = PluginInstance.prepare(pkg, lease, { _, _ -> true }) { entry.context }
                            entry = Entry(instance)
                            try {
                                currentCoroutineContext().ensureActive()
                                entries[handle] = entry
                            } catch (error: Throwable) {
                                instance.close()
                                throw error
                            }
                            PluginContract.json.encodeToString(
                                mapOf("pid" to Process.myPid(), "uid" to Process.myUid())
                            )
                        }

                        RuntimeTransport.CALL -> {
                            val entry = requireNotNull(entries[handle]) { "Plugin instance is unavailable" }
                            entry.lock.withLock {
                                val call = PluginContract.json.parseToJsonElement(
                                    bytes.toString(Charsets.UTF_8)
                                ).jsonObject
                                val kind = call.getValue("kind").jsonPrimitive.content
                                val id = call.getValue("id").jsonPrimitive.content
                                val input = call.getValue("input")
                                entry.context =
                                    if (kind != "panel" && "context.read" in entry.instance.pkg.manifest.capabilities) {
                                        call.getValue("context")
                                    } else {
                                        JsonNull
                                    }
                                try {
                                    when (kind) {
                                        "operation" -> PluginContract.json.encodeToString(
                                            entry.instance.operation(id, input)
                                        )

                                        "tool" -> PluginContract.json.encodeToString(entry.instance.tool(id, input))

                                        "panel" -> PluginContract.json.encodeToString(entry.instance.panel(id, input))

                                        else -> error("Unsupported invocation")
                                    }
                                } finally {
                                    entry.context = JsonNull
                                }
                            }
                        }

                        else -> error("Unknown runtime command")
                    }
                }
                withContext(NonCancellable) {
                    if (result.isFailure) entries.remove(handle)?.instance?.close()
                    pending.remove(handle)
                    respond(response, request, result)
                }
            }
            pending[handle] = work
            work.start()
        }
        true
    })

    private suspend fun respond(target: Messenger, request: Int, result: Result<String>) {
        val bytes = result.getOrNull()?.toByteArray()
        val pipe = bytes?.let { RuntimeTransport.responsePipe() }
        try {
            target.send(Message.obtain().apply {
                arg1 = request
                data = Bundle().apply {
                    if (pipe != null) putParcelable("payload", pipe[0])
                    result.exceptionOrNull()?.let { putString("error", (it.message ?: "Plugin failed").take(1024)) }
                }
            })
            pipe?.get(0)?.close()
            if (pipe != null) ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes!!) }
        } catch (_: Exception) {
            pipe?.forEach { runCatching { it.close() } }
        }
    }

    override fun onBind(intent: Intent): IBinder = incoming.binder

    override fun onDestroy() {
        scope.launch {
            pending.values.toList().forEach { it.cancelAndJoin() }
            entries.values.forEach { it.instance.close() }
            entries.clear()
            scope.cancel()
        }
        super.onDestroy()
    }
}
