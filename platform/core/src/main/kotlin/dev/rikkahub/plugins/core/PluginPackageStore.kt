package dev.rikkahub.plugins.core

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.WRITE

/** Only verified immutable archives live here. Activation pointers are a separate durable transaction. */
class PluginPackageStore(private val root: Path) {
    init {
        Files.createDirectories(root)
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Package root must be a real directory" }
    }

    @Synchronized
    fun install(bytes: ByteArray, expectedHash: String? = null): VerifiedPluginPackage {
        val snapshot = bytes.copyOf()
        val verified = PluginArchive.decode(snapshot, expectedHash)
        val target = path(verified.hash)
        if (Files.exists(target, NOFOLLOW_LINKS)) return load(verified.hash)
        val temporary = Files.createTempFile(root, ".staging-", ".tmp")
        try {
            Files.write(temporary, snapshot)
            FileChannel.open(temporary, WRITE).use { it.force(true) }
            Files.move(temporary, target, ATOMIC_MOVE)
            return load(verified.hash)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun load(hash: String): VerifiedPluginPackage = PluginArchive.decode(readArchive(hash), hash)

    /** Consumers must verify the archive against the requested hash before executing it. */
    fun readArchive(hash: String): ByteArray {
        val target = path(hash)
        require(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "Package missing or not a regular archive" }
        return Files.newInputStream(target, NOFOLLOW_LINKS).use {
            PluginArchive.readBounded(it, PluginArchive.MAX_ARCHIVE_BYTES)
        }
    }

    private fun path(hash: String): Path {
        PluginArchive.requireHash(hash)
        return root.resolve("$hash.rikkaplug")
    }
}
