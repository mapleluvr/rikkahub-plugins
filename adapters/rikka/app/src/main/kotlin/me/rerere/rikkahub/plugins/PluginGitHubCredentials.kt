package me.rerere.rikkahub.plugins

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.utils.SecretCipher
import java.io.File

/** App-only credential, encrypted with Android Keystore and excluded from backup and plugin metadata. */
class PluginGitHubCredentials(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "plugin-github-token"))
    private val access = Mutex()

    suspend fun read(): String? = access.withLock {
        withContext(Dispatchers.IO) {
            if (!file.baseFile.exists()) null else SecretCipher.decrypt(file.readFully().toString(Charsets.UTF_8))
        }
    }

    suspend fun save(value: String) = access.withLock {
        require(value.length <= 1024 && value.none { it.isISOControl() }) { "Invalid GitHub credential" }
        withContext(Dispatchers.IO) {
            if (value.isBlank()) {
                file.delete()
            } else {
                val encrypted = SecretCipher.encrypt(value.trim()).toByteArray()
                val stream = file.startWrite()
                try {
                    stream.write(encrypted)
                    file.finishWrite(stream)
                } catch (error: Exception) {
                    file.failWrite(stream)
                    throw error
                }
            }
        }
    }
}
