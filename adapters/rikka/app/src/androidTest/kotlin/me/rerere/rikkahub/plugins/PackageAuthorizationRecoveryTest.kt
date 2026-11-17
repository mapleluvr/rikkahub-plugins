package me.rerere.rikkahub.plugins

import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import me.rerere.ai.provider.Model
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
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class PackageAuthorizationRecoveryTest {
    private fun isolatedFixture(bytes: ByteArray): ByteArray {
        val suffix = ".recovery" + Uuid.random().toString().replace("-", "")
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { target ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { source ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    val original = source.readBytes()
                    val contents = if (entry.name == "manifest.json") {
                        val manifest = PluginContract.json.decodeFromString<PluginManifest>(
                            original.toString(Charsets.UTF_8)
                        )
                        PluginContract.json.encodeToString(manifest.copy(id = manifest.id + suffix)).toByteArray()
                    } else {
                        original
                    }
                    target.putNextEntry(ZipEntry(entry.name))
                    target.write(contents)
                    target.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    @Test fun foreignIssuerWithoutPauseNeverDispatchesAndStopAllowsFreshRun() = runBlocking {
        val koin = GlobalContext.get()
        val host = koin.get<RikkaPluginManager>().host()
        val service = koin.get<ChatService>()
        val repository = koin.get<ConversationRepository>()
        val settings = koin.get<SettingsStore>()
        val providers = koin.get<ProviderManager>()
        val originalSettings = settings.awaitLoaded()
        val originalProvider = providers.getProvider("openai")
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("plugins/review-report.rikkaplug").use { it.readBytes() }.let(::isolatedFixture)
        val candidate = host.prepare(bytes)
        val installed = host.activate(candidate, candidate.pkg.manifest.capabilities)
        // Hash, plugin ID, epoch and permissions match. Only the installation issuer differs.
        val oldLease = installed.lease.copy(issuerId = Uuid.random().toString())
        val model = Model(modelId = "restored-package")
        val assistant = Assistant(chatModelId = model.id, streamOutput = false)
        val provider = ProviderSetting.OpenAI(baseUrl = "https://fixture.invalid/v1", models = listOf(model))
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """{"id":"response","object":"chat.completion","model":"restored-package","choices":[{"index":0,"message":{"role":"assistant","content":"fresh-authorized-reply"},"finish_reason":"stop"}]}"""
                        .toResponseBody("application/json".toMediaType())
                ).build()
        }.build()
        val user = UIMessage.user("Restored input without any pause gate")
        val initial = Conversation.ofId(Uuid.random(), assistantId = assistant.id)
            .copy(title = "Authorization recovery", config = ConversationConfig(chatModelId = model.id))
            .updateCurrentMessages(listOf(user))
            .withPackageSelection(SessionPackageSelection(packages = mapOf(oldLease.pluginId to oldLease.packageHash)))
        val ref = RunRef(initial.id.toString(), user.id.toString(), "restored-no-gate")
        val checkpoint = RunCheckpoint(
            RunState(ref, 5, 10),
            "BeforeModel",
            0,
            inputIds = listOf(user.id.toString()),
            pluginLeases = listOf(oldLease)
        )
        try {
            settings.update {
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
            repository.insertConversation(
                initial.withExecutionLedger(ExecutionLedger(runs = mapOf(ref.runId to checkpoint)))
            )
            service.addConversationReference(initial.id)
            assertTrue(
                repository.getConversationById(
                    initial.id
                )!!.executionLedger().runs.getValue(ref.runId).state.gates.isEmpty()
            )
            assertTrue(host.authorized(installed.lease, installed.manifest.capabilities))
            assertFalse(host.authorized(oldLease, installed.manifest.capabilities))
            service.resumePluginRun(initial.id)
            withTimeout(20_000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            val waiting = repository.getConversationById(initial.id)!!.executionLedger().runs.getValue(ref.runId)
            assertEquals(service.errors.value.toString(), 0, requests.get())
            assertEquals(RunStatus.ACTIVE, waiting.state.status)
            assertEquals(0, waiting.state.attemptsUsed)
            assertEquals(listOf(oldLease), waiting.pluginLeases)
            assertTrue("host.package-authorization" in waiting.state.gates)
            assertTrue(runCatching { service.adoptCurrentPlugins(initial.id) }.isFailure)
            service.stopGeneration(initial.id)
            service.adoptCurrentPlugins(initial.id)
            service.regenerateAtMessage(initial.id, user.id)
            withTimeout(20_000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            val completed = repository.getConversationById(initial.id)!!
            assertEquals(service.errors.value.toString(), 1, requests.get())
            assertEquals(RunStatus.CANCELLED, completed.executionLedger().runs.getValue(ref.runId).state.status)
            val fresh = completed.executionLedger().runs.values.single { it.state.ref.runId != ref.runId }
            assertEquals(RunStatus.COMPLETED, fresh.state.status)
            assertTrue(installed.lease in fresh.pluginLeases)
            assertTrue(completed.currentMessages.last().parts.toString().contains("fresh-authorized-reply"))
        } finally {
            service.stopGeneration(initial.id)
            service.deleteConversation(initial.id)
            service.removeConversationReference(initial.id)
            host.disable(installed.manifest.id)
            providers.registerProvider("openai", originalProvider)
            settings.update { originalSettings }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
