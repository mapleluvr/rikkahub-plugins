package me.rerere.rikkahub.plugins

import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.protocol.RunStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class PackagedToolServiceTest {
    @Test fun approvalPinsOriginalPackageAcrossUpdateAndPersistsSeparateDetails() = exercise(revoke = false)

    @Test fun revokedApprovalCannotExecuteAfterReenable() = exercise(revoke = true)

    private fun exercise(revoke: Boolean) = runBlocking {
        val koin = GlobalContext.get()
        val host = koin.get<RikkaPluginManager>().host()
        val repository = koin.get<ConversationRepository>()
        val service = koin.get<ChatService>()
        val settings = koin.get<SettingsStore>()
        val providers = koin.get<ProviderManager>()
        val originalSettings = settings.awaitLoaded()
        val oldProvider = providers.getProvider("openai")
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("plugins/review-report.rikkaplug").use { it.readBytes() }
        val candidate = host.prepare(bytes)
        val id = candidate.pkg.manifest.id
        host.disable(id)
        val prepared = host.prepare(bytes)
        val installed = host.activate(prepared, prepared.pkg.manifest.capabilities)
        val toolName = pluginToolName(id, "review")
        val model = Model(modelId = "packaged-tool", abilities = listOf(ModelAbility.TOOL))
        val assistant = Assistant(chatModelId = model.id, streamOutput = false)
        val provider = ProviderSetting.OpenAI(baseUrl = "https://fixture.invalid/v1", models = listOf(model))
        val requests = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val message = if (requests.size == 1) {
                """{"role":"assistant","content":null,"tool_calls":[{"id":"review","type":"function","function":{"name":"$toolName","arguments":"{}"}}]}"""
            } else {
                """{"role":"assistant","content":"done"}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """{"id":"response","object":"chat.completion","model":"packaged-tool","choices":[{"index":0,"message":$message,"finish_reason":"stop"}]}""".toResponseBody(
                        "application/json".toMediaType()
                    )
                ).build()
        }.build()
        val user = UIMessage.user("TODO: inspect the current branch")
        val initial = Conversation.ofId(Uuid.random(), assistantId = assistant.id)
            .copy(title = "Packaged tool fixture", config = ConversationConfig(chatModelId = model.id))
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
            withTimeout(20_000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            val waiting = repository.getConversationById(initial.id)!!
            assertEquals(service.errors.value.toString(), 1, requests.size)
            assertTrue(Json.parseToJsonElement(requests[0]).jsonObject.getValue("tools").jsonArray.any {
                it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content == toolName
            })
            val run = waiting.executionLedger().runs.values.single()
            assertEquals(listOf(installed.lease), run.pluginLeases)
            val pending = waiting.currentMessages.last().getTools().single()
            assertTrue(pending.isPending)
            assertEquals(installed.lease.packageHash,
                pending.details!!.getValue("plugin").jsonObject.getValue("packageHash").jsonPrimitive.content)
            assertEquals("会话审阅", pending.details!!.getValue("title").jsonPrimitive.content)
            if (revoke) host.disable(id)
            val replacement = host.prepare(if (revoke) bytes else changedVersion(bytes))
            val latest = host.activate(replacement, replacement.pkg.manifest.capabilities)
            assertTrue(latest.lease != installed.lease)
            service.handleToolApproval(initial.id, pending.approvalId!!, true)
            withTimeout(20_000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            val current = repository.getConversationById(initial.id)!!
            val saved = current.executionLedger().runs.getValue(run.state.ref.runId)
            assertEquals(run.pluginLeases, saved.pluginLeases)
            val tool = current.currentMessages.last().getTools().single()
            if (revoke) {
                assertEquals(1, requests.size)
                assertEquals(RunStatus.ACTIVE, saved.state.status)
                assertFalse(tool.isExecuted)
                assertEquals(null, tool.startedAt)
                service.stopGeneration(initial.id)
            } else {
                assertEquals(2, requests.size)
                assertEquals(RunStatus.COMPLETED, saved.state.status)
                assertTrue(tool.output.toString().contains("已审阅"))
                assertFalse(tool.output.toString().contains("UPDATED"))
                assertEquals(
                    installed.lease.packageHash,
                    tool.details!!.getValue("plugin").jsonObject.getValue("packageHash").jsonPrimitive.content
                )
                assertTrue(tool.details!!.getValue("data").jsonObject.containsKey("todos"))
                assertFalse(requests[1].contains(installed.lease.packageHash))
                assertFalse(requests[1].contains("\"todos\""))
                service.handleToolApproval(initial.id, pending.approvalId!!, true)
                assertEquals(2, requests.size)
            }
        } finally {
            host.disable(id)
            service.deleteConversation(initial.id)
            service.removeConversationReference(initial.id)
            providers.registerProvider("openai", oldProvider)
            settings.update { originalSettings }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun changedVersion(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { target ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { source ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    val original = source.readBytes()
                    val data = when (entry.name) {
                        "manifest.json" -> original.toString(Charsets.UTF_8).replace("0.1.0", "0.2.0").toByteArray()
                        "main.js" -> original.toString(Charsets.UTF_8).replace("已审阅", "UPDATED").toByteArray()
                        else -> original
                    }
                    target.putNextEntry(ZipEntry(entry.name))
                    target.write(data)
                    target.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}
