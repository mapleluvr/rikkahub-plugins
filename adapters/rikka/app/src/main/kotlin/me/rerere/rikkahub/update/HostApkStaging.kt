package me.rerere.rikkahub.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** A single owner keeps a verified file until the installer has received its read grant. */
internal class HostApkStaging(private val directory: File) {
    suspend fun prepare(
        download: suspend (File) -> Unit,
        verify: suspend (File) -> Unit,
    ): File {
        // The outer owner also covers cancellation when withContext tries to return the file.
        var staged: File? = null
        try {
            return withContext(Dispatchers.IO) {
                check(directory.mkdirs() || directory.isDirectory) { "Host download storage is unavailable" }
                val expireBefore = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                val existing = requireNotNull(directory.listFiles()) { "Host download storage is unavailable" }
                existing.filter { it.lastModified() < expireBefore }.forEach(File::delete)
                check(existing.count(File::exists) < 3) { "Host download storage is busy" }
                val file = File.createTempFile("host-update-", ".apk", directory)
                staged = file
                download(file)
                currentCoroutineContext().ensureActive()
                verify(file)
                currentCoroutineContext().ensureActive()
                file
            }
        } catch (error: Throwable) {
            staged?.delete()
            throw error
        }
    }
}
