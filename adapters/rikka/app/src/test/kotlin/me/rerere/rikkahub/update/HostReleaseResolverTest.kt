package me.rerere.rikkahub.update

import dev.rikkahub.plugins.core.GitHubPluginResponse
import dev.rikkahub.plugins.core.GitHubPluginTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class HostReleaseResolverTest {
    private val repository = "mapleluvr/rikkahub-plugin-host"
    private val applicationId = "io.github.mapleluvr.rikkahub"
    private val certificate = "ab".repeat(32)
    private val apkSha256 = "ef".repeat(32)
    private val provenanceSha256 = "bc".repeat(32)
    private val base = "repos/$repository"
    private var index = index()
    private var repositoryId = 10L
    private var indexId = 20L
    private var packageAssetId = 30L
    private var packageAssetDigest = apkSha256
    private var versionCode = 2
    private var draft = false
    private var releasePages: Map<Int, List<String>>? = null
    private val seen = mutableListOf<String>()

    @Test
    fun selectsTheFirstSupportedAbiAndRechecksTheExactAssets() = runTest {
        val resolver = resolver(listOf("x86_64", "arm64-v8a"))
        val selected = requireNotNull(resolver.discover())
        assertEquals(2, selected.versionCode)
        assertEquals("x86_64", selected.artifact.abi)
        assertEquals(apkSha256, selected.artifact.sha256)
        assertEquals("$base/releases/assets/30", resolver.confirmDownload(selected))
        assertTrue("$base/releases/13" in seen)
    }

    @Test
    fun rejectsRepositoryAndAssetReplacementBeforeReturningTheApkEndpoint() = runTest {
        val resolver = resolver()
        val selected = requireNotNull(resolver.discover())
        repositoryId++
        rejected("repository") { resolver.confirmDownload(selected) }
        repositoryId--
        indexId++
        rejected("changed") { resolver.confirmDownload(selected) }
        indexId--
        packageAssetId += 100
        rejected("changed") { resolver.confirmDownload(selected) }
        packageAssetId -= 100
        index = index(notes = "Changed bytes")
        rejected("changed") { resolver.confirmDownload(selected) }
        assertTrue(seen.none { it == "$base/releases/assets/30" })
    }

    @Test
    fun rejectsAnIndexWithAnotherPackageCertificateOrArtifactDigest() = runTest {
        val resolver = resolver()
        index = index(pkg = "me.rerere.rikkahub")
        rejected("application") { resolver.discover() }
        index = index(cert = "cd".repeat(32))
        rejected("certificate") { resolver.discover() }
        index = index()
        packageAssetDigest = "12".repeat(32)
        rejected("digest") { resolver.discover() }
    }

    @Test
    fun usesVersionCodeAndSelectsOnlySupportedApksFromPublishedReleases() = runTest {
        assertNull(resolver(listOf("armeabi-v7a")).discover())
        versionCode = 1
        index = index(code = 1, name = "99.0.0")
        assertNull(resolver().discover())
        versionCode = 2
        index = index(name = "1.0.0")
        assertEquals("1.0.0", resolver().discover()?.versionName)
        draft = true
        assertNull(resolver().discover())
    }

    @Test
    fun scansPastPluginReleasesAndPrefersTheLargestHostVersionCode() = runTest {
        val lower = release()
        versionCode = 3
        index = index(code = 3)
        val newer = release(id = 14)
        releasePages = mapOf(
            1 to List(29) { """{"tag_name":"plugin-v$it","draft":false,"prerelease":false}""" } + lower,
            2 to listOf(newer),
        )
        assertEquals(3, resolver().discover()?.versionCode)
        assertTrue("$base/releases?per_page=30&page=2" in seen)
    }

    @Test
    fun doesNotReportAnUpdateFromATruncatedScan() = runTest {
        val plugins = List(30) { """{"tag_name":"plugin-v$it","draft":false,"prerelease":false}""" }
        releasePages = (1..8).associateWith { plugins }
        rejected("scan limit") { resolver().discover() }
    }

    private fun resolver(abi: List<String> = listOf("x86_64")) = HostReleaseResolver(
        transport = GitHubPluginTransport { path, binary, limit, _ ->
            seen += path
            val raw = when (path) {
                base -> """{"id":$repositoryId,"full_name":"$repository"}"""

                "$base/releases/latest", "$base/releases/13" -> release()

                in (1..8).map { "$base/releases?per_page=30&page=$it" } -> {
                    val page = path.substringAfterLast('=').toInt()
                    val releases = releasePages?.get(page) ?: if (page == 1) listOf(release()) else emptyList()
                    releases.joinToString(",", "[", "]")
                }

                "$base/releases/assets/$indexId" -> {
                    assertTrue(binary)
                    index
                }

                else -> error("Unexpected GitHub path $path")
            }.toByteArray()
            assertTrue(raw.size <= limit)
            GitHubPluginResponse(200, raw)
        },
        repository = repository,
        indexName = "host-release.json",
        applicationId = applicationId,
        certificateSha256 = certificate,
        installedVersionCode = 1,
        supportedAbis = abi,
    )

    private fun release(id: Long = 13): String = """{
        "id":$id,"tag_name":"host-v$versionCode","draft":$draft,"prerelease":false,"published_at":"2026-11-18T14:00:00Z",
        "assets":[
            {"id":$indexId,"name":"host-release.json","size":${index.toByteArray().size},"digest":"sha256:${hash(
        index
    )}"},
            {"id":$packageAssetId,"name":"host-x86_64.apk","size":12,"digest":"sha256:$packageAssetDigest"},
            {"id":31,"name":"host-arm64-v8a.apk","size":14,"digest":"sha256:${"aa".repeat(32)}"}
        ]
    }"""

    private fun index(
        pkg: String = applicationId,
        cert: String = certificate,
        code: Int = 2,
        name: String = "3.3.0-plugins.2",
        notes: String = "New version",
    ): String = """{
        "schemaVersion":1,"applicationId":"$pkg","certificateSha256":"$cert",
        "versionCode":$code,"versionName":"$name","sourceProvenanceSha256":"$provenanceSha256","notes":"$notes",
        "apks":[
            {"name":"host-arm64-v8a.apk","abi":"arm64-v8a","sizeBytes":14,"sha256":"${"aa".repeat(32)}"},
            {"name":"host-x86_64.apk","abi":"x86_64","sizeBytes":12,"sha256":"$apkSha256"}
        ]
    }"""

    private suspend fun rejected(message: String, action: suspend () -> Any?) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue(
            "Expected rejection containing $message, got $error",
            error?.message?.contains(message, ignoreCase = true) == true
        )
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
