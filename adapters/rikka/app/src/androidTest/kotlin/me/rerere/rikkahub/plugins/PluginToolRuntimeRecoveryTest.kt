package me.rerere.rikkahub.plugins

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.protocol.PluginOperationDeclaration
import dev.rikkahub.plugins.protocol.PluginToolDeclaration
import dev.rikkahub.plugins.protocol.RunStatus
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.ConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/** Real original packaged tools, Binder/QuickJS and Room.
 * The Binder observer only counts and forwards unchanged transactions. JS hold never returns,
 * so a sent CALL has no confirmed tool result when revocation/Stop/host close happens.
 */
class PluginToolRuntimeRecoveryTest {
    @Test fun revocationBeforeBinderCallKeepsTheUnconfirmedToolAndOriginalCursor(): Unit = runBlocking {
        exercise(Cut.BEFORE_CALL)
    }

    @Test fun revocationAfterBinderCallKeepsTheUnknownEffectAcrossRoomRecovery(): Unit = runBlocking {
        exercise(Cut.AFTER_CALL)
    }

    @Test fun userStopAfterRealBinderCallKeepsUnfinishedOutputAndCancelsTheRun(): Unit = runBlocking {
        exercise(Cut.USER_STOP)
    }

    @Test fun concurrentRevocationAndStopNeverFabricateACompletedResult(): Unit = runBlocking {
        repeat(3) { exercise(Cut.STOP_AND_REVOKE) }
    }

    @Test fun hostReopenWithFullOriginalAuthorizationCannotSkipTheUnknownTool(): Unit = runBlocking {
        exercise(Cut.HOST_CLOSE)
    }

    @Test fun actualIsolatedDeathKeepsTheAuthorizedRunWaitingAcrossRoomRecovery(): Unit = runBlocking {
        exercise(Cut.RUNTIME_DEATH)
    }

    @Test fun internalRuntimeTimeoutIsNotUserStopAndCannotSkipTheUnknownTool(): Unit = runBlocking {
        exercise(Cut.RUNTIME_TIMEOUT)
    }

    private enum class Cut {
        BEFORE_CALL,
        AFTER_CALL,
        USER_STOP,
        STOP_AND_REVOKE,
        HOST_CLOSE,
        RUNTIME_DEATH,
        RUNTIME_TIMEOUT
    }

