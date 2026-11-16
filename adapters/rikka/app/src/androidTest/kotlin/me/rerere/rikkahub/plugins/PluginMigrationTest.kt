package me.rerere.rikkahub.plugins

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class PluginMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val name = "plugin-migration-34-35"

    @get:Rule
    val helper =
        MigrationTestHelper(instrumentation, AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test
    fun upstream34UpgradesTo35WithoutChangingConversationData() = runBlocking {
        helper.createDatabase(name, 34).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) VALUES ('old', 'assistant', 'history', '[]', 1, 2)"
            )
            close()
        }
        val db = Room.databaseBuilder(instrumentation.targetContext, AppDatabase::class.java, name).build()
        try {
            assertEquals("history", db.conversationDao().getConversationById("old")!!.title)
            assertNull(db.pluginConversationDao().get("old"))
            assertEquals(35, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
            instrumentation.targetContext.deleteDatabase(name)
        }
    }
}
