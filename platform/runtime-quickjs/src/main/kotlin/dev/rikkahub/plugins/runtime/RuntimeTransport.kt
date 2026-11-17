package dev.rikkahub.plugins.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Large Binder payloads travel through read-only anonymous files, never as inline Binder buffers. */
internal object RuntimeTransport {
    const val OPEN = 1
    const val CALL = 2
    const val CLOSE = 3
    const val LIMIT = 6 * 1024 * 1024

    suspend fun outgoing(directory: File, bytes: ByteArray): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        require(bytes.size <= LIMIT)
        val file = File.createTempFile("plugin-rpc-", ".tmp", directory)
        try {
            FileOutputStream(file).use { it.write(bytes) }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            check(file.delete()) { "Could not unlink plugin transport" }
        }
    }

    /** The isolated service cannot create files in host storage; use a bounded pipe for replies. */
    fun responsePipe(): Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()

    suspend fun incoming(descriptor: ParcelFileDescriptor): ByteArray = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            dev.rikkahub.plugins.core.PluginArchive.readBounded(it, LIMIT)
        }
    }

    @Suppress("DEPRECATION")
    fun descriptor(bundle: Bundle): ParcelFileDescriptor = requireNotNull(bundle.getParcelable("payload"))
}
