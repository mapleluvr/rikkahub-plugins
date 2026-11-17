package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.ExecutionLedger
import dev.rikkahub.plugins.protocol.Gate
import dev.rikkahub.plugins.protocol.GateKind
import dev.rikkahub.plugins.protocol.RunCheckpoint
import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.RunState
import dev.rikkahub.plugins.protocol.RunStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.toDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

class ApprovalServicePublicationTest {
    @Test fun firstModelToolBatchPublishesAndroidAndWebHandlesThenExecutesOnSameRun() = runBlocking {
        val koin = GlobalContext.get()
        val repository = koin.get<ConversationRepository>()
        val service = koin.get<ChatService>()
        val settingsStore = koin.get<SettingsStore>()
        val providers = koin.get<ProviderManager>()
        val originalSettings = settingsStore.awaitLoaded()
        val oldProvider = providers.getProvider("openai")
        val model = Model(modelId = "approval-fixture")
        val assistant = Assistant(
            chatModelId = model.id,
            streamOutput = false,
            localTools = listOf(LocalToolOption.AskUser, LocalToolOption.JavascriptEngine)
        )
        val provider = ProviderSetting.OpenAI(baseUrl = "https://fixture.invalid/v1", models = listOf(model))
        val bodies = CopyOnWriteArrayList<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            bodies +=
                Json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            val message = if (bodies.size ==
                1) {
                """{"role":"assistant","content":null,"tool_calls":[{"id":"eval","type":"function","function":{"name":"eval_javascript","arguments":"{\"code\":\"6 * 7\"}"}},{"id":"ask","type":"function","function":{"name":"ask_user","arguments":"{\"questions\":[{\"id\":\"q\",\"question\":\"Continue?\"}]}"}}]}"""
            } else {
                """{"role":"assistant","content":"done"}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """
                    {"id":"response","object":"chat.completion","model":"approval-fixture",
                     "choices":[{"index":0,"message":$message,"finish_reason":"stop"}]}
                    """.trimIndent().toResponseBody(
                        "application/json".toMediaType()
                    )
                ).build()
        }.build()
        val user = UIMessage.user("calculate and confirm")
        val initial = Conversation.ofId(Uuid.random(), assistantId = assistant.id)
            .copy(title = "approval fixture", config = ConversationConfig(chatModelId = model.id))
            .updateCurrentMessages(listOf(user))
        try {
            settingsStore.update {
                originalSettings.copy(
                    assistants = listOf(assistant),
                    assistantId = assistant.id,
                    providers = listOf(provider),
                    chatModelId = model.id,
                    enableAutoTitle = false,
                    enableSuggestion = false,
                    enableAutoCompress = false
                )
            }
            providers.registerProvider("openai", OpenAIProvider(client))
            repository.insertConversation(initial)
            service.addConversationReference(initial.id)
            service.regenerateAtMessage(initial.id, user.id)
            withTimeout(15000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            val waiting = repository.getConversationById(initial.id)!!
            assertEquals(service.errors.value.filter { it.conversationId == initial.id }.toString(), 1, bodies.size)
            val run = waiting.executionLedger().runs.values.single()
            assertEquals(RunStatus.ACTIVE, run.state.status)
            val reply = waiting.currentMessages.last()
            assertNotNull(reply.modelSnapshot)
            val pending = reply.getTools().single { it.isPending }
            assertNotNull(pending.approvalId)
            assertTrue(JsonInstant.encodeToString(waiting.toDto()).contains(pending.approvalId!!))
            assertTrue(reply.getTools().all { it.startedAt == null && it.output.isEmpty() })
            val waitingInMemory = service.getConversation(initial.id)
            for (owned in listOf(user, reply)) {
                val rejected = runCatching { service.deleteMessage(initial.id, owned.id) }.exceptionOrNull()
                assertTrue("Deleting a message owned by the waiting run must be rejected", rejected is RunHistoryConflict)
                assertEquals(waiting, repository.getConversationById(initial.id))
                assertEquals(waitingInMemory, service.getConversation(initial.id))
            }
            service.handleToolApproval(initial.id, pending.approvalId!!, approved = true, answer = "{\"q\":\"yes\"}")
            withTimeout(15000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            val finished = repository.getConversationById(initial.id)!!
            assertEquals(2, bodies.size)
            assertEquals(setOf(run.state.ref.runId), finished.executionLedger().runs.keys)
            val completed = finished.executionLedger().runs.getValue(run.state.ref.runId)
            assertEquals(RunStatus.COMPLETED, completed.state.status)
            assertEquals(1, completed.consumedToolPermits.size)
            val tools = finished.currentMessages.last().getTools()
            assertTrue(tools.all { it.isExecuted })
            assertTrue(tools.single { it.toolCallId == "eval" }.output.toString().contains("42"))
            service.handleToolApproval(initial.id, pending.approvalId!!, approved = true)
            withTimeout(15000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            assertEquals(2, bodies.size)
        } finally {
            service.deleteConversation(initial.id)
            service.removeConversationReference(initial.id)
            providers.registerProvider("openai", oldProvider)
            settingsStore.update { originalSettings }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun userRegenerationCannotTruncateTheInputOfAPersistentlyPausedRun() = runBlocking {
        val koin = GlobalContext.get()
        val repo = koin.get<ConversationRepository>()
        val service = koin.get<ChatService>()
        val user = UIMessage.user("source")
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("owned reply")))
        val initial = Conversation.ofId(
            Uuid.random()
        ).copy(title = "pause fixture").updateCurrentMessages(listOf(user, reply))
        val ref = RunRef(initial.id.toString(), user.id.toString(), "paused-fixture")
        val checkpoint =
            RunCheckpoint(
                RunState(ref, 5, 10, gates = mapOf("pause" to Gate("pause", GateKind.USER_PAUSE))),
                "BeforeModel",
                0,
                inputIds = listOf(user.id.toString(), reply.id.toString())
            )
        repo.insertConversation(initial.withExecutionLedger(ExecutionLedger(runs = mapOf(ref.runId to checkpoint))))
        service.addConversationReference(initial.id)
        try {
            service.regenerateAtMessage(initial.id, user.id)
            withTimeout(15000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            val disk = repo.getConversationById(initial.id)!!
            assertEquals(initial.messageNodes, disk.messageNodes)
            assertEquals(checkpoint, disk.executionLedger().runs.getValue(ref.runId))
            assertEquals(listOf(user, reply), disk.forInputPath(checkpoint.inputIds).currentMessages)
        } finally {
            service.deleteConversation(initial.id)
            service.removeConversationReference(initial.id)
        }
    }
}
