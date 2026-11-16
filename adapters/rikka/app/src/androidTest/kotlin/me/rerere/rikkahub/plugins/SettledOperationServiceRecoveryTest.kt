package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.RunControl
import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.plugins.builtin.BuiltinContextFeature
import me.rerere.rikkahub.service.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

/** Exercise the production recovery ordering and serializer after a durable adoption boundary. */
class SettledOperationServiceRecoveryTest {
    @Test
    fun coldServiceConsumesSettledSummaryBeforeBuildingFirstProviderRequest() = runBlocking {
        val koin = GlobalContext.get()
        val repo = koin.get<ConversationRepository>()
        val service = koin.get<ChatService>()
        val settingsStore = koin.get<SettingsStore>()
        val manager = koin.get<ProviderManager>()
        val settings = settingsStore.awaitLoaded()
        val oldProvider = manager.getProvider("openai")
        val model = Model(modelId = "recovery-fixture")
        val assistant = Assistant(chatModelId = model.id, streamOutput = false, localTools = emptyList())
        val provider = ProviderSetting.OpenAI(baseUrl = "https://fixture.invalid/v1", models = listOf(model))
        val bodies = CopyOnWriteArrayList<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            bodies +=
                Json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """{"id":"result","object":"chat.completion","model":"recovery-fixture","choices":[{"index":0,"message":{"role":"assistant","content":"resumed answer"},"finish_reason":"stop"}]}""".toResponseBody(
                        "application/json".toMediaType()
                    )
                ).build()
        }.build()
        val id = Uuid.random()
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("old response")))
        val initial = Conversation.ofId(
            id,
            assistantId = assistant.id
        ).copy(title = "recovery", config = ConversationConfig(chatModelId = model.id))
            .updateCurrentMessages(listOf(UIMessage.user("old source"), reply))
        var referenced = false
        try {
            settingsStore.update {
                settings.copy(
                    assistants = listOf(assistant),
                    assistantId = assistant.id,
                    providers = listOf(provider),
                    chatModelId = model.id,
                    enableAutoTitle = false,
                    enableSuggestion = false,
                    enableAutoCompress = false
                )
            }
            manager.registerProvider("openai", OpenAIProvider(client))
            repo.insertConversation(initial)
            val diskStore = object : ConversationStore {
                override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
                override suspend fun write(
                    previous: Conversation?,
                    current: Conversation
                ) = repo.updateConversation(current)
                override suspend fun writeControl(
                    previous: Conversation,
                    current: Conversation
                ) = repo.writePluginControl(current)
            }
            val preparing = ConversationSession(id, initial, this, diskStore, {}).also {
                it.initialize({ repo.getConversationById(id) })
            }
            val run = SessionAgentRun.open(preparing, initial.currentMessages) { _, _ -> null }
            val feature = BuiltinContextFeature { _, _, _, _ -> "adopted summary" }
            val call = initial.operationCall(feature.operation, feature.input(), run = run.ref)
            SessionRunStore(preparing).update(run.ref) { saved ->
                saved.copy(
                    state = RunControl.addGate(
                        saved.state.copy(stepsUsed = 1, attemptsUsed = 1),
                        Gate(call.id, GateKind.OPERATION_DEPENDENCY)
                    ),
                    nextStep = 1,
                    boundary = "AfterTools",
                    messageId = reply.id.toString(),
                    pendingOperation = call
                )
            }
            SessionOperationDispatcher(listOf(feature.operation)) { _, _ -> true }.execute(preparing, call)
            val disk = repo.getConversationById(id)!!
            assertEquals(AttemptStatus.SETTLED, disk.executionLedger().operations.getValue(call.id).status)
            assertNotNull(disk.executionLedger().runs.getValue(run.ref.runId).pendingOperation)
            assertEquals(2, disk.executionLedger().runs.getValue(run.ref.runId).inputIds.size)
            assertEquals(3, disk.currentMessages.size)
            // ChatService has never loaded this session: it must read the checkpoint from Room itself.
            service.addConversationReference(id)
            referenced = true
            service.regenerateAtMessage(id, reply.id)
            withTimeout(15000) { service.getGenerationJobStateFlow(id).first { it == null } }
            assertEquals(service.errors.value.filter { it.conversationId == id }.toString(), 1, bodies.size)
            val wire = bodies.single().getValue("messages").jsonArray.toString()
            assertTrue(wire, wire.contains("adopted summary"))
            assertFalse(wire, wire.contains("old source"))
            assertFalse(wire, wire.contains("old response"))
            val completed = repo.getConversationById(id)!!
            val saved = completed.executionLedger().runs.getValue(run.ref.runId)
            assertNull(saved.pendingOperation)
            assertEquals(RunStatus.COMPLETED, saved.state.status)
            assertEquals(2, saved.state.stepsUsed)
            assertEquals(2, saved.state.attemptsUsed)
            assertEquals("resumed answer", completed.currentMessages.last().toText())
            assertEquals(1, completed.currentMessages.count { it.isContextCheckpoint })
        } finally {
            if (referenced) {
                service.deleteConversation(id)
                service.removeConversationReference(id)
            } else {
                repo.getConversationById(id)?.let { repo.deleteConversation(it) }
            }
            manager.registerProvider("openai", oldProvider)
            settingsStore.update { settings }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
