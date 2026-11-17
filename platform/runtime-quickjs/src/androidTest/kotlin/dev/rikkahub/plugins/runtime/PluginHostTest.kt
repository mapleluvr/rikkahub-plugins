package dev.rikkahub.plugins.runtime

import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginOperationDeclaration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginHostTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pluginId = "dev.example.installed"
    private val grants = setOf("artifacts.write")

    @Test fun persistedVersionsKeepHandlersWhileDisableClosesLiveScopesAndReenableCannotReviveLeases() = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "host-").toFile()
        var host = PluginHost.open(context, root)
        try {
            val first = host.activate(host.prepare(archive("1.0.0")), grants)
            val second = host.activate(host.prepare(archive("1.1.0")), grants)
            host.close()
            host = PluginHost.open(context, root)
            assertEquals(second.lease.packageHash, host.state.value.preferred[pluginId])
            val ready = CompletableDeferred<Pair<IsolatedPluginClient, IsolatedPluginClient>>()
            val scopes = async {
                runCatching {
                    host.withInstance(first.lease) { old ->
                        host.withInstance(second.lease) { latest ->
                            fun report(result: String) = PluginContract.result(first.manifest, result)
                                .artifacts.single().content.jsonPrimitive.content
                            assertEquals("1.0.0:1", report(old.invoke("operation", "report", JsonNull)))
                            assertEquals("1.1.0:1", report(latest.invoke("operation", "report", JsonNull)))
                            assertEquals("1.0.0:2", report(old.invoke("operation", "report", JsonNull)))
                            ready.complete(old to latest)
                            awaitCancellation()
                        }
                    }
                }
            }
            val (old, latest) = ready.await()
            withTimeout(6000) { host.disable(pluginId) }
            assertTrue(scopes.await().isFailure)
            assertTrue("Revocation must drain the retained old runtime", old.close())
            assertTrue("Revocation must drain the preferred runtime", latest.close())
            assertTrue(runCatching { old.invoke("operation", "report", JsonNull) }.isFailure)
            val enabled = host.activate(host.prepare(archive("1.0.0")), grants, allowRollback = true)
            assertNotEquals(first.lease, enabled.lease)
            assertFalse(host.authorized(first.lease, grants))
            assertFalse(host.authorized(second.lease, grants))
            host.close()
            host = PluginHost.open(context, root)
            assertTrue(host.authorized(enabled.lease, grants))
            assertFalse(host.authorized(first.lease, grants))
        } finally {
            host.close()
            root.deleteRecursively()
        }
    }

    @Test fun freshInstallationCannotAuthorizeRestoredLeaseForIdenticalPackage() = runBlocking {
        val firstRoot = Files.createTempDirectory(context.cacheDir.toPath(), "host-first-").toFile()
        val secondRoot = Files.createTempDirectory(context.cacheDir.toPath(), "host-fresh-").toFile()
        val first = PluginHost.open(context, firstRoot)
        val second = PluginHost.open(context, secondRoot)
        try {
            val bytes = archive("1.0.0")
            val old = first.activate(first.prepare(bytes), grants)
            val restored = second.activate(second.prepare(bytes), grants)
            assertEquals(old.lease.packageHash, restored.lease.packageHash)
            assertEquals(old.lease.activationEpoch, restored.lease.activationEpoch)
            assertFalse(
                "Fresh consent must not revive an imported run/approval lease",
                second.authorized(old.lease, grants)
            )
            second.close()
            val reopened = PluginHost.open(context, secondRoot)
            try {
                assertTrue(reopened.authorized(restored.lease, grants))
                assertFalse(reopened.authorized(old.lease, grants))
            } finally {
                reopened.close()
            }
        } finally {
            first.close()
            second.close()
            firstRoot.deleteRecursively()
            secondRoot.deleteRecursively()
        }
    }

    @Test fun badPreflightAndStaleConsentCannotChangeConfirmedRegistry() = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "host-").toFile()
        val host = PluginHost.open(context, root)
        try {
            val bad = archive("1.0.0", "globalThis.rikkaPlugin = rk => rk.registerOperation('other', () => ({}));")
            assertTrue(runCatching { host.prepare(bad) }.isFailure)
            assertTrue(host.state.value.packages.isEmpty())
            val first = host.prepare(archive("1.0.0"))
            val second = host.prepare(archive("1.1.0"))
            val active = host.activate(first, grants)
            val confirmed = host.state.value
            assertTrue(runCatching { host.activate(second, grants) }.isFailure)
            assertEquals(confirmed, host.state.value)
            assertTrue(host.authorized(active.lease, grants))
        } finally {
            host.close()
            root.deleteRecursively()
        }
    }

    @Test fun confirmedCatalogIsSingleOwnerAndDamagedBytesFailClosed() = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "host-").toFile()
        val host = PluginHost.open(context, root)
        try {
            val installed = host.activate(host.prepare(archive("1.0.0")), grants)
            assertTrue(runCatching { PluginHost.open(context, root) }.isFailure)
            host.close()
            File(root, ".catalog-incomplete.tmp").writeText("partial")
            val recovered = PluginHost.open(context, root)
            assertTrue(recovered.authorized(installed.lease, grants))
            recovered.close()
            val packageFile = File(root, "packages/${installed.lease.packageHash}.rikkaplug")
            packageFile.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(runCatching { PluginHost.open(context, root) }.isFailure)
            // Failed open releases ownership; it must neither reset nor replace the corrupt data.
            assertTrue(runCatching { PluginHost.open(context, root) }.isFailure)
            assertEquals(3, packageFile.length().toInt())
            File(root, "catalog.json").writeText("{")
            assertTrue(runCatching { PluginHost.open(context, root) }.isFailure)
        } finally {
            host.close()
            root.deleteRecursively()
        }
    }

    @Test fun disableDuringContextReadRejectsLateResultWithoutReopeningTheRuntime() = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "host-").toFile()
        val host = PluginHost.open(context, root)
        try {
            val consent = grants + "context.read"
            val record = host.activate(host.prepare(archive("1.0.0", capabilities = consent)), consent)
            val entered = CompletableDeferred<Unit>()
            val drained = CompletableDeferred<Unit>()
            val call = async {
                runCatching {
                    host.withInstance(record.lease) { client ->
                        client.invoke("operation", "report", JsonNull) {
                            entered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                drained.complete(Unit)
                            }
                        }
                    }
                }
            }
            entered.await()
            withTimeout(6000) { host.disable(pluginId) }
            assertTrue("Revocation must drain host reads too", drained.isCompleted)
            assertTrue(call.await().isFailure)
        } finally {
            host.close()
            root.deleteRecursively()
        }
    }

    private fun archive(
        version: String,
        source: String = """
            globalThis.rikkaPlugin = rk => {
              let count = 0;
              rk.registerOperation('report', () => ({artifacts:[{type:'report',content:'$version:' + ++count}]}));
            };
        """.trimIndent(),
        capabilities: Set<String> = grants,
    ): ByteArray = ByteArrayOutputStream().let { output ->
        val manifest = PluginManifest(
            pluginId,
            "Installed plugin",
            version,
            capabilities = capabilities,
            operations = listOf(PluginOperationDeclaration("report", "Report"))
        )
        ZipOutputStream(output).use { zip ->
            for ((name, text) in mapOf(
                "manifest.json" to PluginContract.json.encodeToString(manifest),
                "main.js" to source
            )) {
                val entry = ZipEntry(name).apply { time = 0 }
                zip.putNextEntry(entry)
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }
}
