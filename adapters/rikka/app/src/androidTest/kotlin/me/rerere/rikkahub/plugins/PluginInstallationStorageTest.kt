package me.rerere.rikkahub.plugins

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/** Exercise the actual manager directory policy, not a caller-selected PluginHost test directory. */
class PluginInstallationStorageTest {
    @Test fun freshInstallationStoresAuthorityOnlyUnderNoBackup() = runBlocking {
        val context = testContext()
        val manager = RikkaPluginManager(context, this)
        val host = manager.host()
        try {
            assertTrue(File(context.noBackupFilesDir, "plugin-host/catalog.lock").isFile)
            assertFalse(File(context.filesDir, "plugin-host").exists())
        } finally {
            host.close()
            context.root.deleteRecursively()
        }
    }

    @Test fun legacyDirectoryKeepsVerifiedPackagesButNeverRestoresAuthorization() = runBlocking {
        val context = testContext()
        val legacyRoot = File(context.filesDir, "plugin-host")
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("plugins/review-report.rikkaplug").use { it.readBytes() }
        val legacy = PluginHost.open(context, legacyRoot)
        val candidate = legacy.prepare(bytes)
        val old = legacy.activate(candidate, candidate.pkg.manifest.capabilities)
        val previous = legacy.state.value
        legacy.close()
        val manager = RikkaPluginManager(context, this)
        val host = manager.host()
        try {
            val migrated = host.state.value
            val retained = migrated.packages.getValue(old.lease.packageHash)
            assertNotEquals(previous.issuerId, migrated.issuerId)
            assertEquals(previous.updatePolicies, migrated.updatePolicies)
            assertFalse(retained.enabled)
            assertTrue(retained.granted.isEmpty())
            assertTrue(migrated.preferred.isEmpty())
            assertFalse(host.authorized(old.lease, old.manifest.capabilities))
            assertTrue(File(context.noBackupFilesDir, "plugin-host/catalog.json").isFile)
            val prepared = host.prepareInstalled(old.lease.packageHash)
            val fresh = host.activate(prepared, prepared.pkg.manifest.capabilities)
            assertTrue(host.authorized(fresh.lease, fresh.manifest.capabilities))
            assertFalse(host.authorized(old.lease, old.manifest.capabilities))
            host.close()
            val reopened = RikkaPluginManager(context, this).host()
            try {
                assertEquals(fresh.lease, reopened.state.value.packages.getValue(fresh.lease.packageHash).lease)
                assertFalse(reopened.authorized(old.lease, old.manifest.capabilities))
                assertTrue(reopened.authorized(fresh.lease, fresh.manifest.capabilities))
            } finally {
                reopened.close()
            }
        } finally {
            host.close()
            context.root.deleteRecursively()
        }
    }

    private fun testContext(): StorageContext {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        return StorageContext(app, File(app.cacheDir, "installation-${Uuid.random()}"))
    }

    private class StorageContext(app: android.content.Context, val root: File) : ContextWrapper(app) {
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
    }
}
