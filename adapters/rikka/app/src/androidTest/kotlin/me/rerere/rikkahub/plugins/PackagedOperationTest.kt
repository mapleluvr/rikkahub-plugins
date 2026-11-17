package me.rerere.rikkahub.plugins

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.protocol.AttemptStatus
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.ConversationStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** TypeScript-built packages, isolated QuickJS, Room restart, then the production Provider serializer. */
class PackagedOperationTest {
    @Test fun packagedReportAndReductionPersistWithoutSummaryAndChangeOnlyTheNextRequest() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "packaged-operations-").toFile()
        val local = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(directory, "db/$name").also { it.parentFile!!.mkdirs() }
        }
        var database = AppDatabaseFactory.create(local)
        var host = PluginHost.open(app, File(directory, "plugins"))
        var requests = 0
        var body: JsonObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            body = Json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """{"id":"result","object":"chat.completion","model":"fixture","choices":[{"index":0,"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}]}"""
                        .toResponseBody("application/json".toMediaType())
                ).build()
        }.build()
        try {
            for (example in listOf("context-reduce", "review-report")) {
                val bytes = InstrumentationRegistry.getInstrumentation().context.assets
                    .open("plugins/$example.rikkaplug").use { it.readBytes() }
                val candidate = host.prepare(bytes)
                host.activate(candidate, candidate.pkg.manifest.capabilities)
            }
            var repository = repository(database)
            val id = Uuid.random()
            repository.insertConversation(Conversation.ofId(id).updateCurrentMessages(listOf(
                UIMessage.user("old input TODO"),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
                    toolCallId = "old-call",
                    toolName = "search",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("old result")),
                ))),
                UIMessage.assistant("retained reply"),
                UIMessage.user("current input"),
            )))
            var session = open(id, repository)
            val history = session.state.value.currentMessages
            val report = host.operationDefinitions().single { it.id.endsWith("/review") }
            val reportCall = session.state.value.operationCall(report, JsonNull)
            val result = host.operationDispatcher().execute(session, reportCall)
            assertEquals(4, result.artifacts.single().content.jsonObject["messages"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, result.artifacts.single().content.jsonObject["todos"]!!.jsonArray.size)
            assertEquals(null, result.projection)
            assertEquals(history, session.state.value.projectedContext(history))
            val reduce = host.operationDefinitions().single { it.id.endsWith("/reduce") }
            val reduceCall = session.state.value.operationCall(reduce, JsonNull)
            host.operationDispatcher().execute(session, reduceCall)
            assertEquals(0, requests)
            assertEquals(history, repository.getConversationById(id)!!.currentMessages)
            assertTrue(session.state.value.executionLedger().operations.values.all {
                it.status == AttemptStatus.SETTLED && it.modelAttempts == 0 && it.effectAdmissions == 1
            })
            database.close()
            host.close()
            database = AppDatabaseFactory.create(local)
            host = PluginHost.open(app, File(directory, "plugins"))
            repository = repository(database)
            session = open(id, repository)
            val receipt = session.state.value.executionLedger().operations.getValue(reportCall.id)
            assertEquals(result, host.operationDispatcher().execute(session, reportCall))
            assertEquals(receipt, session.state.value.executionLedger().operations.getValue(reportCall.id))
            @Suppress("UNCHECKED_CAST")
            val provider = OpenAIProvider(client) as Provider<ProviderSetting>
            val run = SessionAgentRun.open(session, history) { _, _ -> null }
            val hooks = object : AgentHooks {
                override suspend fun prepareStep(messages: List<UIMessage>) =
                    StepRequest(messages, TextGenerationParams(Model(modelId = "fixture")))
            }
            runAgent(
                run.bind(AgentRequest(
                    provider,
                    ProviderSetting.OpenAI(baseUrl = "https://fixture.invalid/v1"),
                    history,
                    stream = false
                ), Uuid.random()),
                run.hooks(hooks)
            ).collect(run::accept)
            assertEquals(1, requests)
            val sent = body!!.getValue("messages").jsonArray.map {
                it.jsonObject.getValue("content").jsonPrimitive.content
            }
            assertEquals(listOf("retained reply", "current input"), sent)
            assertEquals(history, session.state.value.currentMessages.take(history.size))
            assertTrue(session.state.value.currentMessages.none { it.isContextCheckpoint })
        } finally {
            host.close()
            database.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            directory.deleteRecursively()
        }
    }

    private suspend fun CoroutineScope.open(id: Uuid, repository: ConversationRepository): ConversationSession {
        val initial = repository.getConversationById(id)!!
        val store = object : ConversationStore {
            override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
            override suspend fun write(
                previous: Conversation?,
                current: Conversation
            ) = repository.updateConversation(current)
            override suspend fun writeControl(
                previous: Conversation,
                current: Conversation
            ) = repository.writePluginControl(current)
        }
        return ConversationSession(id, initial, this, store, {}).also { it.initialize({ initial }) }
    }

    private fun repository(database: AppDatabase) = ConversationRepository(
        database.conversationDao(),
        database.messageNodeDao(),
        database.favoriteDao(),
        database,
        GlobalContext.get().get(),
        MessageFtsManager(database),
    )
}
