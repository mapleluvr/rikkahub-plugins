package me.rerere.rikkahub.plugins

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.editMessage
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstantPretty
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/** Stored history remains readable without a current package, including records older than the preview. */
class PluginHistoryPagingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun allReportsAndOldUnknownReceiptAreReachableAcrossPages() = history(withRevokedSibling = false)

    @Test fun revokedPackageHistoryPaginatesOnlyTheSelectedSiblingBranch() = history(withRevokedSibling = true)

    private fun history(withRevokedSibling: Boolean): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = GlobalContext.get().get<ConversationRepository>()
        val service = GlobalContext.get().get<ChatService>()
        val user = UIMessage.user("History source")
        val initial = Conversation.ofId(Uuid.random()).copy(title = "History paging")
            .updateCurrentMessages(listOf(user))
        val frame = listOf(user.sourceStamp())
        val host = GlobalContext.get().get<RikkaPluginManager>().host()
        val installed = if (withRevokedSibling) {
            val bytes = fixture()
            val manifest = PluginArchive.decode(bytes).manifest
            host.activate(host.prepare(bytes), manifest.capabilities)
        } else {
            null
        }
        val handler = installed?.lease ?: PackageLease("dev.example.no-longer-installed", "c".repeat(64), 1)
        val records = (0 until 23).associate { index ->
            val id = "history-$index"
            val call = OperationCall(
                id,
                "history/report-$index",
                BranchRef(initial.id.toString(), user.id.toString()),
                JsonNull,
                handler,
                frame = frame
            )
            id to OperationRecord(
                call,
                frame,
                status = if (index == 0) AttemptStatus.UNKNOWN else AttemptStatus.SETTLED,
                result = if (index == 0) {
                    null
                } else {
                    OperationResult(artifacts = listOf(ArtifactDraft("review", JsonPrimitive("saved-report-$index"))))
                }
            )
        }
        val branched = if (withRevokedSibling) {
            initial.editMessage(user.id, listOf(UIMessagePart.Text("Sibling history source")))
                .withPackageSelection(
                    SessionPackageSelection(packages = mapOf(handler.pluginId to handler.packageHash))
                )
        } else {
            initial
        }
        val sibling = if (withRevokedSibling) {
            val source = branched.currentMessages.single()
            val stamps = listOf(source.sourceStamp())
            (0 until 13).associate { index ->
                val id = "sibling-$index"
                val call = OperationCall(
                    id,
                    "sibling/report-$index",
                    BranchRef(initial.id.toString(), source.id.toString()),
                    JsonNull,
                    handler,
                    frame = stamps
                )
                id to OperationRecord(
                    call,
                    stamps,
                    status = AttemptStatus.SETTLED,
                    result = OperationResult(artifacts = listOf(ArtifactDraft("review", JsonPrimitive(id))))
                )
            }
        } else {
            emptyMap()
        }
        repository.insertConversation(branched.withExecutionLedger(ExecutionLedger(operations = records + sibling)))
        service.addConversationReference(initial.id)
        if (withRevokedSibling) {
            host.disable(handler.pluginId)
            assertFalse(host.authorized(handler, installed!!.manifest.capabilities))
            service.selectMessageNode(initial.id, branched.messageNodes.first().id, 0)
        }
        var scenario: ActivityScenario<RouteActivity>? = null
        fun awaitText(text: String) = compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        try {
            scenario = ActivityScenario.launch(conversationIntent(app, initial.id.toString()))
            val title = app.getString(R.string.plugin_host_title)
            awaitText(title)
            compose.onNode(hasText(title) and hasClickAction()).performClick()
            val history = app.getString(R.string.plugin_host_history)
            awaitText(history)
            compose.onNodeWithText(history).performScrollTo().performClick()
            awaitText("history/report-22")
            compose.onNodeWithText("history/report-0").assertDoesNotExist()
            val next = app.getString(R.string.plugin_host_history_next)
            val previous = app.getString(R.string.plugin_host_history_previous)
            compose.onNodeWithText(next).performScrollTo().performClick()
            awaitText("history/report-12")
            compose.onNodeWithText(next).performScrollTo().performClick()
            awaitText("history/report-0")
            compose.onNodeWithText("history/report-0").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("\"saved-report-1\"").assertExists()
            compose.onAllNodesWithText(app.getString(R.string.plugin_host_receipt_details)).onLast()
                .performScrollTo().performClick()
            compose.onNodeWithText(JsonInstantPretty.encodeToString(records.getValue("history-0"))).assertExists()
            compose.onNodeWithText(next).assertIsNotEnabled()
            compose.onNodeWithText(previous).performScrollTo().performClick()
            awaitText("history/report-12")
            compose.onNodeWithText(previous).performScrollTo().performClick()
            awaitText("history/report-22")
            compose.onNodeWithText(previous).assertIsNotEnabled()
            if (withRevokedSibling) {
                compose.onNodeWithText("sibling/report-12").assertDoesNotExist()
                service.selectMessageNode(initial.id, branched.messageNodes.first().id, 1)
                awaitText("sibling/report-12")
                compose.onNodeWithText("history/report-22").assertDoesNotExist()
                compose.onNodeWithText(next).performScrollTo().performClick()
                awaitText("sibling/report-0")
                compose.onNodeWithText(next).assertIsNotEnabled()
                compose.onNodeWithText(previous).performScrollTo().performClick()
                awaitText("sibling/report-12")
                service.selectMessageNode(initial.id, branched.messageNodes.first().id, 0)
                awaitText("history/report-22")
                compose.onNodeWithText("sibling/report-12").assertDoesNotExist()
            }
        } finally {
            scenario?.close()
            installed?.let { host.disable(it.manifest.id) }
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
                            id = "dev.rikka.history.test" + Uuid.random().toString().replace("-", "")
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
