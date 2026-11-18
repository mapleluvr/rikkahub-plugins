package dev.rikkahub.plugins.testing

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
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.core.PluginContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real Binder/QuickJS fault boundary shared by runtime and app instrumentation tests.
 * Tiny OPEN bytes are observed and re-delivered unchanged to bind PID/UID to this connection.
 * Held CALL replies retain the original real pipe. Header/data corruption, when requested, is explicit.
 */
class IsolatedReplyFaultContext(
    base: Context,
    private val holdReply: Boolean = false,
    private val delayDisconnect: Boolean = false,
    private val mutateHeader: (Bundle) -> Unit = {},
    private val replacementPayload: ByteArray? = null,
    private val storage: File? = null,
) : ContextWrapper(base) {
    data class Identity(val pid: Int, val uid: Int)

    class HeldReply internal constructor(
        private val target: Messenger,
        val message: Message,
        val identity: Identity,
        val binder: IBinder,
        internal val disconnected: CompletableDeferred<Unit>,
    ) {
        private var disposed = false

        fun forwardOriginalPipe() {
            check(!disposed)
            try {
                transfer(target, message)
            } finally {
                dispose()
            }
        }

        fun dispose() {
            if (!disposed) {
                disposed = true
                discard(message.data)
            }
        }
    }

    val connections = ConcurrentHashMap<ServiceConnection, ServiceConnection>()
    val callsSent = AtomicInteger()
    val held = CompletableDeferred<HeldReply>()
    private val delayed = ConcurrentLinkedQueue<() -> Unit>()

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = directory("files") ?: super.getFilesDir()
    override fun getNoBackupFilesDir(): File = directory("no-backup") ?: super.getNoBackupFilesDir()
    override fun getCacheDir(): File = directory("cache") ?: super.getCacheDir()

    private fun directory(name: String): File? = storage?.let { File(it, name).apply { mkdirs() } }

    override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
        val observed = object : ServiceConnection {
            private var identity: Identity? = null
            private val disconnected = CompletableDeferred<Unit>()

            private fun lost(callback: () -> Unit) {
                disconnected.complete(Unit)
                if (delayDisconnect) delayed.add(callback) else callback()
            }

            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connection.onServiceConnected(name, object : IBinder by service {
                    override fun queryLocalInterface(descriptor: String): IInterface? = null

                    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                        if (code != IBinder.FIRST_CALL_TRANSACTION) return service.transact(code, data, reply, flags)
                        val wire = Parcel.obtain()
                        var decoded: Message? = null
                        try {
                            wire.appendFrom(data, 0, data.dataSize())
                            wire.setDataPosition(0)
                            wire.enforceInterface("android.os.IMessenger")
                            check(wire.readInt() == 1)
                            val message = Message.CREATOR.createFromParcel(wire)
                            decoded = message
                            if (message.what != OPEN && message.what != CALL) {
                                return service.transact(code, data, reply, flags)
                            }
                            val action = message.what
                            val target = checkNotNull(message.replyTo)
                            message.replyTo = Messenger(Handler(Looper.getMainLooper()) { acknowledgment ->
                                val received = Message.obtain(acknowledgment)
                                var retained = false
                                try {
                                    if (action == OPEN && received.data.getString("error") == null) {
                                        val bytes = ParcelFileDescriptor.AutoCloseInputStream(
                                            descriptor(received.data)
                                        ).use {
                                            PluginArchive.readBounded(it, 64 * 1024)
                                        }
                                        val metadata = PluginContract.json.parseToJsonElement(
                                            bytes.toString(Charsets.UTF_8)
                                        ).jsonObject
                                        identity =
                                            Identity(
                                                metadata.getValue("pid").jsonPrimitive.int,
                                                metadata.getValue("uid").jsonPrimitive.int
                                            )
                                        withFilePayload(received, bytes) { transfer(target, received) }
                                    } else if (action == CALL && received.data.getString("error") == null) {
                                        mutateHeader(received.data)
                                        if (holdReply) {
                                            val accepted =
                                                HeldReply(
                                                    target,
                                                    received,
                                                    checkNotNull(identity),
                                                    service,
                                                    disconnected
                                                )
                                            retained = held.complete(accepted)
                                            if (!retained) accepted.dispose()
                                            return@Handler true // Ownership moved to HeldReply when retained.
                                        }
                                        if (replacementPayload == null) {
                                            transfer(target, received)
                                        } else {
                                            withFilePayload(received, replacementPayload) { transfer(target, received) }
                                        }
                                    } else {
                                        transfer(target, received)
                                    }
                                } finally {
                                    if (!retained) discard(received.data)
                                }
                                true
                            })
                            wire.setDataSize(0)
                            wire.setDataPosition(0)
                            wire.writeInterfaceToken("android.os.IMessenger")
                            wire.writeInt(1)
                            message.writeToParcel(wire, 0)
                            wire.setDataPosition(0)
                            val sent = service.transact(code, wire, reply, flags)
                            if (action == CALL) callsSent.incrementAndGet()
                            return sent
                        } finally {
                            decoded?.let { discard(it.data) }
                            wire.recycle()
                        }
                    }
                })
            }

            override fun onServiceDisconnected(name: ComponentName) = lost { connection.onServiceDisconnected(name) }
            override fun onBindingDied(name: ComponentName) = lost { connection.onBindingDied(name) }
            override fun onNullBinding(name: ComponentName) = lost { connection.onNullBinding(name) }
        }
        connections[connection] = observed
        return super.bindService(intent, observed, flags).also { if (!it) connections.remove(connection) }
    }

    override fun unbindService(connection: ServiceConnection) = super.unbindService(
        checkNotNull(connections.remove(connection))
    )

    /** Crash only the isolated process identified by this held real reply's OPEN/connection. */
    suspend fun crashWriter(reply: HeldReply) {
        check(reply.identity.pid > 0 && reply.identity.pid != Process.myPid())
        check(reply.identity.uid % 100000 >= 90000) { "Refuse to crash a non-isolated UID" }
        val output = withContext(Dispatchers.IO) {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am crash ${reply.identity.pid}"
            )
            ParcelFileDescriptor.AutoCloseInputStream(fd).use {
                PluginArchive.readBounded(it, 64 * 1024).toString(Charsets.UTF_8)
            }
        }
        check(!output.contains("could not", ignoreCase = true)) { "Observed crash target missing: $output" }
        withTimeout(8_000) { reply.disconnected.await() }
        check(!reply.binder.isBinderAlive) { "Observed OPEN/CALL Binder must be dead" }
    }

    suspend fun releaseDisconnects() = withContext(Dispatchers.Main.immediate) {
        while (true) (delayed.poll() ?: break).invoke()
    }

    suspend fun disposeHeldReply() = withContext(Dispatchers.Main.immediate) {
        if (held.isCompleted && !held.isCancelled) held.await().dispose()
    }

    private fun withFilePayload(message: Message, bytes: ByteArray, send: () -> Unit) {
        val file = File.createTempFile("observed-reply-", ".tmp", cacheDir)
        val original = descriptor(message.data)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { replacement ->
                check(file.delete())
                message.data.putParcelable("payload", replacement)
                try {
                    send()
                } finally {
                    message.data.putParcelable("payload", original)
                }
            }
        } finally {
            if (file.exists()) check(file.delete())
        }
    }

    companion object {
        // Host-internal Binder command numbers, not plugin wire API or manufactured runtime results.
        private const val OPEN = 1
        private const val CALL = 2

        @Suppress("DEPRECATION")
        private fun descriptor(bundle: Bundle): ParcelFileDescriptor = requireNotNull(bundle.getParcelable("payload"))

        private fun discard(bundle: Bundle) {
            runCatching { descriptor(bundle).close() }
        }

        private fun transfer(target: Messenger, message: Message) {
            // Force Parcel transfer; the observer must not hand its own FD ownership to a local Messenger.
            Messenger(object : IBinder by target.binder {
                override fun queryLocalInterface(descriptor: String): IInterface? = null
            }).send(message)
        }
    }
}
