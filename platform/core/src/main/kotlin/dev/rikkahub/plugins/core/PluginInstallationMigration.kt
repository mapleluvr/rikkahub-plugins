package dev.rikkahub.plugins.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

/**
 * Upgrade from a backup-eligible directory without importing execution authority.
 * The caller supplies its install-local (Android noBackup) destination. Package bytes
 * and provenance survive; all grants are retired even when the source is a restored backup.
 */
object PluginInstallationMigration {
    fun prepare(legacy: Path, destination: Path): Path {
        require(legacy.toAbsolutePath().normalize() != destination.toAbsolutePath().normalize())
        if (Files.exists(destination, NOFOLLOW_LINKS)) {
            require(Files.isDirectory(destination, NOFOLLOW_LINKS))
            return destination
        }
        if (!Files.exists(legacy.resolve("catalog.json"), NOFOLLOW_LINKS)) return destination
        require(Files.isDirectory(legacy, NOFOLLOW_LINKS))
        val parent = requireNotNull(destination.parent)
        Files.createDirectories(parent)
        require(Files.isDirectory(parent, NOFOLLOW_LINKS))
        val staging = Files.createTempDirectory(parent, ".plugin-installation-")
        try {
            PluginCatalogFile.open(legacy).use { previousFile ->
                // Another initialization must never overwrite an already published installation.
                if (Files.exists(destination, NOFOLLOW_LINKS)) {
                    require(Files.isDirectory(destination, NOFOLLOW_LINKS))
                    return destination
                }
                val previous = previousFile.read()
                PluginCatalog(previous) { }
                val source = PluginPackageStore(legacy.resolve("packages"))
                val target = PluginPackageStore(staging.resolve("packages"))
                val issuer = UUID.randomUUID().toString()
                val retired = previous.packages.mapValues { (hash, record) ->
                    val verified = target.install(source.readArchive(hash), hash)
                    require(verified.manifest == record.manifest) { "Installed package metadata changed" }
                    record.copy(lease = record.lease.copy(issuerId = issuer), granted = emptySet(), enabled = false)
                }
                val migrated = previous.copy(issuerId = issuer, packages = retired, preferred = emptyMap())
                PluginCatalog(migrated) { }
                PluginCatalogFile.open(staging).use { it.write(migrated) }
            }
            // No activation can observe a partially copied package set or the old issuer.
            Files.move(staging, destination, ATOMIC_MOVE)
            return destination
        } finally {
            // Own staging only; never change the legacy directory or report a post-commit cleanup failure.
            runCatching { staging.toFile().deleteRecursively() }
        }
    }
}
