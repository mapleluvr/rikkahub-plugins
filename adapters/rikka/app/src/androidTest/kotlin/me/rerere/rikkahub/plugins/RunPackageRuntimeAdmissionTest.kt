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
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.*
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.ConversationStore
import org.junit.Assert.*
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

/** Original packaged definition, Room and isolated QuickJS: revoke the owning run before CALL dispatch. */
class RunPackageRuntimeAdmissionTest {
    @Test fun owningRunRevocationBeforeBinderCallPreventsContextDeliveryAndKeepsTheLogicalRun(): Unit = runBlocking {
        exercise(includeOwningA = true)
    }

    @Test fun revocationDuringRuntimeOpenAlsoDrainsTheWholeOwningScope(): Unit = runBlocking {
        exercise(includeOwningA = true, holdDuringOpen = true)
    }

    @Test fun independentHandlerRemainsCallableAfterAnotherPackageIsRevoked(): Unit = runBlocking {
        exercise(includeOwningA = false)
    }

    private suspend fun exercise(includeOwningA: Boolean, holdDuringOpen: Boolean = false): Unit = coroutineScope {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("me.rerere.rikkahub.debug", app.packageName)
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "p45-review-late-").toFile()
        val local = BarrierContext(app, directory)
        val database = AppDatabaseFactory.create(local)
        val repository = ConversationRepository(
            database.conversationDao(),
            database.messageNodeDao(),
            database.favoriteDao(),
            database,
            GlobalContext.get().get(),
            MessageFtsManager(database)
        )
        val host = PluginHost.open(local, File(directory, "plugins"))
        var work: Deferred<OperationDispatch>? = null
        var disabling: Deferred<Unit>? = null
        try {
            val aCandidate = host.prepare(archive("dev.example.owning"))
            val a = host.activate(aCandidate, aCandidate.pkg.manifest.capabilities)
            val bCandidate = host.prepare(archive("dev.example.handler"))
            val b = host.activate(bCandidate, bCandidate.pkg.manifest.capabilities)
            val initial = Conversation.ofId(Uuid.random()).copy(title = "Late runtime admission probe")
                .updateCurrentMessages(listOf(UIMessage.user("private-source-after-revocation")))
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
            val owningLeases = if (includeOwningA) listOf(a.lease, b.lease) else listOf(b.lease)
            val run = SessionAgentRun.open(
                session,
                initial.currentMessages,
                pluginLeases = owningLeases,
                packageAuthorization = host::authorizedPackages
            ) { _, _ ->
                null
            }
            assertTrue(session.state.value.executionLedger().runs.getValue(run.ref.runId).state.gates.isEmpty())
            val original = host.operationDefinitions().single { it.lease == b.lease }
            var returnedByOriginal: OperationResult? = null
            // Only observe its return; do not replace beforeEffect, instance preparation, context read or invocation.
            val observable = original.copy(prepare = { source, input ->
                val prepared = original.prepare(source, input)
                prepared.copy(execute = { execution -> prepared.execute(execution).also { returnedByOriginal = it } })
            })
            val dispatcher = SessionOperationDispatcher(
                listOf(observable),
                packageAuthorization = host::authorizedPackages,
                authorized = host::authorized
            )
            val call = session.state.value.operationCall(observable, JsonNull, run = run.ref)
            local.arm(if (holdDuringOpen) 1 else 2)
            work = async(Dispatchers.IO) { dispatcher.dispatch(session, call) }
            // The first cache access prepares OPEN; the second prepares CALL. Both must allow revocation to progress.
            withTimeout(20_000) { local.beforeCall.await() }
            disabling = async(Dispatchers.IO) { host.disable(a.manifest.id) }
            withTimeout(20_000) { host.state.first { !it.packages.getValue(a.lease.packageHash).enabled } }
            assertFalse(host.authorized(a.lease, a.manifest.capabilities))
            assertTrue(host.authorized(b.lease, b.manifest.capabilities))
            assertFalse(host.authorizedPackages(listOf(a.lease, b.lease)))
            local.release.countDown()
            withTimeout(20_000) { disabling.await() }
            val outcome = withTimeout(20_000) { work.await() }
            val saved = repository.getConversationById(initial.id)!!.executionLedger()
            val record = saved.operations.getValue(call.id)
            assertEquals(RunStatus.ACTIVE, saved.runs.getValue(run.ref.runId).state.status)
            assertEquals(owningLeases, saved.runs.getValue(run.ref.runId).pluginLeases)
            if (includeOwningA) {
                assertNull(
                    "Revoked owning-run authority must stop the original JS before receiving context",
                    returnedByOriginal
                )
                assertTrue(outcome.toString(), outcome is OperationDispatch.Waiting)
                assertEquals(AttemptStatus.PREPARED, record.status)
                assertEquals(0, record.effectAdmissions)
                assertEquals("No CALL may cross Binder; rejecting only its eventual result is insufficient", 0, local.callsSent.get())
                assertTrue("host.package-authorization" in saved.runs.getValue(run.ref.runId).state.gates)
            } else {
                assertNotNull("Independent B must still receive and return its own context", returnedByOriginal)
                assertTrue(returnedByOriginal.toString().contains("private-source-after-revocation"))
                assertTrue(outcome.toString(), outcome is OperationDispatch.Settled)
                assertEquals(AttemptStatus.SETTLED, record.status)
                assertEquals(1, record.effectAdmissions)
                assertEquals("Positive control must execute one real Binder CALL", 1, local.callsSent.get())
                assertTrue(saved.runs.getValue(run.ref.runId).state.gates.isEmpty())
            }
            println(
                "RUN-AUTHORITY-CALL owningA=$includeOwningA duringOpen=$holdDuringOpen binderCalls=${local.callsSent.get()} originalJsReturnedPrivateContext=${returnedByOriginal != null} effectAdmissions=${record.effectAdmissions} status=${record.status} outcome=$outcome"
            )
        } finally {
            local.release.countDown()
            work?.cancelAndJoin()
            disabling?.cancelAndJoin()
            host.close()
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun archive(id: String): ByteArray {
        val manifest = PluginManifest(
            id,
            "Review",
            "1.0.0",
            capabilities = setOf("context.read", "artifacts.write"),
            operations = listOf(PluginOperationDeclaration("report", "Report"))
        )
        val script = "globalThis.rikkaPlugin = api => api.registerOperation('report', async (_, context) => ({artifacts: [{type: 'review', content: await context.readMessages()}]}));"
        val output = ByteArrayOutputStream()
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
        return output.toByteArray()
    }

    private class BarrierContext(base: Context, private val root: File) : ContextWrapper(base) {
        val beforeCall = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val cacheCalls = AtomicInteger()
        val callsSent = AtomicInteger()
        private val connections = ConcurrentHashMap<ServiceConnection, ServiceConnection>()

        @Volatile private var armed = false
        @Volatile private var heldCacheCall = 2
        fun arm(cacheCall: Int) {
            cacheCalls.set(0)
            callsSent.set(0)
            heldCacheCall = cacheCall
            armed = true
        }
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val observer = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    connection.onServiceConnected(name, object : IBinder by service {
                        override fun queryLocalInterface(descriptor: String): IInterface? = null
                        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            if (armed && code == IBinder.FIRST_CALL_TRANSACTION) {
                                val header = Parcel.obtain()
                                try {
                                    // Inspect only the IMessenger/Message header, never deserialize the payload FD.
                                    header.appendFrom(data, 0, data.dataSize())
                                    header.setDataPosition(0)
                                    header.enforceInterface("android.os.IMessenger")
                                    if (header.readInt() != 0 && header.readInt() == 2) callsSent.incrementAndGet()
                                } finally {
                                    header.recycle()
                                }
                            }
                            return service.transact(code, data, reply, flags)
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
        override fun unbindService(connection: ServiceConnection) {
            super.unbindService(checkNotNull(connections.remove(connection)))
        }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "db/$name").also { it.parentFile!!.mkdirs() }
        override fun getCacheDir(): File {
            if (armed && cacheCalls.incrementAndGet() == heldCacheCall) {
                check(Thread.currentThread() != Looper.getMainLooper().thread) {
                    "Barrier must never block the Android main thread"
                }
                beforeCall.complete(Unit)
                check(release.await(20, TimeUnit.SECONDS)) { "CALL barrier was not released" }
            }
            return File(root, "cache").apply { mkdirs() }
        }
    }
}
