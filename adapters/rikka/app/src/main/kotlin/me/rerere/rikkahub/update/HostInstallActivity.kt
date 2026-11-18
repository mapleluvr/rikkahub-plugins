package me.rerere.rikkahub.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.installIntent
import java.io.File

/** Each instance receives the result for one durable request, including after Activity recreation. */
internal class HostInstallActivity : ComponentActivity() {
    private val staging by lazy { hostApkStaging(this) }
    private var ticket: HostInstallTicket? = null
    private var installerLaunched = false
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        lifecycleScope.launch {
            try {
                withContext(NonCancellable) { staging.finishInstall(requireNotNull(ticket)) }
            } catch (error: Exception) {
                showError(error)
            } finally {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fileId = intent.getStringExtra(EXTRA_FILE_ID)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (fileId == null || requestId == null) {
            finish()
            return
        }
        ticket = HostInstallTicket(fileId, requestId)
        installerLaunched = savedInstanceState?.getBoolean(STATE_LAUNCHED) == true
        if (installerLaunched) return
        lifecycleScope.launch {
            val request = requireNotNull(ticket)
            var enteredSystemLaunch = false
            try {
                check(packageManager.canRequestPackageInstalls()) { getString(R.string.host_update_allow_install) }
                val file = staging.authorizeInstaller(request)
                val intent = installIntent(this@HostInstallActivity, file)
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                currentCoroutineContext().ensureActive()
                enteredSystemLaunch = true
                installer.launch(intent)
                installerLaunched = true
            } catch (error: Throwable) {
                // A lost acknowledgment retains its URI material; definite refusals release this request.
                if (!enteredSystemLaunch || error is ActivityNotFoundException || error is SecurityException) {
                    try {
                        withContext(NonCancellable) { staging.refuseUnsentInstall(request) }
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                }
                if (error !is CancellationException) showError(error)
                finish()
                if (error is CancellationException) throw error
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_LAUNCHED, installerLaunched)
        super.onSaveInstanceState(outState)
    }

    private fun showError(error: Throwable) {
        Toast.makeText(this, error.message ?: getString(R.string.update_card_unknown_error), Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val EXTRA_FILE_ID = "host.install.file"
        private const val EXTRA_REQUEST_ID = "host.install.request"
        private const val STATE_LAUNCHED = "host.install.launched"

        internal fun intent(context: Context, ticket: HostInstallTicket): Intent =
            Intent(context, HostInstallActivity::class.java)
                .putExtra(EXTRA_FILE_ID, ticket.fileId)
                .putExtra(EXTRA_REQUEST_ID, ticket.requestId)
    }
}

@Suppress("DEPRECATION") // APK archive metadata is available from API 26 onward.
internal fun hostApkStaging(context: Context): HostApkStaging {
    val verifier = HostApkVerifier(context.packageManager, context.packageName, BuildConfig.HOST_APK_CERTIFICATE_SHA256)
    return HostApkStaging(
        directory = File(context.noBackupFilesDir, "host-updates"),
        installedVersionCode = verifier::installedCode,
        legacyDirectory = File(context.cacheDir, "host-updates"),
        obsoleteLegacyFile = { file ->
            val archive = context.packageManager.getPackageArchiveInfo(file.path, 0)
            archive != null && archive.packageName == context.packageName &&
                (if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()) <=
                verifier.installedCode()
        },
    )
}
