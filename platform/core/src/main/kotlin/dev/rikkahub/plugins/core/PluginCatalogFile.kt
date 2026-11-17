package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.PluginCatalogState
import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** One host owns this catalog. Malformed confirmed data fails closed instead of resetting permissions. */
class PluginCatalogFile private constructor(
    private val root: Path,
    private val channel: FileChannel,
    private val ownership: FileLock,
) : Closeable {
    private val target = root.resolve("catalog.json")

    fun read(): PluginCatalogState {
        check(ownership.isValid)
        if (!Files.exists(target, NOFOLLOW_LINKS)) {
            return PluginCatalogState(issuerId = java.util.UUID.randomUUID().toString())
        }
        require(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "Catalog must be a regular file" }
        val bytes = Files.newInputStream(target, NOFOLLOW_LINKS).use { PluginArchive.readBounded(it, MAX_BYTES) }
        return PluginContract.json.decodeFromString(bytes.toString(Charsets.UTF_8))
    }

    /** The caller serializes writes and publishes only after this returns. No suspension after rename. */
    fun write(state: PluginCatalogState) {
        check(ownership.isValid)
        val bytes = PluginContract.json.encodeToString(state).toByteArray()
        require(bytes.size <= MAX_BYTES) { "Plugin catalog exceeds size limit" }
        val temporary = Files.createTempFile(root, ".catalog-", ".tmp")
        try {
            Files.write(temporary, bytes)
            FileChannel.open(temporary, WRITE).use { it.force(true) }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            // Cleanup cannot turn an already confirmed rename into a reported transaction failure.
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    override fun close() {
        try {
            ownership.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024

        fun open(root: Path): PluginCatalogFile {
            Files.createDirectories(root)
            require(Files.isDirectory(root, NOFOLLOW_LINKS))
            val channel = FileChannel.open(root.resolve("catalog.lock"), CREATE, WRITE, NOFOLLOW_LINKS)
            try {
                val lock = checkNotNull(channel.tryLock()) { "Plugin catalog already has an owner" }
                return PluginCatalogFile(root, channel, lock)
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }
    }
}
