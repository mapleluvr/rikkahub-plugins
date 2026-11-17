package me.rerere.rikkahub.plugins

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstantPretty
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Stored history remains readable without a current package, including records older than the preview. */
class PluginHistoryPagingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun allReportsAndOldUnknownReceiptAreReachableAcrossPages(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = GlobalContext.get().get<ConversationRepository>()
        val service = GlobalContext.get().get<ChatService>()
        val user = UIMessage.user("History source")
        val initial = Conversation.ofId(Uuid.random()).copy(title = "History paging")
            .updateCurrentMessages(listOf(user))
        val frame = listOf(user.sourceStamp())
        val handler = PackageLease("dev.example.no-longer-installed", "c".repeat(64), 1)
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
        repository.insertConversation(initial.withExecutionLedger(ExecutionLedger(operations = records)))
        service.addConversationReference(initial.id)
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
        } finally {
            scenario?.close()
            service.deleteConversation(initial.id)
            service.removeConversationReference(initial.id)
        }
    }
}
