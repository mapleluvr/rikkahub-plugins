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
    @Test fun installationNamespacesSeparateIdenticalEpochsAndRejectMixedCatalogs() = runBlocking {
        val first = PluginCatalog(PluginCatalogState(issuerId = "00000000-0000-4000-8000-000000000001")) {}
        val second = PluginCatalog(PluginCatalogState(issuerId = "00000000-0000-4000-8000-000000000002")) {}
        val bytes = pkg("1.0.0")
        val old = first.activate(bytes, bytes.manifest.capabilities, 0)
        val fresh = second.activate(bytes, bytes.manifest.capabilities, 0)
        assertEquals(old.lease.activationEpoch, fresh.lease.activationEpoch)
        assertFalse(second.authorized(old.lease, bytes.manifest.capabilities))
        assertTrue(second.authorized(fresh.lease, bytes.manifest.capabilities))
        assertTrue(runCatching {
            PluginCatalog(second.state.value.copy(packages = first.state.value.packages)) {}
        }.isFailure)
        // Existing catalogs keep their legacy identity; imported conversation data grants nothing.
        val legacy = PluginCatalog(PluginCatalogState()) {}
        val retained = legacy.activate(bytes, bytes.manifest.capabilities, 0)
        assertTrue(PluginCatalog(legacy.state.value) {}.authorized(retained.lease, bytes.manifest.capabilities))
        assertFalse(second.authorized(retained.lease, bytes.manifest.capabilities))
    }

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
        val selectedOld = catalog.activate(old, old.manifest.capabilities, 2, allowRollback = true)
        assertEquals(
            "Selecting an already retained package must keep live callers' lease",
            first.lease,
            selectedOld.lease
        )
        assertEquals(old.hash, catalog.state.value.preferred.getValue(old.manifest.id))
        catalog.disable(old.manifest.id)
        assertFalse(catalog.authorized(first.lease, old.manifest.capabilities))
        assertFalse(catalog.authorized(second.lease, next.manifest.capabilities))
        val enabled = catalog.activate(
            old,
            old.manifest.capabilities,
            catalog.state.value.revision,
            allowRollback = true
        )
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

    @Test fun rollbackNeverLowersHighWaterAndPublisherChangesRequireSeparateConsent() = runBlocking {
        var disk = PluginCatalogState()
        val catalog = PluginCatalog(disk) { disk = it }
        val origin = dev.rikkahub.plugins.protocol.PluginReleaseOrigin(
            7,
            "owner/repo",
            10,
            "v2.0.0",
            11,
            "a".repeat(64)
        )
        val latest = pkg("2.0.0")
        catalog.activate(latest, latest.manifest.capabilities, 0, origin = origin)
        val old = pkg("1.0.0")
        assertTrue(runCatching {
            catalog.activate(old, old.manifest.capabilities, disk.revision, origin = origin)
        }.isFailure)
        catalog.activate(old, old.manifest.capabilities, disk.revision, origin = origin, allowRollback = true)
        assertEquals("2.0.0", disk.updatePolicies.getValue(latest.manifest.id).highWaterVersion)
        catalog.disable(latest.manifest.id)
        val restored = PluginCatalog(disk) { disk = it }
        val next = pkg("2.1.0")
        for (changed in listOf(null, origin.copy(repositoryId = 8), origin.copy(publisherKey = null))) {
            assertTrue(runCatching {
                restored.activate(next, next.manifest.capabilities, disk.revision, origin = changed)
            }.isFailure)
        }
        restored.activate(
            next,
            next.manifest.capabilities,
            disk.revision,
            origin = origin.copy(repository = "new/repo")
        )
        assertEquals("new/repo", disk.updatePolicies.getValue(next.manifest.id).repository)
    }

    @Test fun legacyCatalogConsentUsesTheSamePolicyAsActivation() = runBlocking {
        val first = PluginCatalog(PluginCatalogState()) {}
        val old = pkg("2.0.0")
        first.activate(old, old.manifest.capabilities, 0)
        val legacy = first.state.value.copy(updatePolicies = emptyMap())
        assertEquals("2.0.0", legacy.updatePolicy(old.manifest.id)?.highWaterVersion)
        val restored = PluginCatalog(legacy) {}
        val origin = dev.rikkahub.plugins.protocol.PluginReleaseOrigin(7, "owner/repo", 10, "v2.0.0", 11)
        assertTrue(runCatching {
            restored.activate(old, old.manifest.capabilities, legacy.revision, origin = origin)
        }.isFailure)
        restored.activate(old, old.manifest.capabilities, legacy.revision, origin = origin, allowSourceChange = true)
        assertEquals(7L, restored.state.value.updatePolicy(old.manifest.id)?.repositoryId)
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
