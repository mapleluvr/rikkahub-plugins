package me.rerere.rikkahub.plugins.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** No message-node FK: upstream rewrites those rows on every conversation save. */
@Entity(tableName = "plugin_conversation")
data class PluginConversationEntity(
    @PrimaryKey val conversationId: String,
    val payload: String,
    /** An unpromoted conversation, without a frozen assistant configuration. */
    val draft: String? = null
)

@Dao
interface PluginConversationDao {
    @Query("SELECT * FROM plugin_conversation WHERE conversationId = :id")
    suspend fun get(id: String): PluginConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: PluginConversationEntity)

    @Query("DELETE FROM plugin_conversation WHERE conversationId = :id")
    suspend fun delete(id: String)

    @Query("SELECT draft FROM plugin_conversation WHERE draft IS NOT NULL")
    suspend fun drafts(): List<String>
}