    private suspend fun exercise(cut: Cut): Unit = coroutineScope {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "p45-fourth-tools-").toFile()
        val local = CountingContext(app, directory)
        val database = AppDatabaseFactory.create(local)
        val repository = ConversationRepository(
            database.conversationDao(),
            database.messageNodeDao(),
            database.favoriteDao(),
            database,
            GlobalContext.get().get(),
            MessageFtsManager(database),
        )
        val host = PluginHost.open(local, File(directory, "plugins"))
        var work: Deferred<List<AgentEvent>>? = null
        var disabling: Deferred<Unit>? = null
        var reopenedHost: PluginHost? = null
        var monitor: Deferred<Unit>? = null
        val releaseMonitor = CompletableDeferred<Unit>()
        try {
            val aCandidate = host.prepare(archive("dev.example.reviewtool.a", toolPackage = false))
            val a = host.activate(aCandidate, aCandidate.pkg.manifest.capabilities)
            val bCandidate = host.prepare(archive("dev.example.reviewtool.b", toolPackage = true))
            val b = host.activate(bCandidate, bCandidate.pkg.manifest.capabilities)
            val crashTarget = if (cut == Cut.RUNTIME_DEATH) {
                val ready = CompletableDeferred<Triple<Int, Int, IBinder>>()
                monitor = async(Dispatchers.IO) {
                    host.withInstance(b.lease) { client ->
                        ready.complete(Triple(client.processId, client.processUid, checkNotNull(local.connectedBinder)))
                        releaseMonitor.await()
                    }
                }
                withTimeout(20_000) { ready.await() }
            } else {
                null
            }
            val initial = Conversation.ofId(Uuid.random()).copy(title = "Tool scope observation")
                .updateCurrentMessages(listOf(UIMessage.user("private-original-AB-source")))
            repository.insertConversation(initial)
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
            val session = ConversationSession(initial.id, initial, this, store, {}).also { it.initialize({ initial }) }
            val originalAB = listOf(a.lease, b.lease)
            val run = SessionAgentRun.open(
                session,
                initial.currentMessages,
                pluginLeases = originalAB,
                packageAuthorization = host::authorizedPackages
            ) { _, _ -> null }
            val tools = host.toolDefinitions(run.runtimeBinding()) {
                session.state.value.runInputMessages(run.ref.runId)
            }
            val provider = ToolProvider(pluginToolName(b.manifest.id, "hold"))
            val hooks = object : AgentHooks {
                override suspend fun prepareStep(
                    messages: List<UIMessage>
                ) = StepRequest(messages, TextGenerationParams(Model()))
            }
            val plan = run.resumePlan()
            val request = AgentRequest(provider, ProviderSetting.OpenAI(), plan.history, tools = tools, stream = false)
            local.arm(if (cut == Cut.BEFORE_CALL) 2 else 0)
            work = async(Dispatchers.IO) {
                runAgent(run.bind(request, plan), run.hooks(hooks)).toList().also { events ->
                    events.forEach { run.accept(it) }
                }
            }
            val sentBeforeCut = if (cut == Cut.BEFORE_CALL) 0 else 1
            if (cut == Cut.BEFORE_CALL) {
                withTimeout(20_000) { local.beforePreparation.await() }
            } else {
                withTimeout(20_000) { local.firstCallSent.await() }
            }
            assertEquals(sentBeforeCut, local.callsSent.get())
            val before = session.state.value.currentMessages.last().getTools().single()
            assertNotNull(before.startedAt)
            assertTrue(before.output.isEmpty())
            assertTrue(host.authorizedPackages(originalAB))
            val events = when (cut) {
                Cut.BEFORE_CALL -> {
                    disabling = async(Dispatchers.IO) { host.disable(a.manifest.id) }
                    withTimeout(20_000) { host.state.first { !it.packages.getValue(a.lease.packageHash).enabled } }
                    local.releasePreparation.countDown()
                    withTimeout(20_000) { disabling.await() }
                    withTimeout(20_000) { work.await() }
                }

                Cut.AFTER_CALL -> {
                    withTimeout(20_000) { host.disable(a.manifest.id) }
                    withTimeout(20_000) { work.await() }
                }

                Cut.USER_STOP -> {
                    work.cancel(CancellationException("Explicit user Stop probe"))
                    withTimeout(20_000) { work.join() }
                    session.cancelPluginRuns()
                    emptyList()
                }

                Cut.STOP_AND_REVOKE -> {
                    // No invented ordering: each ordering must preserve terminality and unconfirmed output.
                    disabling = async(Dispatchers.IO) { host.disable(a.manifest.id) }
                    work.cancel(CancellationException("Explicit Stop competing with revocation"))
                    withTimeout(20_000) { disabling.await() }
                    withTimeout(20_000) { work.join() }
                    session.cancelPluginRuns()
                    emptyList()
                }

                Cut.RUNTIME_DEATH, Cut.RUNTIME_TIMEOUT -> {
                    if (cut == Cut.RUNTIME_DEATH) {
                        val (pid, uid, binder) = checkNotNull(crashTarget)
                        assertEquals("Actual CALL must use the process observed by OPEN", binder, local.callBinder)
                        assertTrue("Crash only the observed isolated service UID", uid % 100000 >= 90000)
                        assertTrue(pid != android.os.Process.myPid())
                        val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                            "am crash $pid"
                        )
                        val result = ParcelFileDescriptor.AutoCloseInputStream(command).use { String(it.readBytes()) }
                        assertFalse(
                            "Isolated process was not found: $result",
                            result.contains("could not", ignoreCase = true)
                        )
                    }
                    withTimeout(25_000) { work.await() }
                }

                Cut.HOST_CLOSE -> {
                    withTimeout(20_000) { host.close() }
                    val stoppedEvents = withTimeout(20_000) { work.await() }
                    assertTrue(stoppedEvents.last() is AgentEvent.Yielded)
                    assertEquals(1, provider.calls.get())
                    val unavailable = repository.getConversationById(initial.id)!!
                    assertTrue(
                        "host.package-authorization" in
                            unavailable.executionLedger().runs.getValue(run.ref.runId).state.gates
                    )
                    // Closing transient engines does not retire the installation's original leases.
                    val newHost = PluginHost.open(local, File(directory, "plugins")).also { reopenedHost = it }
                    assertTrue(
                        "Full original A+B is reauthorized, not substituted by independent B",
                        newHost.authorizedPackages(originalAB)
                    )
                    stoppedEvents
                }
            }
            if (cut in setOf(Cut.HOST_CLOSE, Cut.RUNTIME_DEATH, Cut.RUNTIME_TIMEOUT)) {
                val recoveryHost = reopenedHost ?: host
                val unavailable = repository.getConversationById(initial.id)!!
                assertTrue("Original authorization remains valid", recoveryHost.authorizedPackages(originalAB))
                val coldSession = ConversationSession(initial.id, unavailable, this, store, {
                }).also { it.initialize({ unavailable }) }
                val coldRun = SessionAgentRun.open(
                    coldSession,
                    coldSession.state.value.currentMessages,
                    packageAuthorization = recoveryHost::authorizedPackages
                ) { _, _ -> null }
                assertEquals(run.ref, coldRun.ref)
                assertTrue(
                    "Unconfirmed effect must survive full original authorization",
                    "host.recovery" in
                        coldSession.state.value.executionLedger().runs.getValue(run.ref.runId).state.gates
                )
                val resumedPlan = coldRun.resumePlan()
                val resumedTools = recoveryHost.toolDefinitions(coldRun.runtimeBinding()) {
                    coldSession.state.value.runInputMessages(coldRun.ref.runId)
                }
                val resumedRequest = AgentRequest(
                    provider,
                    ProviderSetting.OpenAI(),
                    resumedPlan.history,
                    tools = resumedTools,
                    stream = false
                )
                withTimeout(20_000) {
                    runAgent(coldRun.bind(resumedRequest, resumedPlan), coldRun.hooks(hooks)).toList()
                        .forEach { coldRun.accept(it) }
                }
            }
            val persisted = repository.getConversationById(initial.id)!!
            val saved = persisted.executionLedger().runs.getValue(run.ref.runId)
            val part = persisted.currentMessages.last().getTools().single()
            assertEquals(originalAB, saved.pluginLeases)
            assertEquals(sentBeforeCut, local.callsSent.get())
            when (cut) {
                Cut.USER_STOP -> {
                    assertEquals(1, provider.calls.get())
                    assertEquals(RunStatus.CANCELLED, saved.state.status)
                    assertFalse(part.isExecuted)
                    assertTrue(part.output.isEmpty())
                    assertNull(part.finishedAt)
                    println("SAFE ANDROID tool-user-Stop CALL=1 provider=1 outputEmpty=true run=CANCELLED")
                }

                Cut.STOP_AND_REVOKE -> {
                    assertEquals(1, provider.calls.get())
                    assertEquals(RunStatus.CANCELLED, saved.state.status)
                    assertFalse(part.isExecuted)
                    assertTrue(part.output.isEmpty())
                    assertNull(part.finishedAt)
                    println(
                        "SAFE ANDROID revoke-Stop-race CALL=1 provider=1 outputEmpty=true unfinished=true run=CANCELLED"
                    )
                }

                Cut.HOST_CLOSE, Cut.RUNTIME_DEATH, Cut.RUNTIME_TIMEOUT -> {
                    assertTrue("Transport loss is not cancellation of the parent", currentCoroutineContext().isActive)
                    assertTrue(events.last() is AgentEvent.Yielded)
                    assertEquals(1, provider.calls.get())
                    assertEquals(RunStatus.ACTIVE, saved.state.status)
                    assertFalse(part.isExecuted)
                    assertTrue(part.output.isEmpty())
                    assertNull(part.finishedAt)
                    assertTrue("host.recovery" in saved.state.gates)
                    println(
                        "SAFE ANDROID host-reopen fullOriginalAB=true sameOriginalRun=true CALL=1 noJsResult=true provider=1 unfinished=true recovery=true run=ACTIVE"
                    )
                }

                else -> {
                    assertTrue(events.last() is AgentEvent.Yielded)
                    assertEquals(1, provider.calls.get())
                    assertFalse(host.authorized(a.lease, a.manifest.capabilities))
                    assertTrue(host.authorized(b.lease, b.manifest.capabilities))
                    assertEquals(RunStatus.ACTIVE, saved.state.status)
                    assertFalse("A revoked execution scope is not a tool result", part.isExecuted)
                    assertNotNull(part.startedAt)
                    assertNull(part.finishedAt)
                    assertTrue(part.output.isEmpty())
                    assertEquals("BeforeTool", saved.boundary)
                    assertEquals("original-call", saved.toolCallId)
                    assertTrue("host.package-authorization" in saved.state.gates)
                    val reopened = ConversationSession(initial.id, persisted, this, store, {
                    }).also { it.initialize({ persisted }) }
                    val resumed = SessionAgentRun.open(
                        reopened,
                        reopened.state.value.currentMessages,
                        packageAuthorization = host::authorizedPackages
                    ) { _, _ -> null }
                    val cold = reopened.state.value.executionLedger().runs.getValue(resumed.ref.runId)
                    assertEquals(run.ref, resumed.ref)
                    assertTrue(
                        "Unconfirmed tool effect requires reconciliation after Room recovery",
                        "host.recovery" in cold.state.gates
                    )
                    assertEquals("original-call", cold.toolCallId)
                    println(
                        "SAFE ANDROID revoked-tool cut=$cut CALL=$sentBeforeCut provider=1 noJsResult=true unfinished=true boundary=BeforeTool recovery=true run=ACTIVE"
                    )
                }
            }
            // Explicit independent B work is not a substitute for original run A+B authorization.
            val positiveHost = reopenedHost ?: host
            val independent = positiveHost.withInstance(b.lease) { client ->
                client.invoke("tool", "ping", JsonObject(emptyMap()))
            }
            assertEquals("independent-B", PluginContract.toolResult(independent).content)
            assertEquals(sentBeforeCut + 1, local.callsSent.get())
            println("SAFE ANDROID independent-B new-scope CALL=1 result=independent-B originalCut=$cut")
        } finally {
            releaseMonitor.complete(Unit)
            monitor?.cancelAndJoin()
            local.releasePreparation.countDown()
            work?.cancelAndJoin()
            disabling?.cancelAndJoin()
            reopenedHost?.close()
            host.close()
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun archive(id: String, toolPackage: Boolean): ByteArray {
        val parameters = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap())))
        val manifest = PluginManifest(
            id,
            "Review tool",
            "1.0.0",
            capabilities = if (toolPackage) setOf("context.read", "tools.register") else setOf("context.read"),
            operations = if (toolPackage) emptyList() else listOf(PluginOperationDeclaration("noop", "Noop")),
            tools = if (toolPackage) {
                listOf(
                    PluginToolDeclaration(
                        "hold",
                        "Hold",
                        "Hold an unconfirmed CALL",
                        parameters,
                        needsApproval = false
                    ),
                    PluginToolDeclaration(
                        "ping",
                        "Ping",
                        "Independent B positive control",
                        parameters,
                        needsApproval = false
                    ),
                )
            } else {
                emptyList()
            },
        )
        val script = if (toolPackage) {
            """
            globalThis.rikkaPlugin = api => {
              api.registerTool('hold', async (_, context) => {
                const privateMessages = await context.readMessages();
                while (true) { }
                return {content: String(privateMessages.length)};
              });
              api.registerTool('ping', () => ({content: 'independent-B'}));
            };
            """.trimIndent()
        } else {
            "globalThis.rikkaPlugin = api => api.registerOperation('noop', () => ({}));"
        }
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

    private class ToolProvider(val name: String) : Provider<ProviderSetting> {
        val calls = AtomicInteger()
        override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams
        ): Flow<StreamChunk> = error("Nonstream probe")
        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams
        ): TextGenerationResult {
            val n = calls.incrementAndGet()
            val parts = if (n == 1) {
                listOf(UIMessagePart.Tool("original-call", name, "{}"))
            } else {
                listOf(UIMessagePart.Text("answer"))
            }
            return TextGenerationResult("probe", "probe", UIMessage(role = MessageRole.ASSISTANT, parts = parts))
        }
    }

    private class CountingContext(base: Context, val root: File) : ContextWrapper(base) {
        @Volatile private var armed = false

        @Volatile private var heldCacheCall = 0
        private val cacheCalls = AtomicInteger()
        val beforePreparation = CompletableDeferred<Unit>()
        val releasePreparation = CountDownLatch(1)
        val callsSent = AtomicInteger()
        val firstCallSent = CompletableDeferred<Unit>()

        @Volatile var connectedBinder: IBinder? = null

        @Volatile var callBinder: IBinder? = null

        private val connections = ConcurrentHashMap<ServiceConnection, ServiceConnection>()
        fun arm(cacheCall: Int) {
            cacheCalls.set(0)
            callsSent.set(0)
            heldCacheCall = cacheCall
            armed = true
        }
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val observer = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    connectedBinder = service
                    connection.onServiceConnected(name, object : IBinder by service {
                        override fun queryLocalInterface(descriptor: String): IInterface? = null
                        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            var call = false
                            if (armed && code == IBinder.FIRST_CALL_TRANSACTION) {
                                val header = Parcel.obtain()
                                try {
                                    header.appendFrom(data, 0, data.dataSize())
                                    header.setDataPosition(0)
                                    header.enforceInterface("android.os.IMessenger")
                                    call = header.readInt() != 0 && header.readInt() == 2
                                } finally {
                                    header.recycle()
                                }
                            }
                            val result = service.transact(code, data, reply, flags)
                            if (call) {
                                callBinder = service
                                callsSent.incrementAndGet()
                                firstCallSent.complete(Unit)
                            }
                            return result
                        }
                    })
                }
                override fun onServiceDisconnected(name: ComponentName) = connection.onServiceDisconnected(name)
                override fun onBindingDied(name: ComponentName) = connection.onBindingDied(name)
                override fun onNullBinding(name: ComponentName) = connection.onNullBinding(name)
            }
            connections[connection] = observer
            return super.bindService(intent, observer, flags).also { if (!it) connections.remove(connection) }
        }
        override fun unbindService(
            connection: ServiceConnection
        ) = super.unbindService(checkNotNull(connections.remove(connection)))
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File {
            if (armed && cacheCalls.incrementAndGet() == heldCacheCall) {
                check(Thread.currentThread() != Looper.getMainLooper().thread)
                beforePreparation.complete(Unit)
                check(releasePreparation.await(20, TimeUnit.SECONDS)) { "Preparation barrier was not released" }
            }
            return File(root, "cache").apply { mkdirs() }
        }
        override fun getDatabasePath(name: String): File = File(root, "db/$name").also { it.parentFile!!.mkdirs() }
    }
}
