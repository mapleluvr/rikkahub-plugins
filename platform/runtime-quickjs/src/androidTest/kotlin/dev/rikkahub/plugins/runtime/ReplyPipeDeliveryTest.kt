package dev.rikkahub.plugins.runtime

import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.ExecutionResultUnavailable
import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginToolDeclaration
import dev.rikkahub.plugins.testing.IsolatedReplyFaultContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Complete delivery is a transport prerequisite, before result JSON or a tool receipt is adopted. */
class ReplyPipeDeliveryTest {
    @Test
    fun realWriterDeathAfterSuccessHeaderIsUnavailableWithCallerStillActive() = runBlocking {
        val context = context(holdReply = true, delayDisconnect = true)
        val client = prepare(context)
        val call = async(Dispatchers.IO) { runCatching { client.invoke("tool", "big", JsonObject(emptyMap())) } }
        try {
            val reply = withTimeout(5_000) { context.held.await() }
            assertEquals(1, context.callsSent.get())
            assertEquals(null, reply.message.data.getString("error"))
            assertFalse(reply.message.data.getBoolean(RuntimeTransport.RESULT_UNAVAILABLE))
            assertEquals(client.processId, reply.identity.pid)
            assertEquals(client.processUid, reply.identity.uid)
            val capacity = android.system.Os.fcntlInt(
                RuntimeTransport.descriptor(reply.message.data).fileDescriptor,
                1032,
                0
            )
            assertTrue("Held real pipe cannot contain the complete response", capacity in 1 until 200_000)
            assertFalse(call.isCompleted)
            context.crashWriter(reply)
            withContext(Dispatchers.Main.immediate) { reply.forwardOriginalPipe() }
            val error = withTimeout(8_000) { call.await() }.exceptionOrNull()
            assertTrue(
                "Short real EOF must be result-unavailable, not JSON/tool failure: $error",
                error is ExecutionResultUnavailable
            )
            assertFalse(error is CancellationException)
            assertTrue(currentCoroutineContext().isActive)
            println(
                "PIPE RECOVERY pid=${reply.identity.pid} uid=${reply.identity.uid} capacity=$capacity CALL=1 type=${error!!.javaClass.name} callerActive=true"
            )
        } finally {
            call.cancelAndJoin()
            client.close()
            context.disposeHeldReply()
            context.releaseDisconnects()
        }
        assertTrue(context.connections.isEmpty())
    }

    @Test
    fun completeLargeRealReplyIsConfirmed() = runBlocking {
        val context = context()
        val client = prepare(context)
        try {
            val body = client.invoke("tool", "big", JsonObject(emptyMap()))
            assertEquals(200_000, PluginContract.toolResult(body).content.length)
            assertEquals(1, context.callsSent.get())
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            client.close()
            context.disposeHeldReply()
            context.releaseDisconnects()
        }
        assertTrue(context.connections.isEmpty())
    }

    @Test
    fun actualCallerStopWithHeldRealReplyRemainsCancellation() = runBlocking {
        val context = context(holdReply = true)
        val client = prepare(context)
        val observed = CompletableDeferred<Throwable>()
        val call = async(Dispatchers.IO) {
            try {
                client.invoke("tool", "big", JsonObject(emptyMap()))
            } catch (error: Throwable) {
                observed.complete(error)
                throw error
            }
        }
        try {
            withTimeout(5_000) { context.held.await() }
            withTimeout(8_000) { call.cancelAndJoin() }
            assertTrue(observed.await() is CancellationException)
            assertFalse(observed.await() is ExecutionResultUnavailable)
            assertTrue(context.connections.isEmpty())
        } finally {
            call.cancelAndJoin()
            client.close()
            context.disposeHeldReply()
            context.releaseDisconnects()
        }
    }

    @Test
    fun realJsInterruptedWordsRemainConfirmedBusinessFailure() = runBlocking {
        val context = context()
        val client = prepare(context)
        try {
            val error = runCatching { client.invoke("tool", "boom", JsonObject(emptyMap())) }.exceptionOrNull()
            assertTrue(error != null)
            assertFalse(error is ExecutionResultUnavailable)
            assertFalse(error is CancellationException)
            assertTrue(error!!.message.orEmpty().contains("interrupted"))
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            client.close()
            context.disposeHeldReply()
            context.releaseDisconnects()
        }
        assertTrue(context.connections.isEmpty())
    }

    @Test
    fun syntacticallyValidButShortResultCannotMasqueradeAsConfirmedSuccess() = runBlocking {
        // Deliberate transport corruption, not a fake engine result: the real callback returns 200,000 chars.
        assertUnavailable(context(replacementPayload = """{"content":"short","details":{}}""".toByteArray()))
    }

    @Test
    fun missingReplyLengthCannotBeAdopted() = runBlocking {
        assertUnavailable(context(mutateHeader = { it.remove("payload-size") }))
    }

    @Test
    fun invalidOrMismatchedReplyLengthsCannotBeAdopted() = runBlocking {
        for (mutate in listOf<(android.os.Bundle) -> Unit>(
            { it.putInt("payload-size", -1) },
            { it.putInt("payload-size", RuntimeTransport.LIMIT + 1) },
            { it.putLong("payload-size", 200_027L) },
            { it.putInt("payload-size", 1) },
            { it.putInt("payload-size", 0) },
        )) {
            assertUnavailable(context(mutateHeader = mutate))
        }
    }

    private suspend fun assertUnavailable(context: IsolatedReplyFaultContext) {
        val client = prepare(context)
        try {
            val error = runCatching { client.invoke("tool", "big", JsonObject(emptyMap())) }.exceptionOrNull()
            assertTrue("Unproven reply must be unavailable: $error", error is ExecutionResultUnavailable)
            assertFalse(error is CancellationException)
            assertTrue(currentCoroutineContext().isActive)
            assertEquals(1, context.callsSent.get())
        } finally {
            client.close()
            context.disposeHeldReply()
            context.releaseDisconnects()
        }
        assertTrue(context.connections.isEmpty())
    }

    private fun context(
        holdReply: Boolean = false,
        delayDisconnect: Boolean = false,
        mutateHeader: (android.os.Bundle) -> Unit = {},
        replacementPayload: ByteArray? = null,
    ) = IsolatedReplyFaultContext(
        InstrumentationRegistry.getInstrumentation().targetContext,
        holdReply,
        delayDisconnect,
        mutateHeader,
        replacementPayload,
    )

    private suspend fun prepare(context: IsolatedReplyFaultContext): IsolatedPluginClient {
        val manifest = PluginManifest(
            "dev.fixture.reply-delivery",
            "Reply delivery",
            "1.0.0",
            capabilities = setOf("tools.register"),
            tools = listOf("big", "boom").map { id ->
                PluginToolDeclaration(
                    id,
                    id,
                    "Delivery $id",
                    JsonObject(mapOf("type" to JsonPrimitive("object"))),
                    false
                )
            },
        )
        val source = """
            globalThis.rikkaPlugin = api => {
              api.registerTool('big', () => ({content: 'x'.repeat(200000)}));
              api.registerTool('boom', () => { throw new InternalError('interrupted'); });
            };
        """.trimIndent()
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, text) in mapOf(
                    "manifest.json" to PluginContract.json.encodeToString(manifest),
                    "main.js" to source
                )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val pkg = PluginArchive.decode(bytes)
        return IsolatedPluginClient.prepare(context, bytes, PackageLease(manifest.id, pkg.hash, 1)) { _, _ -> true }
    }
}
