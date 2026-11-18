package dev.rikkahub.plugins.testing

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.Closeable

/** Real pipe fault injection after a real CALL/header; the producer remains open until test cleanup. */
class ReplyReadStall : Closeable {
    private class OwnedPipe(
        val writer: ParcelFileDescriptor,
        val observation: ParcelFileDescriptor.AutoCloseInputStream,
    ) : Closeable {
        override fun close() {
            observation.close()
            writer.close()
        }
    }

    private val accepted = CompletableDeferred<OwnedPipe>()
    private var owned: OwnedPipe? = null
    private var closed = false

    /** Used only by the successful CALL observer; OPEN, JS execution and the declared size are unchanged. */
    @Suppress("DEPRECATION")
    fun replaceReply(header: Bundle) = synchronized(this) {
        if (closed) return@synchronized
        check(owned == null)
        check(header.getInt("payload-size", -1) > 1024)
        val original = requireNotNull(header.getParcelable<ParcelFileDescriptor>("payload"))
        val pipe = ParcelFileDescriptor.createPipe()
        val observation = ParcelFileDescriptor.dup(pipe[0].fileDescriptor)
        try {
            val prefix = ByteArray(1024) { 'x'.code.toByte() }
            check(Os.write(pipe[1].fileDescriptor, prefix, 0, prefix.size) == prefix.size)
            val captured = OwnedPipe(pipe[1], ParcelFileDescriptor.AutoCloseInputStream(observation))
            header.putParcelable("payload", pipe[0])
            original.close()
            owned = captured
            check(accepted.complete(captured))
            // The observer transfers and discards pipe[0]; only writer and observation stay owned here.
        } catch (error: Throwable) {
            pipe.forEach { runCatching { it.close() } }
            observation.close()
            throw error
        }
    }

    /** Model/isolated preparation precedes the active-read window, matching the existing header fixture. */
    suspend fun awaitReplyHeader() = withTimeout(10_000) {
        accepted.await()
        Unit
    }

    suspend fun awaitPrefixConsumed() = withTimeout(5_000) {
        val captured = accepted.await()
        while (captured.observation.available() != 0) delay(5)
    }

    /** EPIPE with the producer still open proves that no detached consumer is retaining the read end. */
    suspend fun requireConsumerReleased() {
        val captured = accepted.await()
        check(captured.writer.fileDescriptor.valid()) { "The test producer must remain open" }
        captured.observation.close()
        val error = runCatching { Os.write(captured.writer.fileDescriptor, byteArrayOf(1), 0, 1) }.exceptionOrNull()
        check(error is ErrnoException && error.errno == OsConstants.EPIPE) {
            "Owned consumer did not close its read FD: $error"
        }
        check(captured.writer.fileDescriptor.valid())
    }

    override fun close() = synchronized(this) {
        closed = true
        owned?.close()
        owned = null
        accepted.cancel()
    }
}
