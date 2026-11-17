package me.rerere.rikkahub.releasefixture

import me.rerere.rikkahub.plugins.GitHubPluginHttp
import me.rerere.rikkahub.plugins.PluginGitHubCredentials

import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.GitHubPluginResolver
import dev.rikkahub.plugins.core.GitHubPluginSource
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Opt-in live release check. The credential is provisioned to app-private noBackupFilesDir, never a runner argument. */
class GitHubPluginInstallTest {
    @Test fun releaseDownloadsPreflightsActivatesAndReopensWithPublisherIdentity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = InstrumentationRegistry.getArguments().getString("pluginReleaseSource")
            ?: error("Explicit pluginReleaseSource is required for the live GitHub test")
        val tokenFile = File(context.noBackupFilesDir, "plugin-release-fixture-token")
        val credential = tokenFile.readText().trim()
        val credentials = PluginGitHubCredentials(context)
        val previousCredential = credentials.read()
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "release-test-").toFile()
        var host: PluginHost? = null
        try {
            credentials.save(credential)
            assertTrue("Credential round trip failed", credential == credentials.read())
            assertFalse(File(context.noBackupFilesDir, "plugin-github-token").readText().contains(credential))
            val resolver = GitHubPluginResolver(GitHubPluginHttp(credentials::read))
            val found = resolver.discover(GitHubPluginSource.parse(source), includePrerelease = true)
            assertFalse(found.truncated)
            val release = found.candidates.single { it.entry.id == "dev.rikka.releasefixture" }
            assertNotNull(release.origin.publisherKey)
            val bytes = resolver.download(release)
            assertEquals(release.entry.sha256, dev.rikkahub.plugins.core.PluginArchive.hash(bytes))
            val opened = PluginHost.open(context, directory)
            host = opened
            val candidate = opened.prepareRelease(bytes, release)
            assertTrue(opened.state.value.preferred.isEmpty())
            val installed = opened.activate(candidate, candidate.pkg.manifest.capabilities)
            assertEquals(release.origin, installed.origin)
            opened.withInstance(installed.lease) { client ->
                val result = PluginContract.result(
                    installed.manifest,
                    client.invoke("operation", "review", JsonNull) { JsonArray(emptyList()) }
                )
                assertEquals(1, result.artifacts.size)
            }
            opened.close()
            val recovered = PluginHost.open(context, directory)
            host = recovered
            assertEquals(installed, recovered.state.value.packages[installed.lease.packageHash])
            assertEquals(
                release.origin.publisherKey,
                recovered.state.value.updatePolicies.getValue(installed.manifest.id).publisherKey
            )
            assertTrue(recovered.authorized(installed.lease, installed.granted))
        } finally {
            host?.close()
            credentials.save(previousCredential ?: "")
            tokenFile.delete()
            directory.deleteRecursively()
        }
    }
}
