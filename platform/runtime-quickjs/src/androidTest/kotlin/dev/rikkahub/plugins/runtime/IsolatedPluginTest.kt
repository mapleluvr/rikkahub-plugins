package dev.rikkahub.plugins.runtime

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.ExecutionResultUnavailable
import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginOperationDeclaration
import dev.rikkahub.plugins.protocol.PluginPanelDeclaration
import dev.rikkahub.plugins.protocol.PluginToolDeclaration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IsolatedPluginTest {
    @Test fun lostRealOpenReplyIsAnInternalDeadlineNotCallerStop() = runBlocking {
        val context = DropOpenReplyContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val bytes = readyArchive()
        val pkg = PluginArchive.decode(bytes)
        val failure = runCatching {
            withTimeout(25_000) {
                IsolatedPluginClient.prepare(context, bytes, PackageLease(pkg.manifest.id, pkg.hash, 1)) { _, _ ->
                    true
                }
                    .close()
            }
        }.exceptionOrNull()
        assertTrue(
            "The real Service must have finished OPEN before its reply was discarded",
            context.openReply.isCompleted
        )
        assertTrue(
            "An internal OPEN deadline must not cancel the logical caller: $failure",
            failure is ExecutionResultUnavailable
        )
        assertTrue(currentCoroutineContext().isActive)
        assertTrue("The failed prepare must unbind after CLOSE cleanup", context.connections.isEmpty())
    }

    @Test fun actualCallerStopWhileRealOpenReplyIsLostRemainsCancellation() = runBlocking {
        val context = DropOpenReplyContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val bytes = readyArchive()
        val pkg = PluginArchive.decode(bytes)
        val failure = CompletableDeferred<Throwable>()
        val call = async(Dispatchers.Default) {
            try {
                IsolatedPluginClient.prepare(context, bytes, PackageLease(pkg.manifest.id, pkg.hash, 1)) { _, _ ->
                    true
                }
                    .close()
            } catch (error: Throwable) {
                failure.complete(error)
                throw error
            }
        }
        try {
            // Preparation has its own 15-second production deadline; Stop starts after real OPEN was observed.
            withTimeout(20_000) { context.openReply.await() }
            withTimeout(6_000) { call.cancelAndJoin() }
            assertTrue(failure.await() is CancellationException)
            assertFalse(failure.await() is ExecutionResultUnavailable)
            assertTrue("Caller Stop must unbind after CLOSE cleanup", context.connections.isEmpty())
        } finally {
            call.cancelAndJoin()
        }
    }

    private fun readyArchive() = archive("""
        globalThis.rikkaPlugin = rk => {
          rk.registerOperation('report', () => ({artifacts:[]}));
          rk.registerPanel('main', () => ({type:'text', text:'ready'}));
        };
    """.trimIndent())

    /** Forward real OPEN and CLOSE unchanged except for deliberately losing the OPEN acknowledgment. */
    private class DropOpenReplyContext(base: Context) : ContextWrapper(base) {
        val openReply = CompletableDeferred<Unit>()
        val connections = ConcurrentHashMap<ServiceConnection, ServiceConnection>()
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val observer = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    connection.onServiceConnected(name, object : IBinder by service {
                        override fun queryLocalInterface(descriptor: String): IInterface? = null
                        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            if (code !=
                                IBinder.FIRST_CALL_TRANSACTION) {
                                return service.transact(code, data, reply, flags)
                            }
                            val wire = Parcel.obtain()
                            var decoded: Message? = null
                            try {
                                wire.appendFrom(data, 0, data.dataSize())
                                wire.setDataPosition(0)
                                wire.enforceInterface("android.os.IMessenger")
                                check(wire.readInt() == 1)
                                val message = Message.CREATOR.createFromParcel(wire)
                                decoded = message
                                if (message.what !=
                                    RuntimeTransport.OPEN) {
                                    return service.transact(code, data, reply, flags)
                                }
                                val target = checkNotNull(message.replyTo)
                                val request = message.arg1
                                message.replyTo = Messenger(Handler(Looper.getMainLooper()) { acknowledgment ->
                                    if (acknowledgment.arg1 == request) {
                                        RuntimeTransport.discard(acknowledgment.data)
                                        openReply.complete(Unit)
                                    } else {
                                        target.send(acknowledgment)
                                    }
                                    true
                                })
                                wire.setDataSize(0)
                                wire.setDataPosition(0)
                                wire.writeInterfaceToken("android.os.IMessenger")
                                wire.writeInt(1)
                                message.writeToParcel(wire, 0)
                                wire.setDataPosition(0)
                                return service.transact(code, wire, reply, flags)
                            } finally {
                                decoded?.let { RuntimeTransport.discard(it.data) }
                                wire.recycle()
                            }
                        }
                    })
                }
                override fun onServiceDisconnected(name: ComponentName) = connection.onServiceDisconnected(name)
                override fun onBindingDied(name: ComponentName) = connection.onBindingDied(name)
                override fun onNullBinding(name: ComponentName) = connection.onNullBinding(name)
            }
            connections[connection] = observer
            return super.bindService(intent, observer, flags).also { if (!it) connections.remove(connection) }
        }
        override fun unbindService(connection: ServiceConnection) =
            super.unbindService(checkNotNull(connections.remove(connection)))
    }

    @Test fun isolatedUidKeepsItsCallbacksAndTransfersLargeResultsThenCloses() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes =
            archive("""
            globalThis.rikkaPlugin = rk => {
              let count = 0;
              rk.registerOperation('report', async (_, ctx) => ({
                artifacts:[{type:'report', content:(await ctx.readMessages()) + ':' + ++count}]
              }));
              rk.registerPanel('main', () => ({type:'text', text:'Calls: ' + count}));
            };
            """.trimIndent())
        val pkg = PluginArchive.decode(bytes)
        var granted = true
        val client = IsolatedPluginClient.prepare(context, bytes, PackageLease(pkg.manifest.id, pkg.hash, 1)) { _, _ ->
            granted
        }
        try {
            assertNotEquals(Process.myPid(), client.processId)
            assertNotEquals(Process.myUid(), client.processUid)
            assertTrue("Actual isolated Android UID required", client.processUid % 100000 >= 90000)
            val large = "source".repeat(40000)
            val result = PluginContract.result(pkg.manifest, client.invoke("operation", "report", JsonNull) {
                JsonPrimitive(large)
            })
            assertEquals(large + ":1", result.artifacts.single().content.jsonPrimitive.content)
            assertEquals("Calls: 1", PluginContract.ui(pkg.manifest, client.invoke("panel", "main", JsonNull)).text)
            granted = false
            assertTrue(runCatching { client.invoke("operation", "report", JsonNull) }.isFailure)
            assertTrue("Service must acknowledge resource release", client.close())
        } finally {
            client.close()
        }
        assertTrue(runCatching { client.invoke("panel", "main", JsonNull) }.isFailure)
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("plugin-rpc-") })
    }

    @Test fun closingActiveNativeLoopDrainsItAndFreshInstanceWorks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes =
            archive("""
            globalThis.rikkaPlugin = rk => {
              rk.registerOperation('report', () => { while(true) {} });
              rk.registerPanel('main', () => ({type:'text', text:'ready'}));
            };
            """.trimIndent())
        val pkg = PluginArchive.decode(bytes)
        val lease = PackageLease(pkg.manifest.id, pkg.hash, 1)
        val client = IsolatedPluginClient.prepare(context, bytes, lease) { _, _ -> true }
        try {
            val call = async(Dispatchers.Default) { runCatching { client.invoke("operation", "report", JsonNull) } }
            delay(100)
            withTimeout(6000) { assertTrue("Service must acknowledge drained work", client.close()) }
            withTimeout(3000) { assertTrue(call.await().isFailure) }
        } finally {
            client.close()
        }
        val fresh = IsolatedPluginClient.prepare(context, bytes, lease.copy(activationEpoch = 2)) { _, _ -> true }
        try {
            assertEquals("ready", PluginContract.ui(pkg.manifest, fresh.invoke("panel", "main", JsonNull)).text)
        } finally {
            fresh.close()
        }
    }

    @Test fun invalidToolArgumentsCannotReadContextOrReachJavaScript() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = PluginManifest(
            "dev.example.validation",
            "Validation",
            "1.0.0",
            capabilities = setOf("context.read", "tools.register"),
            tools = listOf(PluginToolDeclaration(
                "inspect",
                "Inspect",
                "Inspect supplied text",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}"""
                ).jsonObject
            )),
        )
        val bytes =
            archive("""
            globalThis.rikkaPlugin = rk => {
              let calls = 0;
              rk.registerTool('inspect', async (input, ctx) => {
                await ctx.readMessages();
                return {content: input.text + ':' + ++calls};
              });
            };
            """.trimIndent(), manifest)
        val pkg = PluginArchive.decode(bytes)
        val client = IsolatedPluginClient.prepare(context, bytes, PackageLease(manifest.id, pkg.hash, 1)) { _, _ ->
            true
        }
        var reads = 0
        try {
            for (input in listOf("{}", """{"text":1}""", """{"text":"ok","extra":true}""")) {
                assertTrue(runCatching {
                    client.invoke("tool", "inspect", Json.parseToJsonElement(input)) {
                        reads++
                        JsonNull
                    }
                }.isFailure)
            }
            assertEquals(0, reads)
            val result = client.invoke("tool", "inspect", JsonObject(mapOf("text" to JsonPrimitive("ok")))) {
                reads++
                JsonNull
            }
            assertEquals("ok:1", PluginContract.toolResult(result).content)
            assertEquals(1, reads)
        } finally {
            client.close()
        }
    }

    @Test fun closeDrainsUnreadLargeBinderReplyBeforeAcknowledging() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes =
            archive("""
            globalThis.rikkaPlugin = rk => {
              rk.registerOperation('report', () => ({artifacts:[{type:'report',content:'x'.repeat(240000)}]}));
              rk.registerPanel('main', () => ({type:'text',text:'ready'}));
            };
            """.trimIndent())
        val pkg = PluginArchive.decode(bytes)
        val connected = CompletableDeferred<Messenger>()
        val replies = Channel<Bundle>(Channel.UNLIMITED, onUndeliveredElement = RuntimeTransport::discard)
        val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (replies.trySend(message.data).isFailure) RuntimeTransport.discard(message.data)
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(Messenger(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val bound = withContext(Dispatchers.Main) {
            context.bindService(
                Intent(context, IsolatedPluginService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }
        assertTrue(bound)
        try {
            val remote = withTimeout(5000) { connected.await() }
            suspend fun send(handle: String, action: Int, payload: ByteArray? = null) {
                RuntimeTransport.withOutgoing(context.cacheDir, payload) { descriptor ->
                    remote.send(Message.obtain().apply {
                        what = action
                        replyTo = messenger
                        data = Bundle().apply {
                            putString("handle", handle)
                            putString("hash", pkg.hash)
                            putLong("epoch", 1)
                            descriptor?.let { putParcelable("payload", it) }
                        }
                    })
                }
            }
            repeat(8) { iteration ->
                val handle = "unread-$iteration"
                send(handle, RuntimeTransport.OPEN, bytes)
                val opened = withTimeout(5000) { replies.receive() }
                val info = PluginContract.json.parseToJsonElement(
                    RuntimeTransport.incoming(RuntimeTransport.descriptor(opened)).toString(Charsets.UTF_8)
                ).jsonObject
                assertNotEquals(Process.myUid().toString(), info.getValue("uid").jsonPrimitive.content)
                send(
                    handle,
                    RuntimeTransport.CALL,
                    """{"kind":"operation","id":"report","input":null,"context":null}""".toByteArray()
                )
                val unread = RuntimeTransport.descriptor(withTimeout(5000) { replies.receive() })
                try {
                    send(handle, RuntimeTransport.CLOSE)
                    val closed = withTimeout(5000) { replies.receive() }
                    assertEquals(
                        "closed",
                        RuntimeTransport.incoming(RuntimeTransport.descriptor(closed))
                            .toString(Charsets.UTF_8)
                    )
                    // CLOSE must already have closed the writer. A blocking old writer would finish the full reply.
                    val partial = withTimeout(2000) { RuntimeTransport.incoming(unread) }
                    assertTrue("Unread writer survived CLOSE", partial.size < 240000)
                } finally {
                    unread.close()
                }
            }
        } finally {
            replies.cancel()
            withContext(Dispatchers.Main) { context.unbindService(connection) }
        }
    }

    private fun archive(
        source: String,
        manifest: PluginManifest = PluginManifest(
            "dev.example.report",
            "Report",
            "1.0.0",
            capabilities = setOf("context.read", "artifacts.write", "ui.actions"),
            operations = listOf(PluginOperationDeclaration("report", "Report")),
            panels = listOf(PluginPanelDeclaration("main", "Report")),
        ),
    ): ByteArray = ByteArrayOutputStream().let { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, text) in mapOf("manifest.json" to Json.encodeToString(manifest), "main.js" to source)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }
}
