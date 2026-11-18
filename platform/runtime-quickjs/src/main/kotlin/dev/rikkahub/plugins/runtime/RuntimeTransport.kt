package dev.rikkahub.plugins.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.rikkahub.plugins.core.ExecutionResultUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Large Binder payloads travel through read-only anonymous files, never as inline Binder buffers. */
internal object RuntimeTransport {
    const val OPEN = 1
    const val CALL = 2
    const val CLOSE = 3

    // A canceled isolated evaluation has no confirmed result; this is never a user Stop signal.
    const val RESULT_UNAVAILABLE = "result-unavailable"
    const val LIMIT = 6 * 1024 * 1024
    const val PAYLOAD_SIZE = "payload-size"

    /** No descriptor crosses a cancellable dispatcher boundary without an owner. */
    suspend fun withOutgoing(directory: File, bytes: ByteArray?, send: suspend (ParcelFileDescriptor?) -> Unit) {
        withContext(Dispatchers.IO) {
            if (bytes == null) {
                send(null)
                return@withContext
            }
            require(bytes.size <= LIMIT)
            val file = File.createTempFile("plugin-rpc-", ".tmp", directory)
            try {
                FileOutputStream(file).use { it.write(bytes) }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    check(file.delete()) { "Could not unlink plugin transport" }
                    currentCoroutineContext().ensureActive()
                    send(descriptor)
                }
            } finally {
                if (file.exists()) check(file.delete()) { "Could not unlink plugin transport" }
            }
        }
    }

    /** The isolated service cannot create files in host storage; use a bounded pipe for replies. */
    fun responsePipe(): Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()

    suspend fun incoming(descriptor: ParcelFileDescriptor): ByteArray = descriptor.use {
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                dev.rikkahub.plugins.core.PluginArchive.readBounded(it, LIMIT)
            }
        }
    }

    /** A reply header/FD is not a result: the complete declared UTF-8 payload must arrive first. */
    suspend fun incomingReply(bundle: Bundle): ByteArray = try {
        descriptor(bundle).use { descriptor ->
            val expected = bundle.getInt(PAYLOAD_SIZE, -1)
            require(expected in 0..LIMIT) { "Missing or invalid runtime reply length" }
            val bytes = incoming(descriptor)
            require(bytes.size == expected) { "Runtime reply ended at ${bytes.size} bytes; expected $expected" }
            bytes
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        throw ExecutionResultUnavailable(
            "Plugin runtime reply was not completely delivered; uncertain work must be reconciled",
            error
        )
    }

    /** A slow/unread pipe must remain cancellable so CLOSE can drain its writer. */
    suspend fun writeResponse(descriptor: ParcelFileDescriptor, bytes: ByteArray) = descriptor.use {
        require(bytes.size <= LIMIT)
        val fd = descriptor.fileDescriptor
        Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            try {
                offset += Os.write(fd, bytes, offset, minOf(16 * 1024, bytes.size - offset))
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                delay(5)
            }
        }
    }

    fun replies(): Channel<Bundle> = Channel(capacity = 1, onUndeliveredElement = ::discard)

    fun discard(bundle: Bundle) {
        runCatching { descriptor(bundle).close() }
    }

    @Suppress("DEPRECATION")
    fun descriptor(bundle: Bundle): ParcelFileDescriptor = requireNotNull(bundle.getParcelable("payload"))
}
