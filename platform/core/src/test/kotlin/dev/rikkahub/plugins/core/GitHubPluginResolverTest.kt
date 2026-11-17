package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginOperationDeclaration
import dev.rikkahub.plugins.protocol.PluginReleaseIndex
import dev.rikkahub.plugins.protocol.PluginReleasePackage
import dev.rikkahub.plugins.protocol.PluginReleaseSignature
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GitHubPluginResolverTest {
    @Test fun findsOlderCompatiblePackageAcrossPagesAndRevalidatesBytesOnDownload() = runBlocking {
        val fixture = Fixture()
        val incompatible = fixture.release(1, "2.0.0", api = "2")
        val compatible = fixture.release(2, "1.10.0")
        fixture.page(1, listOf(incompatible) + (1..29).map { fixture.emptyRelease(100 + it) })
        fixture.page(2, listOf(compatible))
        val resolver = GitHubPluginResolver(fixture)
        val found = resolver.discover(GitHubPluginSource.parse("owner/repo"))
        assertEquals(31, found.releasesChecked)
        assertFalse(found.truncated)
        val selected = found.candidates.single()
        assertEquals("1.10.0", selected.entry.version)
        assertArrayEquals(fixture.packageBytes.getValue(2), resolver.download(selected))
        assertEquals(0, fixture.calls.count { "latest" in it })
        fixture.repositoryId = 99
        assertTrue(runCatching { resolver.download(selected) }.isFailure)
    }

    @Test fun malformedNewestReleaseDoesNotHideAnOlderCompatiblePackage() = runBlocking {
        val fixture = Fixture()
        val newest = fixture.release(1, "2.0.0")
        val assets = (newest.getValue("assets") as JsonArray).toMutableList()
        assets[0] = JsonObject(assets[0].jsonObject + ("size" to JsonPrimitive(65537)))
        fixture.page(1, listOf(JsonObject(newest + ("assets" to JsonArray(assets))), fixture.release(2, "1.0.0")))
        val found = GitHubPluginResolver(fixture).discover(GitHubPluginSource.parse("owner/repo"))
        assertEquals("1.0.0", found.candidates.single().entry.version)
        assertEquals(1, found.rejected.size)
    }

    @Test fun invalidPublisherKeyIsRejectedPerReleaseWithoutAbortingDiscovery() = runBlocking {
        val fixture = Fixture()
        val newest = fixture.release(1, "2.0.0")
        val signature = """{"algorithm":"ES256","publicKey":"AQ==","signature":"AQ=="}""".toByteArray()
        fixture.responses["repos/owner/repo/releases/assets/13"] = signature
        val assets = (newest.getValue("assets") as JsonArray) + JsonObject(mapOf(
            "id" to JsonPrimitive(13),
            "name" to JsonPrimitive("release.json.sig"),
            "size" to JsonPrimitive(signature.size)
        ))
        fixture.page(1, listOf(JsonObject(newest + ("assets" to JsonArray(assets))), fixture.release(2, "1.0.0")))
        val found = GitHubPluginResolver(fixture).discover(GitHubPluginSource.parse("owner/repo"))
        assertEquals("1.0.0", found.candidates.single().entry.version)
        assertEquals(1, found.rejected.size)
    }

    @Test fun truncationPrereleasesAndEtagResponsesRemainExplicit() = runBlocking {
        val fixture = Fixture()
        fixture.page(
            1,
            listOf(fixture.release(1, "2.0.0-rc.1", prerelease = true)) +
                (1..29).map { fixture.emptyRelease(100 + it) }
        )
        val resolver = GitHubPluginResolver(fixture, maxPages = 1)
        val source = GitHubPluginSource.parse("https://github.com/owner/repo")
        val stable = resolver.discover(source)
        assertTrue(stable.truncated)
        assertTrue(stable.candidates.isEmpty())
        assertEquals("2.0.0-rc.1", resolver.discover(source, true).candidates.single().entry.version)
        assertTrue(fixture.notModified > 0)
        fixture.failure = 403
        assertTrue(runCatching { resolver.discover(source) }.isFailure)
    }

    @Test fun replacedAssetsAndIncompleteDownloadsCannotProduceAnInstallablePackage() = runBlocking {
        val fixture = Fixture()
        fixture.page(1, listOf(fixture.release(1, "1.0.0")))
        val resolver = GitHubPluginResolver(fixture)
        val selected = resolver.discover(GitHubPluginSource.parse("owner/repo")).candidates.single()
        val path = "repos/owner/repo/releases/assets/12"
        val good = fixture.responses.getValue(path)
        fixture.responses[path] = good.dropLast(1).toByteArray()
        assertTrue(runCatching { resolver.download(selected) }.isFailure)
        fixture.responses[path] = good.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertTrue(runCatching { resolver.download(selected) }.isFailure)
        fixture.responses[path] = good
        fixture.release(1, "1.0.1")
        assertTrue(runCatching { resolver.download(selected) }.isFailure)
    }

    @Test fun signaturesBindExactIndexBytesAndDoNotAssertPublisherTrust() {
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val bytes = "{\"schemaVersion\":1}".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(bytes)
            sign()
        }
        val envelope = Json.encodeToString(PluginReleaseSignature(
            publicKey = Base64.getEncoder().encodeToString(keys.public.encoded),
            signature = Base64.getEncoder().encodeToString(signature)
        )).toByteArray()
        assertEquals(PluginArchive.hash(keys.public.encoded), PluginReleaseContract.verifySignature(bytes, envelope))
        assertTrue(runCatching { PluginReleaseContract.verifySignature(bytes + ' '.code.toByte(), envelope) }.isFailure)
    }

    @Test fun sourceParserRejectsCredentialUrlsAndKeepsReleaseTagsAsOneApiSegment() {
        assertEquals(
            "v1+build/test",
            GitHubPluginSource.parse("https://github.com/owner/repo/releases/tag/v1%2Bbuild%2Ftest").tag
        )
        for (input in listOf(
            "https://token@github.com/a/b",
            "https://evil.test/a/b",
            "a/../b",
            "a/b?token=x",
            "https://github.com/a/b/issues/1"
        )) {
            assertTrue(input, runCatching { GitHubPluginSource.parse(input) }.isFailure)
        }
    }

    private class Fixture : GitHubPluginTransport {
        val responses = mutableMapOf<String, ByteArray>()
        val packageBytes = mutableMapOf<Int, ByteArray>()
        val calls = mutableListOf<String>()
        var repositoryId = 7
        var failure: Int? = null
        var notModified = 0

        override suspend fun get(path: String, binary: Boolean, limit: Int, etag: String?): GitHubPluginResponse {
            calls += path
            failure?.let { return GitHubPluginResponse(it, byteArrayOf()) }
            val bytes = if (path == "repos/owner/repo") {
                """{"id":$repositoryId,"full_name":"owner/repo"}""".toByteArray()
            } else {
                requireNotNull(responses[path]) { "Unexpected request $path" }
            }
            val current = PluginArchive.hash(bytes)
            if (etag == current) {
                notModified++
                return GitHubPluginResponse(304, byteArrayOf(), current)
            }
            return GitHubPluginResponse(200, bytes, current)
        }

        fun page(page: Int, entries: List<JsonObject>) {
            responses["repos/owner/repo/releases?per_page=30&page=$page"] = JsonArray(entries).toString().toByteArray()
        }

        fun emptyRelease(id: Int): JsonObject = JsonObject(mapOf(
            "id" to JsonPrimitive(id),
            "tag_name" to JsonPrimitive("unused-$id"),
            "draft" to JsonPrimitive(false),
            "prerelease" to JsonPrimitive(false),
            "assets" to JsonArray(emptyList())
        ))

        fun release(id: Int, version: String, api: String = "1", prerelease: Boolean = false): JsonObject {
            val manifest = PluginManifest(
                "dev.example.report",
                "Report",
                version,
                apiVersion = api,
                operations = listOf(PluginOperationDeclaration("report", "Report"))
            )
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                for ((name, text) in mapOf(
                    "manifest.json" to Json.encodeToString(manifest),
                    "main.js" to "globalThis.rikkaPlugin = rk => rk.registerOperation('report', () => ({}));"
                )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            val bytes = output.toByteArray()
            packageBytes[id] = bytes
            val entry =
                PluginReleasePackage(
                    manifest.id,
                    version,
                    "report.rikkaplug",
                    PluginArchive.hash(bytes),
                    bytes.size,
                    api,
                    "quickjs-es2020"
                )
            val index = Json.encodeToString(PluginReleaseIndex(packages = listOf(entry))).toByteArray()
            responses["repos/owner/repo/releases/assets/${id * 10 + 1}"] = index
            responses["repos/owner/repo/releases/assets/${id * 10 + 2}"] = bytes
            fun asset(number: Int, name: String, size: Int) = JsonObject(mapOf(
                "id" to JsonPrimitive(number),
                "name" to JsonPrimitive(name),
                "size" to JsonPrimitive(size)
            ))
            return JsonObject(emptyRelease(id) + mapOf(
                "tag_name" to JsonPrimitive("v$version"),
                "prerelease" to JsonPrimitive(prerelease),
                "assets" to JsonArray(
                    listOf(asset(id * 10 + 1, "release.json", index.size), asset(id * 10 + 2, entry.asset, bytes.size))
                )
            )).also {
                responses["repos/owner/repo/releases/$id"] = it.toString().toByteArray()
                responses["repos/owner/repo/releases/tags/v$version"] = it.toString().toByteArray()
            }
        }
    }
}
