package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.PluginCatalogState
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginOperationDeclaration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginCatalogTest {
    @Test fun failedActivationNeverPublishesCandidateAndColdReadUsesConfirmedVersion() = runBlocking {
        var disk = PluginCatalogState()
        var fail = false
        val catalog = PluginCatalog(disk) { if (fail) error("storage failed") else disk = it }
        val old = pkg("1.0.0")
        val first = catalog.activate(old, old.manifest.capabilities, 0)
        val before = disk
        fail = true
        val next = pkg("1.1.0")
        assertTrue(runCatching { catalog.activate(next, next.manifest.capabilities, 1) }.isFailure)
        assertEquals(before, disk)
        assertEquals(before, catalog.state.value)
        assertTrue(PluginCatalog(disk) {}.authorized(first.lease, old.manifest.capabilities))
        assertFalse(next.hash in disk.packages)
    }

    @Test fun oldLeaseSurvivesPreferredVersionChangesButDisableAndReenableCannotReviveIt() = runBlocking {
        val catalog = PluginCatalog(PluginCatalogState()) {}
        val old = pkg("1.0.0")
        val next = pkg("1.1.0")
        val first = catalog.activate(old, old.manifest.capabilities, 0)
        val second = catalog.activate(next, next.manifest.capabilities, 1)
        assertTrue(catalog.authorized(first.lease, old.manifest.capabilities))
        assertTrue(catalog.authorized(second.lease, next.manifest.capabilities))
        val selectedOld = catalog.activate(old, old.manifest.capabilities, 2)
        assertEquals(
            "Selecting an already retained package must keep live callers' lease",
            first.lease,
            selectedOld.lease
        )
        assertEquals(old.hash, catalog.state.value.preferred.getValue(old.manifest.id))
        catalog.disable(old.manifest.id)
        assertFalse(catalog.authorized(first.lease, old.manifest.capabilities))
        assertFalse(catalog.authorized(second.lease, next.manifest.capabilities))
        val enabled = catalog.activate(old, old.manifest.capabilities, catalog.state.value.revision)
        assertNotEquals(first.lease, enabled.lease)
        assertFalse(catalog.authorized(first.lease, old.manifest.capabilities))
        assertTrue(catalog.authorized(enabled.lease, old.manifest.capabilities))
    }

    @Test fun concurrentCandidatesCannotCommitAgainstTheSameCatalogRevision() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var disk = PluginCatalogState()
        val catalog = PluginCatalog(disk) {
            entered.complete(Unit)
            release.await()
            disk = it
        }
        val old = pkg("1.0.0")
        val next = pkg("1.1.0")
        val first = async { catalog.activate(old, old.manifest.capabilities, 0) }
        entered.await()
        assertTrue(catalog.state.value.packages.isEmpty())
        val second = async { runCatching { catalog.activate(next, next.manifest.capabilities, 0) } }
        release.complete(Unit)
        first.await()
        assertTrue(second.await().isFailure)
        assertEquals(setOf(old.hash), disk.packages.keys)
    }

    @Test fun cancellationAfterDurableWriteStillPublishesTheConfirmedCatalog() = runBlocking {
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var disk = PluginCatalogState()
        val catalog = PluginCatalog(disk) {
            disk = it
            written.complete(Unit)
            release.await()
        }
        val candidate = pkg("1.0.0")
        val activation = async { catalog.activate(candidate, candidate.manifest.capabilities, 0) }
        written.await()
        activation.cancel()
        release.complete(Unit)
        activation.join()
        assertEquals("Cancellation must not split disk and published registry", disk, catalog.state.value)
        assertEquals(candidate.hash, catalog.state.value.preferred[candidate.manifest.id])
    }

    @Test fun changedSameVersionBytesAndMissingConsentAreRejected() = runBlocking {
        val catalog = PluginCatalog(PluginCatalogState()) {}
        val old = pkg("1.0.0")
        catalog.activate(old, old.manifest.capabilities, 0)
        val changed = pkg("1.0.0", "changed bytes")
        assertTrue(runCatching { catalog.activate(changed, changed.manifest.capabilities, 1) }.isFailure)
        val next = pkg("1.1.0")
        assertTrue(runCatching { catalog.activate(next, emptySet(), 1) }.isFailure)
        assertEquals(setOf(old.hash), catalog.state.value.packages.keys)
    }

    private fun pkg(version: String, source: String = "source") = ByteArrayOutputStream().let { output ->
        val manifest = PluginManifest(
            "dev.example.review",
            "Review",
            version,
            capabilities = setOf("artifacts.write"),
            operations = listOf(PluginOperationDeclaration("review", "Review")),
        )
        ZipOutputStream(output).use { zip ->
            for ((name, text) in mapOf("manifest.json" to Json.encodeToString(manifest), "main.js" to source)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        PluginArchive.decode(output.toByteArray())
    }
}
