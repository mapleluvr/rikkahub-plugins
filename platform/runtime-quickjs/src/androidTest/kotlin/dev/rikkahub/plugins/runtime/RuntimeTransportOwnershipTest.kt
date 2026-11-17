package dev.rikkahub.plugins.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

class RuntimeTransportOwnershipTest {
    @Test fun cancelledIncomingOwnsAndClosesDescriptorBeforeIoDispatch() = runBlocking {
        val pipe = RuntimeTransport.responsePipe()
        pipe[1].close()
        val descriptor = pipe[0]
        val raw = descriptor.fileDescriptor
        launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            runCatching { RuntimeTransport.incoming(descriptor) }
        }.join()
        try {
            assertFalse("Cancellation before IO dispatch must close the accepted descriptor", raw.valid())
        } finally {
            descriptor.close()
        }
    }

    @Test fun cancelledReplyAfterDeliveryBeforeResumeClosesUnreadDescriptor() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val responses = RuntimeTransport.replies()
        val pipe = RuntimeTransport.responsePipe()
        val raw = pipe[0].fileDescriptor
        var consumed = false
        val receiver = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            val reply = responses.receive()
            consumed = true
            RuntimeTransport.discard(reply)
        }
        try {
            assertTrue(responses.trySend(Bundle().apply { putParcelable("payload", pipe[0]) }).isSuccess)
            assertTrue(dispatcher.hasWork())
            receiver.cancel()
            dispatcher.drain()
            receiver.join()
            assertFalse(consumed)
            assertFalse("Prompt cancellation must release the already delivered reply", raw.valid())
        } finally {
            responses.cancel()
            pipe.forEach { it.close() }
        }
    }

    @Test fun closingReplySlotClosesBufferedAndRejectsLateReplies() {
        val responses = RuntimeTransport.replies()
        val pipe = RuntimeTransport.responsePipe()
        val raw = pipe[0].fileDescriptor
        try {
            assertTrue(responses.trySend(Bundle().apply { putParcelable("payload", pipe[0]) }).isSuccess)
            responses.cancel()
            assertFalse(raw.valid())
            assertTrue(responses.trySend(Bundle()).isFailure)
        } finally {
            responses.cancel()
            pipe.forEach { it.close() }
        }
    }

    @Test fun outgoingCancellationAtReturnClosesCreatedDescriptorAndUnlinksFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var raw: FileDescriptor? = null
        var returned = false
        val owner = Job()
        launch(owner) {
            RuntimeTransport.withOutgoing(context.cacheDir, byteArrayOf(1)) { descriptor ->
                raw = descriptor!!.fileDescriptor
                assertTrue(raw!!.valid())
                owner.cancel()
            }
            returned = true
        }.join()
        assertFalse(returned)
        assertFalse(requireNotNull(raw).valid())
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("plugin-rpc-") })
    }

    @Test fun cancellingWriterWithUnreadLargePipeDrainsAndClosesWriteEnd() = runBlocking {
        val pipe = RuntimeTransport.responsePipe()
        val raw = pipe[1].fileDescriptor
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            RuntimeTransport.writeResponse(pipe[1], ByteArray(240_000))
        }
        try {
            assertTrue("Unread pipe must still have an active writer", writer.isActive)
            withTimeout(2000) { writer.cancelAndJoin() }
            assertFalse(raw.valid())
            assertTrue(RuntimeTransport.incoming(pipe[0]).size < 240_000)
        } finally {
            writer.cancelAndJoin()
            pipe.forEach { it.close() }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
        }
        fun hasWork(): Boolean = tasks.isNotEmpty()
        fun drain() {
            while (true) (tasks.poll() ?: return).run()
        }
    }
}
