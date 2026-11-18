package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginToolDeclaration
import dev.rikkahub.plugins.protocol.RunStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/** Real ChatService and isolated QuickJS; the Provider interceptor counts dispatch, not HTTP. */
class PluginToolServiceInterruptionTest {
    @Test
    fun isolatedTimeoutKeepsTheServiceRunWaitingAndStopAllowsFreshWork() = runBlocking {
        val koin = GlobalContext.get()
        val host = koin.get<RikkaPluginManager>().host()
        val repository = koin.get<ConversationRepository>()
        val service = koin.get<ChatService>()
        val settings = koin.get<SettingsStore>()
        val providers = koin.get<ProviderManager>()
        val originalSettings = settings.awaitLoaded()
        val oldProvider = providers.getProvider("openai")
        val candidate = host.prepare(archive())
        val installed = host.activate(candidate, candidate.pkg.manifest.capabilities)
        val toolName = pluginToolName(installed.manifest.id, "hold")
        val model = Model(modelId = "service-interruption", abilities = listOf(ModelAbility.TOOL))
        val assistant = Assistant(chatModelId = model.id, streamOutput = false)
        val provider = ProviderSetting.OpenAI(baseUrl = "https://fixture.invalid/v1", models = listOf(model))
        val requests = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val message = if (requests.size == 1) {
                """{"role":"assistant","content":null,"tool_calls":[{"id":"hold-call","type":"function","function":{"name":"$toolName","arguments":"{}"}}]}"""
            } else {
                """{"role":"assistant","content":"fresh work completed"}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """{
                      "id":"response",
                      "object":"chat.completion",
                      "model":"service-interruption",
                      "choices":[{"index":0,"message":$message,"finish_reason":"stop"}]
                    }""".trimIndent().toResponseBody(
                        "application/json".toMediaType()
                    )
                ).build()
        }.build()
        val user = UIMessage.user("Keep an unconfirmed tool on its original run")
        val initial = Conversation.ofId(Uuid.random(), assistantId = assistant.id)
            .copy(title = "Service interruption", config = ConversationConfig(chatModelId = model.id))
            .updateCurrentMessages(listOf(user))
        try {
            settings.update {
                originalSettings.copy(
                    assistants = listOf(assistant),
                    assistantId = assistant.id,
                    providers = listOf(provider),
                    chatModelId = model.id,
                    enableAutoTitle = false,
                    enableSuggestion = false,
                    enableAutoCompress = false,
                )
            }
            providers.registerProvider("openai", OpenAIProvider(client))
            repository.insertConversation(initial)
            service.addConversationReference(initial.id)
            service.regenerateAtMessage(initial.id, user.id)
            // The JS callback really enters the isolated Service and never returns. Its own deadline fires.
            withTimeout(30_000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            val waiting = repository.getConversationById(initial.id)!!
            val run = waiting.executionLedger().runs.values.single()
            val tool = waiting.currentMessages.last().getTools().single()
            assertEquals("run=$run; tool=$tool; requests=$requests", 1, requests.size)
            assertEquals(RunStatus.ACTIVE, run.state.status)
            assertEquals(listOf(installed.lease), run.pluginLeases)
            assertEquals("BeforeTool", run.boundary)
            assertEquals("hold-call", run.toolCallId)
            assertTrue("host.recovery" in run.state.gates)
            assertFalse(tool.isExecuted)
            assertTrue(tool.startedAt != null)
            assertTrue(tool.output.isEmpty())
            assertNull(tool.finishedAt)
            assertTrue(host.authorizedPackages(run.pluginLeases))
            assertTrue(service.errors.value.none { it.conversationId == initial.id })

            // Evict the transient Session; the production resume command must restore the same Room ledger.
            service.removeConversationReference(initial.id)
            service.addConversationReference(initial.id)
            service.resumePluginRun(initial.id)
            withTimeout(20_000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            val restored = repository.getConversationById(initial.id)!!
            val restoredRun = restored.executionLedger().runs.getValue(run.state.ref.runId)
            assertEquals(run.state.ref, restoredRun.state.ref)
            assertEquals(run.state.attemptsUsed, restoredRun.state.attemptsUsed)
            assertEquals(run.state.stepsUsed, restoredRun.state.stepsUsed)
            assertEquals(run.pluginLeases, restoredRun.pluginLeases)
            assertEquals(RunStatus.ACTIVE, restoredRun.state.status)
            assertTrue("host.recovery" in restoredRun.state.gates)
            assertEquals(1, requests.size)
            assertFalse(restored.currentMessages.last().getTools().single().isExecuted)

            // Real Service Stop is distinct from the runtime deadline and permits a fresh logical run.
            service.stopGeneration(initial.id)
            val stopped = repository.getConversationById(initial.id)!!
            assertEquals(RunStatus.CANCELLED, stopped.executionLedger().runs.getValue(run.state.ref.runId).state.status)
            service.sendMessage(initial.id, listOf(me.rerere.ai.ui.UIMessagePart.Text("fresh input")))
            withTimeout(20_000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            val completed = repository.getConversationById(initial.id)!!
            assertEquals(service.errors.value.toString(), 2, requests.size)
            assertEquals("fresh work completed", completed.currentMessages.last().toText())
            assertEquals(
                RunStatus.CANCELLED,
                completed.executionLedger().runs.getValue(run.state.ref.runId).state.status
            )
            assertTrue(completed.executionLedger().runs.values.any {
                it.state.ref.runId != run.state.ref.runId && it.state.status == RunStatus.COMPLETED
            })
        } finally {
            service.stopGeneration(initial.id)
            host.disable(installed.manifest.id)
            service.deleteConversation(initial.id)
            service.removeConversationReference(initial.id)
            providers.registerProvider("openai", oldProvider)
            settings.update { originalSettings }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun archive(): ByteArray {
        val manifest = PluginManifest(
            id = "dev.fixture.service" + Uuid.random().toString().replace("-", ""),
            name = "Service interruption",
            version = "1.0.0",
            capabilities = setOf("context.read", "tools.register"),
            tools = listOf(PluginToolDeclaration(
                id = "hold",
                title = "Hold",
                description = "Wait for the isolated runtime deadline without confirming a result",
                parameters = JsonObject(
                    mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))
                ),
                needsApproval = false,
            )),
        )
        val script = """
            globalThis.rikkaPlugin = api => api.registerTool('hold', async (_, context) => {
              await context.readMessages();
              while (true) { }
            });
        """.trimIndent()
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, text) in mapOf(
                    "manifest.json" to PluginContract.json.encodeToString(manifest),
                    "main.js" to script
                )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }
}
