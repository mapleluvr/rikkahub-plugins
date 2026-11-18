package me.rerere.rikkahub.plugins

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.update.HostApkArtifact
import me.rerere.rikkahub.update.HostApkVerifier
import me.rerere.rikkahub.update.HostUpdateCandidate
import me.rerere.rikkahub.utils.installIntent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Tiny APKs come from verification/p6/make-update-fixture.ps1, using the local Debug signing identity. */
@RunWith(AndroidJUnit4::class)
class HostUpdateApkDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    @Test
    fun verifiedUpgradeProducesAReadOnlyGrantToTheSystemInstaller() = runBlocking {
        withFixture("signed") { apk, verifier, candidate ->
            verifier.verify(apk, candidate)
            val intent = installIntent(app, apk)
            assertEquals("android.intent.action.INSTALL_PACKAGE", intent.action)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val component = requireNotNull(intent.component)
            val systemInstaller = app.packageManager.getActivityInfo(component, 0)
            assertTrue(systemInstaller.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
            val uri = requireNotNull(intent.data)
            assertEquals("${app.packageName}.fileprovider", uri.authority)
            assertArrayEquals(apk.readBytes(), app.contentResolver.openInputStream(uri)?.use { it.readBytes() })
        }
    }

    @Test
    fun signatureDigestAndVersionEachFailIndependently() = runBlocking {
        withFixture("unsigned") { apk, verifier, candidate ->
            rejected("signature") { verifier.verify(apk, candidate) }
        }
        withFixture("signed") { apk, verifier, candidate ->
            rejected("digest") {
                verifier.verify(apk, candidate.copy(artifact = candidate.artifact.copy(sha256 = "00".repeat(32))))
            }
            rejected("version") { verifier.verify(apk, candidate.copy(versionCode = candidate.versionCode + 1)) }
            val otherSigner = HostApkVerifier(app.packageManager, app.packageName, "00".repeat(32))
            rejected("certificate") { otherSigner.verify(apk, candidate) }
        }
    }

    private suspend fun withFixture(
        name: String,
        action: suspend (File, HostApkVerifier, HostUpdateCandidate) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val apk = File.createTempFile("apk-verification-", ".apk", app.cacheDir)
        try {
            val args = InstrumentationRegistry.getArguments()
            val versionCode = requireNotNull(args.getString("hostFixtureVersionCode")).toInt()
            instrumentation.context.assets.open("host-update-fixture/$name.apk").use { input ->
                apk.outputStream().use { output -> input.copyTo(output) }
            }
            val candidate = HostUpdateCandidate(
                versionCode, "3.3.0-fixture.$versionCode",
                HostApkArtifact(name, "universal", apk.length(), sha256(apk.readBytes())),
                "Fixture", "2026-11-18T14:00:00Z", 1, 1, 1, "00".repeat(32), 2,
            )
            action(apk, HostApkVerifier(app.packageManager, app.packageName, installedCertificate()), candidate)
        } finally {
            apk.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedCertificate(): String {
        val flags = if (Build.VERSION.SDK_INT >=
            28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = app.packageManager.getPackageInfo(app.packageName, flags)
        val signer = if (Build.VERSION.SDK_INT >= 28) {
            requireNotNull(info.signingInfo).apkContentsSigners.single()
        } else {
            requireNotNull(info.signatures).single()
        }
        return sha256(signer.toByteArray())
    }

    private suspend fun rejected(reason: String, action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertNotNull("Expected rejection for $reason", error)
        assertTrue("Wrong rejection for $reason: $error", error?.message.orEmpty().contains(reason, ignoreCase = true))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
}
