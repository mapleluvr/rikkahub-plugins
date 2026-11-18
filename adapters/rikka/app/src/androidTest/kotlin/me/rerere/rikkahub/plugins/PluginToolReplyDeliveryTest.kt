package me.rerere.rikkahub.plugins

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginToolDeclaration
import dev.rikkahub.plugins.protocol.RunStatus
import dev.rikkahub.plugins.runtime.PluginHost
import dev.rikkahub.plugins.testing.IsolatedReplyFaultContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/** Production ChatService + isolated QuickJS + owned Room reopen + actual loopback HTTP. */
class PluginToolReplyDeliveryTest {
    @Test
    fun shortRealPipeKeepsOriginalRoomRunWaitingAcrossReopenAndStopAllowsNewHttp() = runBlocking {
        verify(shortReply = true)
    }

    @Test
    fun completeRealPipePersistsToolAndAllowsExactlyOneContinuationHttp() = runBlocking {
        verify(shortReply = false)
    }

    private suspend fun verify(shortReply: Boolean) {
        val koin = GlobalContext.get()
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val storage = File(application.cacheDir, "reply-delivery-${Uuid.random()}").apply { mkdirs() }
        val databaseName = "reply-delivery-${Uuid.random()}.db"
        val context =
            IsolatedReplyFaultContext(
                application,
                holdReply = shortReply,
                delayDisconnect = shortReply,
                storage = storage
            )
        val settings = koin.get<SettingsStore>()
        val providers = koin.get<ProviderManager>()
        val originalSettings = settings.awaitLoaded()
        val originalProvider = providers.getProvider("openai")
        val manifest = manifest()
        val server = LocalOpenAi(pluginToolName(manifest.id, "big"))
        val client = OkHttpClient.Builder().build()
        val model = Model(modelId = "delivery-local", abilities = listOf(ModelAbility.TOOL))
        val assistant = Assistant(chatModelId = model.id, streamOutput = false)
        val provider = ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:${server.port}/v1", models = listOf(model))
        var scope = AppScope()
        var database = AppDatabaseFactory.create(context, databaseName)
        var repository = repository(koin, database)
        var manager = RikkaPluginManager(context, scope)
        var host: PluginHost? = null
        var service = service(koin, application, scope, repository, manager)
        val user = UIMessage.user("Require a complete result before tool settlement")
        val initial = Conversation.ofId(Uuid.random(), assistantId = assistant.id)
            .copy(title = "Reply delivery", config = ConversationConfig(chatModelId = model.id))
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
            host = manager.host()
            val candidate = host.prepare(archive(manifest))
            val installed = host.activate(candidate, manifest.capabilities)
            repository.insertConversation(initial)
            service.addConversationReference(initial.id)
            service.regenerateAtMessage(initial.id, user.id)
            if (shortReply) {
                val reply = withTimeout(10_000) { context.held.await() }
                assertNull(reply.message.data.getString("error"))
                context.crashWriter(reply)
                withContext(Dispatchers.Main.immediate) { reply.forwardOriginalPipe() }
            }
            withTimeout(25_000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            val saved = repository.getConversationById(initial.id)!!
            val run = saved.executionLedger().runs.values.single()
            val tool = saved.currentMessages.flatMap { it.getTools() }.single()
            assertEquals(1, context.callsSent.get())
            assertEquals(listOf(installed.lease), run.pluginLeases)
            if (shortReply) {
                assertEquals("run=$run; tool=$tool; HTTP=${server.requests}", 1, server.requests.size)
                assertEquals(RunStatus.ACTIVE, run.state.status)
                assertEquals("BeforeTool", run.boundary)
                assertEquals("delivery-call", run.toolCallId)
                assertTrue("host.recovery" in run.state.gates)
                assertFalse(tool.isExecuted)
                assertTrue(tool.startedAt != null)
                assertTrue(tool.output.isEmpty())
                assertNull(tool.finishedAt)
                assertTrue(service.errors.value.none { it.conversationId == initial.id })
            } else {
                assertEquals(2, server.requests.size)
                assertEquals(RunStatus.COMPLETED, run.state.status)
                assertTrue(tool.isExecuted)
                assertTrue(tool.finishedAt != null)
                assertEquals(200_000, tool.output.filterIsInstance<UIMessagePart.Text>().single().text.length)
                assertEquals("HTTP finished", saved.currentMessages.last().parts
                    .filterIsInstance<UIMessagePart.Text>().single().text)
            }

            // Drain both actual owners, close the owned Room DB, then open the same file via the production factory.
            service.removeConversationReference(initial.id)
            host.close()
            context.disposeHeldReply()
            context.releaseDisconnects()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            database.close()
            scope = AppScope()
            database = AppDatabaseFactory.create(context, databaseName)
            repository = repository(koin, database)
            manager = RikkaPluginManager(context, scope)
            host = manager.host()
            service = service(koin, application, scope, repository, manager)
            val reopened = repository.getConversationById(initial.id)!!
            val originalRun = reopened.executionLedger().runs.getValue(run.state.ref.runId)
            assertEquals(run.state.ref, originalRun.state.ref)
            assertEquals(run.pluginLeases, originalRun.pluginLeases)
            assertEquals(run.state.stepsUsed, originalRun.state.stepsUsed)
            assertEquals(run.state.attemptsUsed, originalRun.state.attemptsUsed)
            assertEquals(run.state.status, originalRun.state.status)
            if (shortReply) {
                assertTrue(host.authorizedPackages(originalRun.pluginLeases))
                service.addConversationReference(initial.id)
                service.resumePluginRun(initial.id)
                withTimeout(20_000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
                val restored = repository.getConversationById(initial.id)!!
                assertEquals(1, server.requests.size)
                assertEquals(1, context.callsSent.get())
                val waiting = restored.executionLedger().runs.getValue(run.state.ref.runId)
                assertEquals(RunStatus.ACTIVE, waiting.state.status)
                assertTrue("host.recovery" in waiting.state.gates)
                assertEquals(run.state.stepsUsed, waiting.state.stepsUsed)
                assertEquals(run.state.attemptsUsed, waiting.state.attemptsUsed)
                val unfinished = restored.currentMessages.flatMap { it.getTools() }.single()
                assertFalse(unfinished.isExecuted)
                assertTrue(unfinished.output.isEmpty())
                assertNull(unfinished.finishedAt)

                service.stopGeneration(initial.id)
                assertEquals(RunStatus.CANCELLED, repository.getConversationById(initial.id)!!.executionLedger()
                    .runs.getValue(run.state.ref.runId).state.status)
                service.sendMessage(initial.id, listOf(UIMessagePart.Text("fresh input")))
                withTimeout(20_000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
                val completed = repository.getConversationById(initial.id)!!
                assertEquals(2, server.requests.size)
                assertEquals("HTTP finished", completed.currentMessages.last().parts
                    .filterIsInstance<UIMessagePart.Text>().single().text)
                assertEquals(
                    RunStatus.CANCELLED,
                    completed.executionLedger().runs.getValue(run.state.ref.runId).state.status
                )
                assertTrue(completed.executionLedger().runs.values.any {
                    it.state.ref.runId != run.state.ref.runId && it.state.status == RunStatus.COMPLETED
                })
                println(
                    "PIPE SERVICE short=true HTTP=1-before-Stop/2-after-new-input originalRun=${run.state.ref.runId} RoomClosedReopened=true CALL=1"
                )
            } else {
                assertTrue(reopened.currentMessages.flatMap { it.getTools() }.single().isExecuted)
                assertEquals(2, server.requests.size)
                println(
                    "PIPE SERVICE complete=true HTTP=2 originalRun=${run.state.ref.runId} RoomClosedReopened=true CALL=1"
                )
            }
            server.assertHealthy()
        } finally {
            try {
                service.stopGeneration(initial.id)
                service.removeConversationReference(initial.id)
                host?.close()
                context.disposeHeldReply()
                context.releaseDisconnects()
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                database.close()
                providers.registerProvider("openai", originalProvider)
                settings.update { originalSettings }
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.close()
                application.deleteDatabase(databaseName)
                storage.deleteRecursively()
            }
        }
    }

    private fun repository(koin: Koin, database: AppDatabase) = ConversationRepository(
        database.conversationDao(),
        database.messageNodeDao(),
        database.favoriteDao(),
        database,
        koin.get(),
        MessageFtsManager(database),
    )

    private fun service(
        koin: Koin,
        application: Application,
        scope: AppScope,
        repository: ConversationRepository,
        manager: RikkaPluginManager,
    ) = ChatService(
        context = application, appScope = scope, appEventBus = AppEventBus(),
        settingsStore = koin.get(), conversationRepo = repository, requestFactory = koin.get(),
        memoryConsolidationScheduler = koin.get(), translationHandler = koin.get(), metaGenerator = koin.get(),
        mcpManager = koin.get(), filesManager = koin.get(), folderRepository = koin.get(),
        backgroundShellManager = koin.get(), pluginManager = manager,
    )

    private fun manifest() = PluginManifest(
        id = "dev.fixture.delivery" + Uuid.random().toString().replace("-", ""),
        name = "Reply delivery",
        version = "1.0.0",
        capabilities = setOf("tools.register"),
        tools = listOf(PluginToolDeclaration(
            "big",
            "Big",
            "Return a real large tool result",
            JsonObject(mapOf("type" to JsonPrimitive("object"))),
            false
        )),
    )

    private fun archive(manifest: PluginManifest): ByteArray {
        val script = "globalThis.rikkaPlugin = api => api.registerTool('big', () => ({content:'x'.repeat(200000)}));"
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

    private class LocalOpenAi(private val toolName: String) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        val requests = CopyOnWriteArrayList<String>()

        @Volatile private var failure: Throwable? = null
        private val thread = Thread({
            try {
                while (!listener.isClosed) {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = BufferedInputStream(socket.getInputStream())
                        val first = line(input)
                        check(first.startsWith("POST /v1/chat/completions ")) { "Unexpected real HTTP target: $first" }
                        var length = -1
                        while (true) {
                            val header = line(input)
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                                length =
                                    header.substringAfter(':').trim().toInt()
                            }
                        }
                        check(length in 0..1_048_576)
                        val body = ByteArray(length)
                        var offset = 0
                        while (offset < body.size) {
                            val count = input.read(body, offset, body.size - offset)
                            check(count > 0) { "Short HTTP request body" }
                            offset += count
                        }
                        requests += body.toString(Charsets.UTF_8)
                        val message = if (requests.size == 1) {
                            """
                                {"role":"assistant","content":null,
                                "tool_calls":[{"id":"delivery-call","type":"function",
                                "function":{"name":"$toolName","arguments":"{}"}}]}
                            """.trimIndent()
                        } else {
                            """{"role":"assistant","content":"HTTP finished"}"""
                        }
                        val response = """
                            {"id":"delivery","object":"chat.completion","model":"delivery-local",
                            "choices":[{"index":0,"message":$message,"finish_reason":"stop"}]}
                        """.trimIndent().toByteArray()
                        val output = socket.getOutputStream()
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                        output.write(header.toByteArray())
                        output.write(response)
                        output.flush()
                    }
                }
            } catch (error: Throwable) {
                if (!listener.isClosed) failure = error
            }
        }, "delivery-loopback-http").apply {
            isDaemon = true
            start()
        }

        fun assertHealthy() {
            assertNull("Real HTTP server failed: $failure", failure)
        }

        override fun close() {
            listener.close()
            thread.join(5_000)
            check(!thread.isAlive)
        }

        private fun line(input: BufferedInputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                check(byte >= 0) { "Unexpected HTTP EOF" }
                if (byte == 10) return bytes.toString("UTF-8").trimEnd('\r')
                check(bytes.size() < 16_384) { "Oversized HTTP header" }
                bytes.write(byte)
            }
        }
    }
}
