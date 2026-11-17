package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.InstalledPlugin
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginCatalogState
import dev.rikkahub.plugins.protocol.PluginManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SessionPackageSelectionTest {
    private val pluginId = "dev.example.review"
    private val old = InstalledPlugin(
        PluginManifest(pluginId, "Review", "1.0.0"),
        PackageLease(pluginId, "a".repeat(64), 1),
        emptySet()
    )
    private val updated = old.copy(
        manifest = old.manifest.copy(version = "2.0.0"),
        lease = old.lease.copy(packageHash = "b".repeat(64), activationEpoch = 2)
    )
    private fun catalog(preferred: InstalledPlugin) = PluginCatalogState(
        nextEpoch = 3,
        packages = listOf(old, updated).associateBy { it.lease.packageHash },
        preferred = mapOf(pluginId to preferred.lease.packageHash)
    )

    @Test fun idleConversationRetainsItsVersionAcrossGlobalUpdateAndReopenUntilAdoption() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start")))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        assertEquals(listOf(old.lease), session.capturePackageSelection(catalog(old)))
        val restored = open(initial, store)
        assertEquals(listOf(old.lease), restored.capturePackageSelection(catalog(updated)))
        restored.commitExtension { it.withPackageSelection(catalog(updated).preferredSelection()) }
        assertEquals(listOf(updated.lease), open(initial, store).capturePackageSelection(catalog(updated)))
    }

    @Test fun emptySelectionDoesNotGainToolsAndRevokedVersionsNeverResolve() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start")))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        assertTrue(session.capturePackageSelection(PluginCatalogState()).isEmpty())
        assertTrue(open(initial, store).capturePackageSelection(catalog(updated)).isEmpty())
        val selection = catalog(old).preferredSelection()
        val disabled = catalog(updated).copy(packages = catalog(updated).packages.mapValues { (_, value) ->
            value.copy(enabled = false)
        })
        assertTrue(selection.resolve(disabled).isEmpty())
        val reenabled = disabled.copy(packages =
            disabled.packages + (old.lease.packageHash to old.copy(lease = old.lease.copy(activationEpoch = 5))))
        assertEquals(listOf(old.lease.copy(activationEpoch = 5)), selection.resolve(reenabled))
    }

    @Test fun selectionWriteFailureDoesNotPublishOrDispatchAnUnconfirmedSelection() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start")))
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        store.beforeWrite = { error("storage unavailable") }
        assertTrue(runCatching { session.capturePackageSelection(catalog(old)) }.isFailure)
        assertEquals(null, session.state.value.packageSelection())
        assertEquals(null, store.persisted!!.packageSelection())
    }

    @Test fun forkKeepsVersionConfigurationWithoutInheritingRunAuthority() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start")))
        val session = open(initial, FakeConversationStore(initial))
        session.capturePackageSelection(catalog(old))
        SessionAgentRun.open(session, initial.currentMessages, pluginLeases = listOf(old.lease)) { _, _ -> null }
        val fork = initial.copy(id = Uuid.random()).inheritPluginHistory(session.state.value)
        assertEquals(mapOf(pluginId to old.lease.packageHash), fork.packageSelection()?.packages)
        assertTrue(fork.executionLedger().runs.isEmpty())
        assertEquals(listOf(old.lease), fork.packageLeases(catalog(updated)))
    }

    @Test fun missingRestoredPackageHasReadableIdentityWithoutSilentReplacement() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("restore")))
            .withPackageSelection(catalog(old).preferredSelection())
        val restored = catalog(updated).copy(packages = mapOf(updated.lease.packageHash to updated))
        val view = initial.packageAvailability(restored).single()
        assertEquals(pluginId, view.pluginId)
        assertEquals(old.lease.packageHash, view.hash)
        assertEquals(SessionPackageStatus.MISSING, view.status)
        val session = open(initial, FakeConversationStore(initial))
        assertTrue(runCatching { session.capturePackageSelection(restored) }.isFailure)
        assertEquals(old.lease.packageHash, session.state.value.packageSelection()!!.packages[pluginId])
        session.commitExtension { it.withPackageSelection(SessionPackageSelection()) }
        assertTrue(session.capturePackageSelection(restored).isEmpty())
    }

    @Test fun missingActiveLeaseRemainsPinnedEvenAfterSamePackageIsReauthorized() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("restore")))
        val session = open(initial, FakeConversationStore(initial))
        SessionAgentRun.open(session, initial.currentMessages, pluginLeases = listOf(old.lease)) { _, _ -> null }
        assertEquals(
            SessionPackageStatus.MISSING,
            session.state.value.packageAvailability(PluginCatalogState()).single().status
        )
        val reauthorized = catalog(old).copy(packages = mapOf(
            old.lease.packageHash to
                old.copy(lease = old.lease.copy(activationEpoch = 10))
        ))
        val view = session.state.value.packageAvailability(reauthorized).single()
        assertEquals(SessionPackageStatus.REVOKED, view.status)
        assertEquals(old.lease, view.lease)
        assertEquals(listOf(old.lease), session.state.value.packageLeases(reauthorized))
    }

    private suspend fun CoroutineScope.open(initial: Conversation, store: FakeConversationStore) =
        ConversationSession(initial.id, initial, this, store, {}).also { it.initialize({ store.persisted }) }
}
