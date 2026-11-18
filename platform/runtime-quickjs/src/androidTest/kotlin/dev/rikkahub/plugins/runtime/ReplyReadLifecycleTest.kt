package dev.rikkahub.plugins.runtime

import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.ExecutionAuthorizationRevoked
import dev.rikkahub.plugins.core.ExecutionResultUnavailable
import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginToolDeclaration
import dev.rikkahub.plugins.testing.IsolatedReplyFaultContext
import dev.rikkahub.plugins.testing.ReplyReadStall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Cancellation must own an active read too, not only waiting for a reply header. */
class ReplyReadLifecycleTest {
    @Test
    fun callerStopAfterPrefixReadDrainsWithoutProducerEof() = runBlocking {
        val stall = ReplyReadStall()
        val context = faultContext(stall)
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
            stall.awaitPrefixConsumed()
            assertEquals(1, context.callsSent.get())
            assertFalse(call.isCompleted)
            val drained = withTimeoutOrNull(8_000) {
                call.cancelAndJoin()
                true
            } == true
            println("READ OWNER prefixConsumed=true StopDrained=$drained writerStillOpen=true CALL=1")
            assertTrue("Stop must drain the actual reader while the writer remains open", drained)
            assertTrue(observed.await() is CancellationException)
            assertFalse(observed.await() is ExecutionResultUnavailable)
            stall.requireConsumerReleased()
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            // Even a red assertion must release the deliberate stall before joining the old blocking reader.
            stall.close()
            call.cancelAndJoin()
            client.close()
            context.disposeHeldReply()
            context.releaseDisconnects()
        }
        assertTrue(context.connections.isEmpty())
    }

    @Test
    fun internalDeadlineAfterPrefixReadReturnsUnavailableWithoutProducerEof() = runBlocking {
        val stall = ReplyReadStall()
        val context = faultContext(stall)
        val client = prepare(context)
        val call = async(Dispatchers.IO) {
            runCatching { client.invoke("tool", "big", JsonObject(emptyMap())) }
        }
        try {
            stall.awaitPrefixConsumed()
            assertEquals(1, context.callsSent.get())
            assertFalse(call.isCompleted)
            val result = withTimeoutOrNull(20_000) { call.await() }
            println("READ OWNER prefixConsumed=true internalDeadline=15000 observedAfter20000=${result != null} CALL=1")
            assertNotNull("Internal deadline must release the reader while the writer remains open", result)
            val error = result?.exceptionOrNull()
            assertTrue(error is ExecutionResultUnavailable)
            assertTrue(error?.cause is kotlinx.coroutines.TimeoutCancellationException)
            stall.requireConsumerReleased()
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            stall.close()
            call.cancelAndJoin()
            client.close()
            context.disposeHeldReply()
            context.releaseDisconnects()
        }
        assertTrue(context.connections.isEmpty())
    }

    @Test
    fun hostCloseDrainsActiveReplyReaderBeforeReturning() = runBlocking { hostManagementClosesRead(disable = false) }

    @Test
    fun disableDrainsActiveReplyReaderAndRetiresLeaseBeforeReturning() = runBlocking {
        hostManagementClosesRead(disable = true)
    }

    private suspend fun hostManagementClosesRead(disable: Boolean) = kotlinx.coroutines.coroutineScope {
        val stall = ReplyReadStall()
        val context = faultContext(stall)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "read-owner-").toFile()
        val host = PluginHost.open(context, root)
        val installed = host.activate(host.prepare(archive()), setOf("tools.register"))
        val ready = CompletableDeferred<Unit>()
        val call = async(Dispatchers.IO) {
            runCatching {
                host.withInstance(installed.lease) {
                    ready.complete(Unit)
                    it.invoke("tool", "big", JsonObject(emptyMap()))
                }
            }.onFailure { error ->
                if (!ready.isCompleted) ready.completeExceptionally(error)
            }
        }
        // Management is a sibling, not a child of the invocation it must cancel and drain.
        val manage = async(Dispatchers.IO) {
            ready.await()
            stall.awaitPrefixConsumed()
            if (disable) host.disable(installed.manifest.id) else host.close()
        }
        try {
            // Cold isolated OPEN has its own production deadline; active-read assertions start after preparation.
            kotlinx.coroutines.withTimeout(20_000) { ready.await() }
            stall.awaitPrefixConsumed()
            assertEquals(1, context.callsSent.get())
            val drained = withTimeoutOrNull(8_000) {
                manage.await()
                true
            } == true
            println("READ OWNER prefixConsumed=true disable=$disable managementDrained=$drained CALL=1")
            assertTrue("Host lifecycle must drain an active read without closing the test producer", drained)
            // The host drains its child owner; the caller's outer error classification can finish afterwards.
            val result = kotlinx.coroutines.withTimeout(2_000) { call.await() }
            assertTrue(result.exceptionOrNull() is ExecutionAuthorizationRevoked)
            assertFalse(host.authorized(installed.lease, setOf("tools.register")))
            assertTrue(context.connections.isEmpty())
            stall.requireConsumerReleased()
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            stall.close()
            call.cancelAndJoin()
            manage.await()
            host.close()
            context.disposeHeldReply()
            context.releaseDisconnects()
            root.deleteRecursively()
        }
    }

    private fun faultContext(stall: ReplyReadStall) = IsolatedReplyFaultContext(
        InstrumentationRegistry.getInstrumentation().targetContext,
        mutateHeader = stall::replaceReply,
    )

    private suspend fun prepare(context: IsolatedReplyFaultContext): IsolatedPluginClient {
        val bytes = archive()
        val pkg = PluginArchive.decode(bytes)
        return IsolatedPluginClient.prepare(context, bytes, PackageLease(pkg.manifest.id, pkg.hash, 1)) { _, _ -> true }
    }

    private fun archive(): ByteArray {
        val manifest = PluginManifest(
            "dev.fixture.reply-read-owner",
            "Reply read owner",
            "1.0.0",
            capabilities = setOf("tools.register"),
            tools = listOf(PluginToolDeclaration(
                "big",
                "Big",
                "Large result",
                JsonObject(mapOf("type" to JsonPrimitive("object"))),
                false
            )),
        )
        val source = """
            globalThis.rikkaPlugin = api => api.registerTool('big', () => ({content: 'x'.repeat(200000)}));
        """.trimIndent()
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, text) in mapOf(
                    "manifest.json" to PluginContract.json.encodeToString(manifest),
                    "main.js" to source
                )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }
}
