package me.rerere.rikkahub.plugins

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.sync.DatabaseBackup
import me.rerere.rikkahub.plugins.storage.PluginConversationEntity
import me.rerere.rikkahub.plugins.storage.readPluginDraft
import me.rerere.rikkahub.plugins.storage.readPluginState
import me.rerere.rikkahub.plugins.storage.writePluginDraft
import me.rerere.rikkahub.plugins.storage.writePluginState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class PluginDatabaseContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val state = JsonObject(mapOf("unknown.future.version" to JsonPrimitive("preserve me")))

    @Test
    fun messageReplacementAndExtensionStateCommitOrRollbackTogether() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val id = Uuid.random()
            val conversation = Conversation.ofId(id).copy(pluginState = state)
            db.withTransaction {
                db.conversationDao().insert(entity(id.toString()))
                db.writePluginState(conversation)
            }
            val sql = db.openHelper.writableDatabase
            sql.execSQL(
                "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES ('node', ?, 0, '[]', 0)",
                arrayOf(id.toString())
            )
            assertTrue(runCatching {
                db.withTransaction {
                    db.messageNodeDao().deleteByConversation(id.toString())
                    db.writePluginState(conversation.copy(pluginState = JsonObject(emptyMap())))
                    error("injected before commit")
                }
            }.isFailure)
            assertEquals(state, db.readPluginState(id.toString()))
            assertEquals(1, sql.query("SELECT count(*) FROM message_node").use {
                it.moveToFirst()
                it.getInt(0)
            })
            db.withTransaction {
                db.messageNodeDao().deleteByConversation(id.toString())
                db.writePluginState(conversation)
            }
            assertEquals(state, db.readPluginState(id.toString()))
            db.withTransaction {
                db.pluginConversationDao().delete(id.toString())
                db.conversationDao().delete(entity(id.toString()))
            }
            assertNull(db.pluginConversationDao().get(id.toString()))
        } finally {
            db.close()
        }
    }

    @Test
    fun draftPromotionIsAtomicAndBackupIncludesOpaqueExtensionPayloads() = runBlocking {
        val name = "plugin-contract-${Uuid.random()}.db"
        val snapshot = File(context.cacheDir, "$name.snapshot")
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val id = Uuid.random()
            val draft = Conversation.ofId(id).copy(pluginState = state)
            db.writePluginDraft(draft)
            assertFalse(db.conversationDao().existsById(id.toString()))
            assertTrue(db.readPluginDraft(id.toString())!!.pluginDraft)
            assertTrue(runCatching {
                db.withTransaction {
                    db.conversationDao().insert(entity(id.toString()))
                    db.writePluginState(draft)
                    error("promotion interrupted")
                }
            }.isFailure)
            assertTrue(db.readPluginDraft(id.toString())!!.pluginDraft)
            db.withTransaction {
                db.conversationDao().insert(entity(id.toString()))
                db.writePluginState(draft)
            }
            assertNull(db.readPluginDraft(id.toString()))
            DatabaseBackup.createSnapshot(db.openHelper.writableDatabase, snapshot)
            val copy = Room.databaseBuilder(context, AppDatabase::class.java, snapshot.absolutePath).build()
            try {
                assertEquals(state, copy.readPluginState(id.toString()))
                assertTrue(copy.conversationDao().existsById(id.toString()))
            } finally {
                copy.close()
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
            snapshot.delete()
        }
    }

    @Test
    fun readingUnknownPayloadDoesNotInterpretOrDiscardIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.pluginConversationDao().put(PluginConversationEntity("unknown", "{\"version\":999,\"opaque\":[1,2,3]}"))
            assertEquals("999", db.readPluginState("unknown")["version"].toString())
            assertEquals("{\"version\":999,\"opaque\":[1,2,3]}", db.pluginConversationDao().get("unknown")!!.payload)
        } finally {
            db.close()
        }
    }

    private fun entity(id: String) = ConversationEntity(
        id,
        Uuid.random().toString(),
        "test",
        "[]",
        0,
        0,
        "[]",
        false
    )
}
