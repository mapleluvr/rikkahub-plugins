package me.rerere.rikkahub.plugins

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.protocol.AttemptStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/** Real RouteActivity, document-result import, isolated package runtime and persisted Service receipts. */
class PluginUiWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun importConsentActionRecreationAndRevocationUseTheChatSession() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val koin = GlobalContext.get()
        val host = koin.get<RikkaPluginManager>().host()
        val repository = koin.get<ConversationRepository>()
        val service = koin.get<ChatService>()
        val pluginId = "dev.rikka.reviewreport"
        host.disable(pluginId)
        val conversation = Conversation.ofId(Uuid.random()).copy(title = "Plugin UI fixture")
            .updateCurrentMessages(listOf(UIMessage.user("TODO: review"), UIMessage.assistant("ready")))
        repository.insertConversation(conversation)
        service.addConversationReference(conversation.id)
        val file = File(app.cacheDir, "ui-review.rikkaplug")
        file.writeBytes(instrumentation.context.assets.open("plugins/review-report.rikkaplug").use { it.readBytes() })
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        // Only the system file picker is replaced; production OpenDocument callback reads the real URI.
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
                } else {
                    null
                }
        }
        instrumentation.addMonitor(picker)
        var scenario: ActivityScenario<RouteActivity>? = null
        fun label(id: Int) = app.getString(id)
        fun awaitText(text: String) = compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        fun click(text: String) {
            compose.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
        }
        try {
            scenario = ActivityScenario.launch(conversationIntent(app, conversation.id.toString()))
            awaitText(label(R.string.plugin_host_title))
            compose.onNode(hasText(label(R.string.plugin_host_title)) and hasClickAction()).performClick()
            awaitText(label(R.string.plugin_host_import))
            click(label(R.string.plugin_host_import))
            awaitText(label(R.string.plugin_host_consent))
            compose.onNodeWithText(label(R.string.plugin_host_read_context), substring = true).assertExists()
            assertTrue(host.state.value.packages.values.none { it.manifest.id == pluginId && it.enabled })
            compose.onNodeWithText(label(R.string.plugin_host_approve)).performClick()
            awaitText("会话审阅")
            click("会话审阅")
            awaitText("生成报告")
            click("生成报告")
            awaitText(label(R.string.plugin_host_settled))
            val confirmed = repository.getConversationById(conversation.id)!!
            val receipt = confirmed.executionLedger().operations.values.single()
            assertEquals(AttemptStatus.SETTLED, receipt.status)
            assertEquals(
                2,
                receipt.result!!.artifacts.single().content.jsonObject.getValue(
                    "messages"
                ).jsonPrimitive.content.toInt()
            )
            assertEquals(conversation.currentMessages.map { it.id }, confirmed.currentMessages.map { it.id })
            assertTrue(confirmed.executionLedger().runs.isEmpty())
            service.editMessage(
                conversation.id,
                conversation.currentMessages.first().id,
                listOf(me.rerere.ai.ui.UIMessagePart.Text("Edited after the panel was rendered"))
            )
            click("生成报告")
            awaitText("Displayed conversation changed; reopen the plugin action")
            assertEquals(1, repository.getConversationById(conversation.id)!!.executionLedger().operations.size)
            click(label(R.string.plugin_host_overlay))
            awaitText(label(R.string.plugin_host_close))
            compose.onNodeWithText(label(R.string.plugin_host_close)).performClick()
            awaitText(label(R.string.plugin_host_overlay))
            click(label(R.string.plugin_host_overlay))
            scenario.recreate()
            awaitText("生成报告")
            // Recreation disposes the former floating root and reloads the registered panel.
            compose.onNodeWithText(label(R.string.plugin_host_close)).assertDoesNotExist()
            click(label(R.string.plugin_host_overlay))
            awaitText(label(R.string.plugin_host_close))
            host.disable(pluginId)
            awaitText(label(R.string.plugin_host_expired))
            compose.onNodeWithText(label(R.string.plugin_host_settled)).assertDoesNotExist()
            service.selectMessageNode(conversation.id, conversation.messageNodes.first().id, 0)
            awaitText(label(R.string.plugin_host_settled))
            click(label(R.string.plugin_host_receipt_details))
            compose.onNodeWithText(receipt.call.id, substring = true).assertExists()
            compose.onNodeWithText("生成报告").assertDoesNotExist()
            compose.onNodeWithText(label(R.string.plugin_host_close)).assertDoesNotExist()
            assertEquals(1, repository.getConversationById(conversation.id)!!.executionLedger().operations.size)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitText(label(R.string.plugin_host_history))
            click(label(R.string.plugin_host_history))
            awaitText(label(R.string.plugin_host_settled))
            click(label(R.string.plugin_host_receipt_details))
            compose.onNodeWithText(receipt.call.id, substring = true).assertExists()
            // A later package can remove every panel; host-owned history still has its own entry.
            val manifest = host.state.value.packages.values.first {
                it.manifest.id == pluginId
            }.manifest.copy(
                version = "1.99.0",
                capabilities = setOf("artifacts.write"),
                tools = emptyList(),
                panels = emptyList()
            )
            val replacement = java.io.ByteArrayOutputStream().also { output ->
                java.util.zip.ZipOutputStream(output).use { zip ->
                    for ((name, text) in mapOf(
                        "manifest.json" to kotlinx.serialization.json.Json.encodeToString(manifest),
                        "main.js" to
                            "globalThis.rikkaPlugin = rk => rk.registerOperation('review', () => ({artifacts:[]}));"
                    )) {
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        zip.write(text.toByteArray())
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
            host.activate(host.prepare(replacement), manifest.capabilities)
            service.adoptCurrentPlugins(conversation.id)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitText(label(R.string.plugin_host_title))
            compose.onNode(hasText(label(R.string.plugin_host_title)) and hasClickAction()).performClick()
            awaitText(label(R.string.plugin_host_history))
            click(label(R.string.plugin_host_history))
            awaitText(label(R.string.plugin_host_settled))
            compose.onNodeWithText("生成报告").assertDoesNotExist()
        } finally {
            scenario?.close()
            instrumentation.removeMonitor(picker)
            host.disable(pluginId)
            service.deleteConversation(conversation.id)
            service.removeConversationReference(conversation.id)
            file.delete()
        }
    }
}
