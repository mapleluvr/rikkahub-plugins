package me.rerere.rikkahub.update

import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

internal class HostApkVerifier(
    private val packages: PackageManager,
    private val applicationId: String,
    private val certificateSha256: String,
) {
    suspend fun verify(apk: File, candidate: HostUpdateCandidate) {
        require(apk.length() == candidate.artifact.sizeBytes) { "Host APK size mismatch" }
        val caller = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                caller.ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().toHexString() == candidate.artifact.sha256) { "Host APK digest mismatch" }
        val signature = ApkVerifier.Builder(apk)
            .setMinCheckedPlatformVersion(26)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT.coerceAtLeast(26))
            .build()
            .verify()
        require(signature.isVerified && signature.isVerifiedUsingV2Scheme) { "Host APK signature verification failed" }
        val signer = signature.signerCertificates.singleOrNull()
        val android8Signer = signature.v2SchemeSigners.singleOrNull()?.certificate
        require(signer != null && android8Signer != null &&
            sha256(signer.encoded) == certificateSha256 && sha256(android8Signer.encoded) == certificateSha256
        ) { "Host APK certificate mismatch" }
        requireInstalledSigner()
        val archive = requireNotNull(packages.getPackageArchiveInfo(apk.path, 0)) { "Invalid host APK manifest" }
        require(archive.packageName == applicationId) { "Host APK application ID mismatch" }
        require(
            PackageInfoCompat.getLongVersionCode(archive) == candidate.versionCode.toLong() &&
                archive.versionName == candidate.versionName && candidate.versionCode.toLong() > installedCode()
        ) { "Host APK version mismatch" }
    }

    @Suppress("DEPRECATION") // Android 8 uses GET_SIGNATURES.
    private fun requireInstalledSigner() {
        val flag = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val installed = packages.getPackageInfo(applicationId, flag)
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            installed.signingInfo?.apkContentsSigners
        } else {
            installed.signatures
        }
        val signer = signers?.singleOrNull()
        require(signer != null && sha256(signer.toByteArray()) == certificateSha256) {
            "Installed host does not belong to the update signing chain"
        }
    }

    fun installedCode(): Long = PackageInfoCompat.getLongVersionCode(packages.getPackageInfo(applicationId, 0))

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
}
