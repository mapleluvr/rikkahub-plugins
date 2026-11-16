package me.rerere.rikkahub.plugins

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.ConversationStore
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Reopen the actual Room database; failures occur inside the real conversation transaction. */
class RoomExecutionRecoveryTest {
    @Test
    fun failedAdoptionRollsBackMessagesAndNeverLeaksIntoLaterSave() = runBlocking {
        withDatabase { db, repo ->
            val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
            repo.insertConversation(source)
            val session = open(source.id, repo)
            val lease = PackageLease("test", "v1", 1)
            val call =
                OperationCall("report", "test/report", BranchRef(source.id.toString(), "branch"), JsonNull, lease)
            val operations = SessionOperationStore(session)
            operations.prepare(call, source.pluginSources())
            operations.start(call.id, "attempt")
            val seen = mutableListOf<Conversation>()
            val observer = launch(Dispatchers.Unconfined) { session.state.collect { seen.add(it) } }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("""
                CREATE TRIGGER fail_settlement BEFORE INSERT ON plugin_conversation
                WHEN NEW.payload LIKE '%SETTLED%'
                BEGIN SELECT RAISE(ABORT, 'injected settlement failure'); END
            """.trimIndent())
            try {
                assertTrue(runCatching {
                    operations.settle(call, "attempt", OperationResult()) {
                        it.updateCurrentMessages(it.currentMessages + UIMessage.user("uncommitted summary"))
                    }
                }.isFailure)
                assertTrue(seen.none { it.currentMessages.size != 1 })
                assertEquals(source.currentMessages, repo.getConversationById(source.id)!!.currentMessages)
                assertEquals(
                    AttemptStatus.RUNNING,
                    session.state.value.executionLedger().operations.getValue(call.id).status
                )
            } finally {
                sql.execSQL("DROP TRIGGER fail_settlement")
                observer.cancelAndJoin()
            }
            session.update { it.copy(title = "ordinary edit") }
            session.flush()
            assertEquals(source.currentMessages, repo.getConversationById(source.id)!!.currentMessages)
            // A newly loaded session must persist uncertainty before allowing dispatch.
            val restarted = open(source.id, repo)
            assertEquals(
                AttemptStatus.UNKNOWN,
                restarted.state.value.executionLedger().operations.getValue(call.id).status
            )
            assertEquals(
                AttemptStatus.UNKNOWN,
                repo.getConversationById(source.id)!!.executionLedger().operations.getValue(call.id).status
            )
        }
    }

    @Test
    fun searchIndexFailureCannotCommitMessagesBeforeReceiptPublication() = runBlocking {
        withDatabase { db, repo ->
            val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
            repo.insertConversation(source)
            val session = open(source.id, repo)
            val call = OperationCall(
                "index",
                "report",
                BranchRef(source.id.toString(), "branch"),
                JsonNull,
                PackageLease("test", "v1", 1)
            )
            val operations = SessionOperationStore(session)
            operations.prepare(call, source.pluginSources())
            operations.start(call.id, "attempt")
            val sql = db.openHelper.writableDatabase
            sql.execSQL("""
                CREATE TRIGGER fail_index BEFORE INSERT ON message_fts_rowid
                BEGIN SELECT RAISE(ABORT, 'injected index failure'); END
            """.trimIndent())
            try {
                assertTrue(runCatching {
                    operations.settle(call, "attempt", OperationResult()) {
                        it.updateCurrentMessages(it.currentMessages + UIMessage.user("candidate"))
                    }
                }.isFailure)
                val persisted = repo.getConversationById(source.id)!!
                assertEquals(source.currentMessages, persisted.currentMessages)
                assertEquals(AttemptStatus.RUNNING, persisted.executionLedger().operations.getValue(call.id).status)
                assertEquals(source.currentMessages, session.state.value.currentMessages)
            } finally {
                sql.execSQL("DROP TRIGGER fail_index")
            }
        }
    }

    @Test
    fun controlOnlyPauseDoesNotRewriteMessageNodes() = runBlocking {
        withDatabase { db, repo ->
            val source = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("source")))
            repo.insertConversation(source)
            val session = open(source.id, repo)
            val run = SessionAgentRun.open(session, source.currentMessages) { _, _ -> null }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("""
                CREATE TRIGGER forbid_message_rewrite BEFORE DELETE ON message_node
                BEGIN SELECT RAISE(ABORT, 'control write touched messages'); END
            """.trimIndent())
            try {
                SessionRunStore(session).addGate(run.ref, Gate("user", GateKind.USER_PAUSE))
                assertTrue(
                    "user" in
                        repo.getConversationById(source.id)!!.executionLedger().runs.getValue(run.ref.runId).state.gates
                )
                assertEquals(source.currentMessages, repo.getConversationById(source.id)!!.currentMessages)
            } finally {
                sql.execSQL("DROP TRIGGER forbid_message_rewrite")
            }
        }
    }

    @Test
    fun diskReopenPromotesDraftWithoutLosingRunOrPause() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "plugin-draft-reopen-").toFile()
        val context = isolatedContext(app, directory)
        var db = AppDatabaseFactory.create(context)
        try {
            var repo = repository(db)
            val initial = Conversation.ofId(Uuid.random())
            val session = open(initial.id, repo)
            val run = SessionAgentRun.open(session, emptyList()) { _, _ -> null }
            SessionRunStore(session).addGate(run.ref, Gate("user", GateKind.USER_PAUSE))
            assertFalse(db.conversationDao().existsById(initial.id.toString()))
            db.close()
            db = AppDatabaseFactory.create(context)
            repo = repository(db)
            val reopened = open(initial.id, repo)
            assertFalse(reopened.canStartNextTurn)
            val input = UIMessage.user("first input")
            reopened.update { it.updateCurrentMessages(listOf(input)) }
            reopened.flush()
            val saved = repo.getConversationById(initial.id)!!
            val checkpoint = saved.executionLedger().runs.getValue(run.ref.runId)
            assertFalse(saved.pluginDraft)
            assertEquals(run.ref, checkpoint.state.ref)
            assertEquals(listOf(input.id.toString()), checkpoint.inputIds)
            assertTrue("user" in checkpoint.state.gates)
            assertFalse(reopened.canStartNextTurn)
            assertNull(db.pluginConversationDao().get(initial.id.toString())!!.draft)
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun CoroutineScope.open(id: Uuid, repo: ConversationRepository): ConversationSession {
        val initial = Conversation.ofId(id)
        val store = object : ConversationStore {
            override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
            override suspend fun write(previous: Conversation?, current: Conversation) {
                if (previous == null) repo.insertConversation(current) else repo.updateConversation(current)
            }
            override suspend fun writeControl(
                previous: Conversation,
                current: Conversation
            ) = repo.writePluginControl(current)
            override suspend fun writeDraft(current: Conversation) = repo.writePluginDraft(current)
        }
        return ConversationSession(id, initial, this, store, {
        }).also { it.initialize({ repo.getConversationById(id) }, { initial }) }
    }

    private suspend fun withDatabase(block: suspend (AppDatabase, ConversationRepository) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "plugin-room-recovery-").toFile()
        val db = AppDatabaseFactory.create(isolatedContext(app, directory))
        try {
            block(db, repository(db))
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    private fun repository(db: AppDatabase) = ConversationRepository(
        db.conversationDao(),
        db.messageNodeDao(),
        db.favoriteDao(),
        db,
        GlobalContext.get().get(),
        MessageFtsManager(db),
    )

    private fun isolatedContext(app: Context, directory: File): Context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getDatabasePath(
            name: String
        ): File = File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
    }
}
