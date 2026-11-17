package me.rerere.rikkahub.plugins

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.ExecutionLedger
import dev.rikkahub.plugins.protocol.Gate
import dev.rikkahub.plugins.protocol.GateKind
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.RunCheckpoint
import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.RunState
import dev.rikkahub.plugins.protocol.RunStatus
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/** A restored conversation may outlive its authorization directory and every plugin page. */
class PackageRecoveryUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun missingIdleSelectionCanBeClearedFromThePage() = exercise(active = false)

    @Test fun restoredRunKeepsOldAuthorityAndRequiresStopBeforeAdoption() = exercise(active = true)

    private fun exercise(active: Boolean) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val repository = koin.get<ConversationRepository>()
        val service = koin.get<ChatService>()
        val host = koin.get<RikkaPluginManager>().host()
        val bytes = fixture()
        val pkg = PluginArchive.decode(bytes)
        val oldLease = PackageLease(pkg.manifest.id, pkg.hash, 1, Uuid.random().toString())
        val user = UIMessage.user("Restored source")
        val initial = Conversation.ofId(Uuid.random()).copy(title = "Missing plugin fixture")
            .updateCurrentMessages(listOf(user))
            .withPackageSelection(SessionPackageSelection(packages = mapOf(pkg.manifest.id to pkg.hash)))
        val ref = RunRef(initial.id.toString(), user.id.toString(), "restored-run")
        val checkpoint = RunCheckpoint(
            RunState(ref, 5, 10, gates = mapOf("user" to Gate("user", GateKind.USER_PAUSE))),
            "BeforeModel",
            0,
            inputIds = listOf(user.id.toString()),
            pluginLeases = listOf(oldLease)
        )
        val restored = if (active) {
            initial.withExecutionLedger(ExecutionLedger(runs = mapOf(ref.runId to checkpoint)))
        } else {
            initial
        }
        repository.insertConversation(restored)
        service.addConversationReference(initial.id)
        var scenario: ActivityScenario<RouteActivity>? = null
        fun label(id: Int) = app.getString(id)
        fun awaitText(text: String) = compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        fun click(text: String) {
            compose.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
        }
        try {
            scenario = ActivityScenario.launch(conversationIntent(app, initial.id.toString()))
            awaitText(label(R.string.plugin_host_title))
            compose.onNode(hasText(label(R.string.plugin_host_title)) and hasClickAction()).performClick()
            awaitText(label(R.string.plugin_host_missing_package))
            compose.onNodeWithText(pkg.hash).assertExists()
            compose.onNodeWithText(label(R.string.plugin_host_import)).assertExists()
            compose.onNodeWithText(label(R.string.plugin_host_adopt)).assertExists()
            if (active) {
                click(label(R.string.plugin_host_clear_selection))
                compose.waitForIdle()
                assertEquals(
                    checkpoint.pluginLeases,
                    repository.getConversationById(initial.id)!!.executionLedger().runs.getValue(ref.runId).pluginLeases
                )
                assertTrue(runCatching { service.adoptCurrentPlugins(initial.id) }.isFailure)
                val candidate = host.prepare(bytes)
                val fresh = host.activate(candidate, candidate.pkg.manifest.capabilities)
                awaitText(label(R.string.plugin_host_package_not_authorized))
                assertFalse(host.authorized(oldLease, pkg.manifest.capabilities))
                assertTrue(host.authorized(fresh.lease, pkg.manifest.capabilities))
                service.resumePluginRun(initial.id)
                compose.waitForIdle()
                val waiting = repository.getConversationById(initial.id)!!.executionLedger().runs.getValue(ref.runId)
                assertEquals(RunStatus.ACTIVE, waiting.state.status)
                assertEquals(listOf(oldLease), waiting.pluginLeases)
                assertEquals(0, waiting.state.attemptsUsed)
                service.stopGeneration(initial.id)
            }
            click(label(R.string.plugin_host_clear_selection))
            compose.waitUntil(20_000) {
                service.getConversationFlow(initial.id).value.packageSelection()?.packages?.isEmpty() == true
            }
            assertTrue(repository.getConversationById(initial.id)!!.packageSelection()!!.packages.isEmpty())
            scenario.recreate()
            awaitText(label(R.string.plugin_host_clear_selection))
            compose.onNodeWithText(label(R.string.plugin_host_missing_package)).assertDoesNotExist()
            if (active) {
                click(label(R.string.plugin_host_adopt))
                compose.waitUntil(20_000) {
                    service.getConversationFlow(initial.id).value.packageSelection()?.packages?.get(pkg.manifest.id) == pkg.hash
                }
                assertEquals(
                    RunStatus.CANCELLED,
                    repository.getConversationById(initial.id)!!.executionLedger().runs.getValue(ref.runId).state.status
                )
                assertFalse(host.authorized(oldLease, pkg.manifest.capabilities))
            }
        } finally {
            scenario?.close()
            host.disable(pkg.manifest.id)
            service.stopGeneration(initial.id)
            service.deleteConversation(initial.id)
            service.removeConversationReference(initial.id)
        }
    }

    private fun fixture(): ByteArray {
        val original = InstrumentationRegistry.getInstrumentation().context.assets
            .open("plugins/review-report.rikkaplug").use { it.readBytes() }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { target ->
            ZipInputStream(ByteArrayInputStream(original)).use { source ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    val content = source.readBytes()
                    val bytes = if (entry.name == "manifest.json") {
                        val manifest = PluginContract.json.decodeFromString<PluginManifest>(
                            content.toString(Charsets.UTF_8)
                        )
                        PluginContract.json.encodeToString(PluginManifest.serializer(), manifest.copy(
                            id = "dev.rikka.restore.test" + Uuid.random().toString().replace("-", "")
                        )).toByteArray()
                    } else {
                        content
                    }
                    target.putNextEntry(ZipEntry(entry.name))
                    target.write(bytes)
                    target.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}
