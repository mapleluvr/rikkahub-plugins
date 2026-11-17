package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginOperationDeclaration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginPackageTest {
    private val manifest = PluginManifest(
        "dev.example.reduce",
        "Reduce",
        "1.0.0",
        capabilities = setOf("context.read", "context.write"),
        operations = listOf(PluginOperationDeclaration("reduce", "Reduce context")),
    )
    private val source = "globalThis.rikkaPlugin = rk => rk.registerOperation('reduce', () => ({}));"

    @Test fun validArchiveRoundTripsThroughImmutableStoreAndChecksBytesOnEveryLoad() {
        val bytes = archive(manifest)
        val decoded = PluginArchive.decode(bytes)
        assertEquals(manifest, decoded.manifest)
        assertEquals(source, decoded.source)
        val root = Files.createTempDirectory("plugin-packages")
        try {
            val store = PluginPackageStore(root)
            val installed = store.install(bytes)
            assertEquals(decoded.hash, installed.hash)
            assertEquals(installed.hash, store.install(bytes).hash)
            assertEquals(source, PluginPackageStore(root).load(installed.hash).source)
            Files.write(root.resolve(installed.hash + ".rikkaplug"), byteArrayOf(0))
            rejected { store.load(installed.hash) }
            rejected { store.load("../outside") }
            assertFalse(Files.exists(root.resolve("../outside.rikkaplug")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun archiveRejectsUndeclaredFilesAmbiguousNamesAndCompatibilityMismatches() {
        for (name in listOf("../escape", "/absolute", "assets/../../escape", "assets\\escape", "Main.js")) {
            rejected { PluginArchive.decode(archive(manifest, mapOf(name to byteArrayOf(1)))) }
        }
        for (invalid in listOf(
            manifest.copy(apiVersion = "2"),
            manifest.copy(version = "01.0.0"),
            manifest.copy(version = "1.0"),
            manifest.copy(id = "../plugin"),
            manifest.copy(runtime = "node"),
            manifest.copy(requiredFeatures = setOf("future.feature")),
            manifest.copy(capabilities = setOf("android.raw")),
            manifest.copy(resources = listOf("main.js")),
            manifest.copy(operations = manifest.operations + manifest.operations),
        )) {
            rejected { PluginArchive.decode(archive(invalid)) }
        }
        val mismatched = "0".repeat(64)
        rejected { PluginArchive.decode(archive(manifest), expectedHash = mismatched) }
    }

    @Test fun compressedExpansionAndDuplicateZipEntriesCannotBecomeReadyPackages() {
        val expanded = ByteArray(PluginArchive.MAX_EXPANDED_BYTES + 1)
        rejected {
            PluginArchive.decode(
                archive(manifest.copy(resources = listOf("assets/data")), mapOf("assets/data" to expanded))
            )
        }
        val duplicate = archive(
            manifest.copy(resources = listOf("a.txt", "b.txt")),
            mapOf("a.txt" to byteArrayOf(1), "b.txt" to byteArrayOf(2)),
        )
        // Both local and central headers receive the same name; no filesystem extraction is involved.
        val marker = "b.txt".toByteArray()
        for (index in 0..duplicate.size - marker.size) {
            if (marker.indices.all { duplicate[index + it] == marker[it] }) duplicate[index] = 'a'.code.toByte()
        }
        rejected { PluginArchive.decode(duplicate) }
    }

    @Test fun uiAndOutputMustStayWithinDeclaredOperationAndCapabilityBoundaries() {
        val pkg = PluginArchive.decode(archive(manifest))
        val accepted = """{"type":"column","children":[{"type":"button","text":"Reduce","operation":"reduce"}]}"""
        assertEquals("column", PluginContract.ui(pkg.manifest, accepted).type)
        rejected { PluginContract.ui(pkg.manifest, accepted.replace("reduce", "unregistered")) }
        rejected { PluginContract.ui(pkg.manifest, """{"type":"webview","text":"https://example.invalid"}""") }
        val reportOnly = manifest.copy(capabilities = setOf("artifacts.write"))
        rejected { PluginContract.result(reportOnly, """{"projection":{"omit":["m1"]}}""") }
        assertEquals(
            1,
            PluginContract.result(reportOnly, """{"artifacts":[{"type":"report","content":"ok"}]}""").artifacts.size
        )
        rejected { PluginContract.result(manifest, """{"artifacts":[{"type":"report","content":"not granted"}]}""") }
    }

    @Test fun additionalArgumentsCannotBypassDepthOrNodeBudgets() {
        val schema = Json.parseToJsonElement("""{"type":"object"}""") as kotlinx.serialization.json.JsonObject
        var deep: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonPrimitive(1)
        repeat(200) { deep = kotlinx.serialization.json.JsonObject(mapOf("extra" to deep)) }
        rejected { PluginToolSchema.validate(schema, deep) }
        val wide = kotlinx.serialization.json.JsonObject(mapOf(
            "extra" to
                kotlinx.serialization.json.JsonArray(List(10001) { kotlinx.serialization.json.JsonPrimitive(it) })
        ))
        rejected { PluginToolSchema.validate(schema, wide) }
        PluginToolSchema.validate(schema, Json.parseToJsonElement("""{"extra":{"list":[1,true,null]}}"""))
    }

    @Test fun centralDirectoryMustAgreeWithLocalCrcLengthsAndDescriptor() {
        val original = archive(manifest)
        val data = java.nio.ByteBuffer.wrap(original).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val central = data.getInt(original.size - 6)
        for ((field, value) in listOf(
            16 to 123,
            20 to -1,
            24 to -1,
            20 to (data.getInt(central + 20) + 1),
            24 to (data.getInt(central + 24) + 1)
        )) {
            val changed = original.copyOf()
            java.nio.ByteBuffer.wrap(changed).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(central + field, value)
            rejected { PluginArchive.decode(changed) }
        }
    }

    private fun archive(value: PluginManifest, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in mapOf(
                "manifest.json" to Json.encodeToString(value).toByteArray(),
                "main.js" to source.toByteArray()
            ) +
                extra) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun rejected(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected package/contract rejection, got $error", error is IllegalArgumentException)
    }
}
