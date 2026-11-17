package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.PluginCatalogState
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginOperationDeclaration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginInstallationMigrationTest {
    @Test fun emptySourceDoesNotCreateOrAuthorizeAnInstallation() = directories { source, target ->
        assertEquals(target, PluginInstallationMigration.prepare(source, target))
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(source))
    }

    @Test fun bothLegacyAndNamespacedCatalogsRequireFreshConsent() = runBlocking {
        for (issuer in listOf("", "00000000-0000-4000-8000-000000000001")) {
            directories { source, target ->
                val previous = install(source, issuer)
                PluginInstallationMigration.prepare(source, target)
                val migrated = PluginCatalogFile.open(target).use { it.read() }
                val old = previous.packages.values.single()
                assertNotEquals(previous.issuerId, migrated.issuerId)
                assertEquals(previous.updatePolicies, migrated.updatePolicies)
                assertEquals(previous.nextEpoch, migrated.nextEpoch)
                assertEquals(previous.packages.keys, migrated.packages.keys)
                assertTrue(migrated.preferred.isEmpty())
                assertTrue(migrated.packages.values.all { !it.enabled && it.granted.isEmpty() })
                assertFalse(PluginCatalog(migrated) {}.authorized(old.lease, old.granted))
                assertArrayEquals(
                    PluginPackageStore(source.resolve("packages")).readArchive(old.lease.packageHash),
                    PluginPackageStore(target.resolve("packages")).readArchive(old.lease.packageHash)
                )
                assertEquals(previous, PluginCatalogFile.open(source).use { it.read() })
            }
        }
    }

    @Test fun existingDestinationIsNeverOverwrittenByARestoredCatalog() = runBlocking {
        directories { source, target ->
            val current = install(target, "00000000-0000-4000-8000-000000000002")
            Files.createDirectories(source)
            Files.writeString(source.resolve("catalog.json"), "not a catalog")
            PluginInstallationMigration.prepare(source, target)
            assertEquals(current, PluginCatalogFile.open(target).use { it.read() })
            assertEquals("not a catalog", Files.readString(source.resolve("catalog.json")))
        }
    }

    @Test fun corruptedPackageCannotPublishAPartialInstallation() = runBlocking {
        directories { source, target ->
            val previous = install(source, "00000000-0000-4000-8000-000000000001")
            val before = Files.readAllBytes(source.resolve("catalog.json"))
            val hash = previous.packages.keys.single()
            Files.write(source.resolve("packages/$hash.rikkaplug"), byteArrayOf(1, 2, 3))
            assertTrue(runCatching { PluginInstallationMigration.prepare(source, target) }.isFailure)
            assertFalse(Files.exists(target))
            assertArrayEquals(before, Files.readAllBytes(source.resolve("catalog.json")))
            assertTrue(target.parent.toFile().listFiles()!!.none { it.name.startsWith(".plugin-installation-") })
        }
    }

    @Test fun malformedCatalogFailsClosedAndLeavesSourceUntouched() = directories { source, target ->
        Files.createDirectories(source)
        Files.writeString(source.resolve("catalog.json"), "malformed")
        assertTrue(runCatching { PluginInstallationMigration.prepare(source, target) }.isFailure)
        assertFalse(Files.exists(target))
        assertEquals("malformed", Files.readString(source.resolve("catalog.json")))
    }

    @Test fun destinationMustBeARealDirectory() = directories { source, target ->
        Files.writeString(target, "occupied")
        assertTrue(runCatching { PluginInstallationMigration.prepare(source, target) }.isFailure)
        assertEquals("occupied", Files.readString(target))
    }

    private suspend fun install(root: Path, issuer: String): PluginCatalogState {
        val manifest = PluginManifest(
            "dev.example.migration",
            "Migration",
            "1.0.0",
            capabilities = setOf("artifacts.write"),
            operations = listOf(PluginOperationDeclaration("report", "Report"))
        )
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, text) in mapOf(
                    "manifest.json" to PluginContract.json.encodeToString(manifest),
                    "main.js" to "source"
                )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        PluginCatalogFile.open(root).use { file ->
            val catalog = PluginCatalog(PluginCatalogState(issuerId = issuer)) { file.write(it) }
            val pkg = PluginPackageStore(root.resolve("packages")).install(bytes)
            catalog.activate(pkg, pkg.manifest.capabilities, 0)
            return catalog.state.value
        }
    }

    private inline fun directories(block: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("plugin-installation-test")
        try {
            block(root.resolve("source"), root.resolve("target"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
