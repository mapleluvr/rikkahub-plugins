package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.ExecutionLedger
import dev.rikkahub.plugins.protocol.Gate
import dev.rikkahub.plugins.protocol.GateKind
import dev.rikkahub.plugins.protocol.RunCheckpoint
import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.RunState
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.editMessage
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.routes.conversationRoutes
import me.rerere.rikkahub.web.startWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Real route, Service and Room, served on an ephemeral loopback port without changing app server settings. */
class HistoryMutationHttpTest {
    @Test
    fun deletionReturnsConflictForOwnedInputAndAllowsAnUnrelatedSibling() = runBlocking {
        val koin = GlobalContext.get()
        val service = koin.get<ChatService>()
        val repository = koin.get<ConversationRepository>()
        val settings = koin.get<SettingsStore>()
        val folders = koin.get<FolderRepository>()
        val user = UIMessage.user("owned")
        val original = Conversation.ofId(Uuid.random()).copy(title = "HTTP history fixture")
            .updateCurrentMessages(listOf(user)).editMessage(user.id, listOf(UIMessagePart.Text("sibling")))
        val sibling = original.currentMessages.single()
        val ref = RunRef(original.id.toString(), user.id.toString(), "paused")
        val checkpoint = RunCheckpoint(
            RunState(ref, 5, 10, gates = mapOf("user" to Gate("user", GateKind.USER_PAUSE))),
            "BeforeModel",
            0,
            inputIds = listOf(user.id.toString()),
        )
        val source = original.withExecutionLedger(ExecutionLedger(runs = mapOf(ref.runId to checkpoint)))
        repository.insertConversation(source)
        service.addConversationReference(source.id)
        val server = startWebServer(port = 0, host = "127.0.0.1") {
            install(ContentNegotiation) { json(JsonInstant) }
            routing { conversationRoutes(service, repository, folders, settings) }
        }
        val client = OkHttpClient()
        try {
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().single().port
            suspend fun delete(id: Uuid): Int = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("http://127.0.0.1:$port/conversations/${source.id}/messages/$id").delete().build()
                client.newCall(request).execute().use { it.code }
            }
            assertEquals(409, delete(user.id))
            assertEquals(source.messageNodes, repository.getConversationById(source.id)!!.messageNodes)
            assertEquals(200, delete(sibling.id))
            val saved = repository.getConversationById(source.id)!!
            assertEquals(listOf(user), saved.currentMessages)
            assertEquals(checkpoint, saved.executionLedger().runs.getValue(ref.runId))
            service.stopGeneration(source.id)
            assertEquals(200, delete(user.id))
            assertEquals(emptyList<UIMessage>(), repository.getConversationById(source.id)!!.currentMessages)
        } finally {
            server.stop(0, 1000)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            service.deleteConversation(source.id)
            service.removeConversationReference(source.id)
        }
    }
}
