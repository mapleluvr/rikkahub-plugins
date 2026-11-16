package me.rerere.rikkahub.plugins.storage

import androidx.room.withTransaction
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant

/** Called inside the same Room transaction as the corresponding message changes. */
suspend fun AppDatabase.writePluginState(conversation: Conversation) {
    check(inTransaction()) { "Plugin state must commit with its conversation" }
    val id = conversation.id.toString()
    if (conversation.pluginState.isEmpty()) {
        pluginConversationDao().delete(id)
    } else {
        pluginConversationDao().put(PluginConversationEntity(id, JsonInstant.encodeToString(conversation.pluginState)))
    }
}

suspend fun AppDatabase.readPluginState(id: String): JsonObject =
    pluginConversationDao().get(id)?.let { JsonInstant.decodeFromString<JsonObject>(it.payload) }
        ?: JsonObject(emptyMap())

suspend fun AppDatabase.writePluginDraft(conversation: Conversation) = withTransaction {
    val id = conversation.id.toString()
    check(!conversationDao().existsById(id)) { "A promoted conversation cannot be overwritten by a draft" }
    require(conversation.messageNodes.isEmpty() && conversation.title.isBlank() && conversation.config == null)
    if (conversation.pluginState.isEmpty()) {
        pluginConversationDao().delete(id)
    } else {
        pluginConversationDao().put(PluginConversationEntity(
            id,
            JsonInstant.encodeToString(conversation.pluginState),
            JsonInstant.encodeToString(conversation)
        ))
    }
}

suspend fun AppDatabase.readPluginDraft(id: String): Conversation? =
    pluginConversationDao().get(id)?.draft?.let {
        JsonInstant.decodeFromString<Conversation>(it).copy(pluginDraft = true)
    }
