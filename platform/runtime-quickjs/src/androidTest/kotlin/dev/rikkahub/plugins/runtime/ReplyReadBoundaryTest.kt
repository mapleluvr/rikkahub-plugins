package dev.rikkahub.plugins.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.ExecutionResultUnavailable
import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginOperationDeclaration
import dev.rikkahub.plugins.protocol.PluginPanelDeclaration
import dev.rikkahub.plugins.protocol.PluginToolDeclaration
import dev.rikkahub.plugins.testing.IsolatedReplyFaultContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Delivery cancellation preserves strict framing, byte limits, EOF and IO-error contracts. */
class ReplyReadBoundaryTest {
    @Test
    fun invalidTypedLengthsCloseTheReadFdWithoutWaitingForProducer() = runBlocking {
        for (mutate in listOf<(Bundle) -> Unit>(
            { it.remove(RuntimeTransport.PAYLOAD_SIZE) },
            { it.putLong(RuntimeTransport.PAYLOAD_SIZE, 8L) },
            { it.putString(RuntimeTransport.PAYLOAD_SIZE, "8") },
            { it.putBoolean(RuntimeTransport.PAYLOAD_SIZE, true) },
            { it.putDouble(RuntimeTransport.PAYLOAD_SIZE, 8.0) },
            { it.putInt(RuntimeTransport.PAYLOAD_SIZE, -1) },
            { it.putInt(RuntimeTransport.PAYLOAD_SIZE, RuntimeTransport.LIMIT + 1) },
        )) {
            val pipe = RuntimeTransport.responsePipe()
            val raw = pipe[0].fileDescriptor
            try {
                val error = withTimeout(2_000) {
                    runCatching { RuntimeTransport.incomingReply(reply(pipe[0], 8).also(mutate)) }.exceptionOrNull()
                }
                assertTrue(error is ExecutionResultUnavailable)
                assertFalse(error is CancellationException)
                assertFalse(raw.valid())
                assertTrue(currentCoroutineContext().isActive)
            } finally {
                pipe.forEach { it.close() }
            }
        }
    }

    @Test
    fun missingFdAndWriteOnlyFdRemainUnavailableIoNotStopOrBusinessError() = runBlocking {
        val missing = runCatching {
            RuntimeTransport.incomingReply(Bundle().apply { putInt(RuntimeTransport.PAYLOAD_SIZE, 8) })
        }.exceptionOrNull()
        assertTrue(missing is ExecutionResultUnavailable)
        val pipe = RuntimeTransport.responsePipe()
        val raw = pipe[1].fileDescriptor
        try {
            val error = runCatching { RuntimeTransport.incomingReply(reply(pipe[1], 8)) }.exceptionOrNull()
            assertTrue(error is ExecutionResultUnavailable)
            assertTrue(error?.cause is IOException)
            assertFalse(error is CancellationException)
            assertFalse(raw.valid())
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            pipe.forEach { it.close() }
        }
    }

    @Test
    fun emptyAndExactSixMiBAreAcceptedAtEofButAnExtraByteIsRejected() = runBlocking {
        val empty = RuntimeTransport.responsePipe()
        try {
            empty[1].close()
            assertArrayEquals(byteArrayOf(), RuntimeTransport.incomingReply(reply(empty[0], 0)))
        } finally {
            empty.forEach { it.close() }
        }
        val bytes = ByteArray(RuntimeTransport.LIMIT) { (it and 0xff).toByte() }
        val exact = RuntimeTransport.responsePipe()
        val writer = async(Dispatchers.IO) { RuntimeTransport.writeResponse(exact[1], bytes) }
        try {
            assertArrayEquals(bytes, withTimeout(15_000) {
                RuntimeTransport.incomingReply(reply(exact[0], bytes.size))
            })
            writer.await()
        } finally {
            exact.forEach { it.close() }
            writer.cancelAndJoin()
        }
        val extra = RuntimeTransport.responsePipe()
        val overflowWriter = async(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(extra[1]).use {
                    it.write(bytes)
                    it.write(1)
                }
            }
        }
        try {
            val error = withTimeout(15_000) {
                runCatching { RuntimeTransport.incomingReply(reply(extra[0], bytes.size)) }.exceptionOrNull()
            }
            assertTrue(error is ExecutionResultUnavailable)
            assertFalse(error is CancellationException)
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            extra.forEach { it.close() }
            overflowWriter.cancelAndJoin()
        }
    }

    @Test
    fun realToolOperationAndPanelKeepMultibyteUtf8Framing() = runBlocking {
        val lengths = ConcurrentLinkedQueue<Int>()
        val context = IsolatedReplyFaultContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            mutateHeader = { lengths.add(it.getInt(RuntimeTransport.PAYLOAD_SIZE, -1)) },
        )
        val manifest = PluginManifest(
            "dev.fixture.read-unicode",
            "Read Unicode",
            "1.0.0",
            capabilities = setOf("tools.register", "artifacts.write", "ui.actions"),
            tools = listOf(PluginToolDeclaration(
                "big",
                "Big",
                "Unicode",
                JsonObject(mapOf("type" to JsonPrimitive("object"))),
                false
            )),
            operations = listOf(PluginOperationDeclaration("report", "Report")),
            panels = listOf(PluginPanelDeclaration("main", "Main")),
        )
        val script = """
            globalThis.rikkaPlugin = api => {
              const text = '汉🧪'.repeat(40000);
              api.registerTool('big', () => ({content:text}));
              api.registerOperation('report', () => ({artifacts:[{type:'review',content:text}]}));
              api.registerPanel('main', () => ({type:'column',children:Array.from({length:4}, (_,i) =>
                ({type:'text',text:text.slice(i*30000,(i+1)*30000)}))}));
            };
        """.trimIndent()
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, text) in mapOf(
                    "manifest.json" to PluginContract.json.encodeToString(manifest),
                    "main.js" to script
                )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val pkg = PluginArchive.decode(bytes)
        val client = IsolatedPluginClient.prepare(context, bytes, PackageLease(manifest.id, pkg.hash, 1)) { _, _ ->
            true
        }
        val expected = "汉🧪".repeat(40_000)
        try {
            val tool = client.invoke("tool", "big", JsonObject(emptyMap()))
            assertEquals(expected, PluginContract.toolResult(tool).content)
            assertEquals(tool.toByteArray(Charsets.UTF_8).size, lengths.remove())
            val operation = client.invoke("operation", "report", JsonNull)
            assertEquals(
                expected,
                PluginContract.result(manifest, operation).artifacts.single().content.jsonPrimitive.content
            )
            assertEquals(operation.toByteArray(Charsets.UTF_8).size, lengths.remove())
            val panel = client.invoke("panel", "main", JsonNull)
            val ui = PluginContract.ui(manifest, panel)
            assertEquals(4, ui.children.size)
            assertTrue(ui.children.all { it.text.length == 30_000 })
            assertEquals(expected, ui.children.joinToString("") { it.text })
            assertEquals(panel.toByteArray(Charsets.UTF_8).size, lengths.remove())
            assertEquals(3, context.callsSent.get())
            assertTrue(client.close())
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            client.close()
            context.disposeHeldReply()
            context.releaseDisconnects()
        }
        assertTrue(context.connections.isEmpty())
    }

    private fun reply(fd: ParcelFileDescriptor, size: Int) = Bundle().apply {
        putParcelable("payload", fd)
        putInt(RuntimeTransport.PAYLOAD_SIZE, size)
    }
}
