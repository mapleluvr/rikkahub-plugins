package dev.rikkahub.plugins.runtime

import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginOperationDeclaration
import dev.rikkahub.plugins.protocol.PluginPanelDeclaration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginInstanceTest {
    private val manifest = PluginManifest(
        "dev.example.reduce",
        "Reduce",
        "1.0.0",
        capabilities = setOf("context.read", "context.write", "ui.actions"),
        operations = listOf(PluginOperationDeclaration("reduce", "Reduce")),
        panels = listOf(PluginPanelDeclaration("main", "Reduce context")),
    )
    private val source = """
        globalThis.rikkaPlugin = rk => {
          let count = 0;
          rk.registerOperation('reduce', async (_, ctx) => {
            const messages = await ctx.readMessages();
            count++;
            return {projection: {omit: messages.slice(0, -2).map(m => m.id)}};
          });
          rk.registerPanel('main', () => ({type:'column', children:[
            {type:'text', text:'Calls: ' + count}, {type:'button', text:'Reduce', operation:'reduce'}
          ]}));
        };
    """.trimIndent()

    @Test fun packagedOperationUsesRealRpcWhilePanelRenderingNeverReadsHistory() = runBlocking {
        val pkg = archive(source)
        val lease = PackageLease(manifest.id, pkg.hash, 1)
        var reads = 0
        val instance = PluginInstance.prepare(pkg, lease, { current, _ -> current == lease }) {
            reads++
            Json.parseToJsonElement("""[{"id":"old"},{"id":"latest-user"},{"id":"latest-reply"}]""")
        }
        try {
            assertEquals("Calls: 0", instance.panel("main").children.first().text)
            assertEquals(0, reads)
            assertEquals(setOf("old"), instance.operation("reduce", JsonNull).projection!!.omit)
            assertEquals(1, reads)
            assertEquals("Calls: 1", instance.panel("main").children.first().text)
            assertEquals(1, reads)
        } finally {
            instance.close()
        }
    }

    @Test fun registrationCannotCallHostAndMustMatchTheArchiveManifest() = runBlocking {
        for (code in listOf(
            "await hostRequest(JSON.stringify({method:'context.read'}));\n$source",
            source.replace("'reduce', async", "'other', async"),
            source.replace("let count = 0;", "let count = 0; rk.registerOperation('reduce', () => ({}));"),
        )) {
            val pkg = archive(code)
            var hostCalls = 0
            val result = runCatching {
                PluginInstance.prepare(pkg, PackageLease(manifest.id, pkg.hash, 1), { _, _ -> true }) {
                    hostCalls++
                    JsonNull
                }
            }
            result.getOrNull()?.close()
            assertTrue("Invalid candidate must not activate", result.isFailure)
            assertEquals(0, hostCalls)
        }
    }

    @Test fun revokedPermissionWhileHostReadWaitsRejectsLateResultAndInvalidatesInstance() = runBlocking {
        val pkg = archive(source)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var granted = true
        val instance = PluginInstance.prepare(pkg, PackageLease(manifest.id, pkg.hash, 1), { _, _ -> granted }) {
            entered.complete(Unit)
            release.await()
            Json.parseToJsonElement("[]")
        }
        try {
            val call = async { runCatching { instance.operation("reduce", JsonNull) } }
            entered.await()
            granted = false
            release.complete(Unit)
            assertTrue(call.await().isFailure)
            granted = true
            assertTrue(runCatching { instance.operation("reduce", JsonNull) }.isFailure)
        } finally {
            instance.close()
        }
    }

    @Test fun readOnlyPanelCannotInvokeHostAndReportOnlyPackageCannotAlterProjection() = runBlocking {
        val badPanel = archive(source.replace(
            "rk.registerPanel('main', () => ({type:'column', children:[",
            "rk.registerPanel('main', async (_, ctx) => (await ctx.readMessages(), {type:'column', children:[",
        ))
        val panel = PluginInstance.prepare(badPanel, PackageLease(manifest.id, badPanel.hash, 1), { _, _ -> true }) {
            error("Rendering must not reach host")
        }
        try {
            assertTrue(runCatching { panel.panel("main") }.isFailure)
        } finally {
            panel.close()
        }
        val reportManifest = manifest.copy(capabilities = setOf("artifacts.write"), panels = emptyList())
        val badResult =
            archive(
                "globalThis.rikkaPlugin = rk => rk.registerOperation('reduce', () => ({projection:{omit:['x']}}));",
                reportManifest
            )
        val report = PluginInstance.prepare(badResult, PackageLease(manifest.id, badResult.hash, 1), { _, _ -> true })
        try {
            assertTrue(runCatching { report.operation("reduce", JsonNull) }.isFailure)
        } finally {
            report.close()
        }
    }

    @Test fun retainedOldPackageAndNewPackageHaveSeparateHandlersAndState() = runBlocking {
        val reportManifest = manifest.copy(capabilities = setOf("artifacts.write"), panels = emptyList())
        fun report(version: String) = archive("""
            globalThis.rikkaPlugin = rk => {
              let count = 0;
              rk.registerOperation('reduce', () => ({artifacts:[{type:'report', content:'$version:' + ++count}]}));
            };
        """.trimIndent(), reportManifest.copy(version = version))
        val old = report("1.0.0")
        val next = report("1.1.0")
        val first = PluginInstance.prepare(old, PackageLease(manifest.id, old.hash, 1), { _, _ -> true })
        val second = PluginInstance.prepare(next, PackageLease(manifest.id, next.hash, 2), { _, _ -> true })
        try {
            assertFalse(old.hash == next.hash)
            assertEquals(
                "1.0.0:1",
                first.operation("reduce", JsonNull).artifacts.single().content.jsonPrimitive.content
            )
            assertEquals(
                "1.1.0:1",
                second.operation("reduce", JsonNull).artifacts.single().content.jsonPrimitive.content
            )
            assertEquals(
                "1.0.0:2",
                first.operation("reduce", JsonNull).artifacts.single().content.jsonPrimitive.content
            )
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun invalidToolArgumentsCannotReachJavascriptOrHostReads() = runBlocking {
        val schema = Json.parseToJsonElement(
            """{"type":"object","properties":{"count":{"type":"integer"}},"required":["count"],"additionalProperties":false}"""
        ) as kotlinx.serialization.json.JsonObject
        val toolManifest = manifest.copy(
            operations = emptyList(),
            panels = emptyList(),
            capabilities = setOf("context.read", "tools.register"),
            tools = listOf(
                dev.rikkahub.plugins.protocol.PluginToolDeclaration("count", "Count", "Count messages", schema)
            )
        )
        val pkg =
            archive("""
            globalThis.rikkaPlugin = rk => rk.registerTool('count', async (input, ctx) => {
              await ctx.readMessages();
              return {content: String(input.count)};
            });
            """.trimIndent(), toolManifest)
        var reads = 0
        val instance = PluginInstance.prepare(pkg, PackageLease(manifest.id, pkg.hash, 1), { _, _ -> true }) {
            reads++
            Json.parseToJsonElement("[]")
        }
        try {
            for (input in listOf("{}", """{"count":"2"}""", """{"count":2,"extra":true}""")) {
                assertTrue(runCatching { instance.tool("count", Json.parseToJsonElement(input)) }.isFailure)
            }
            assertEquals(0, reads)
            assertEquals("2", instance.tool("count", Json.parseToJsonElement("""{"count":2}""")).content)
            assertEquals(1, reads)
        } finally {
            instance.close()
        }
    }

    private fun archive(code: String, value: PluginManifest = manifest) = ByteArrayOutputStream().let { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, text) in mapOf("manifest.json" to Json.encodeToString(value), "main.js" to code)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        PluginArchive.decode(output.toByteArray())
    }
}
