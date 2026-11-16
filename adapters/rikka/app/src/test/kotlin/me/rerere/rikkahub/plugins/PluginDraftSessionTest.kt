package me.rerere.rikkahub.plugins

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.ConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PluginDraftSessionTest {
    private val waiting = JsonObject(mapOf("version" to JsonPrimitive(1), "wait" to JsonPrimitive("approval")))

    @Test
    fun emptyWaitSurvivesReloadWithoutFreezingAssistantConfiguration() = runBlocking {
        val id = Uuid.random()
        val store = Store()
        val initial = Conversation.ofId(id)
        val session = ConversationSession(id, initial, this, store, {})
        session.initialize({ null }, { initial })
        session.update { it.copy(pluginState = waiting) }
        session.flush()
        assertEquals(0, store.preparations)
        assertNull(store.saved)
        assertEquals(waiting, store.draft!!.pluginState)
        assertFalse(session.existsInDatabase)

        val restarted = ConversationSession(id, initial, this, store, {})
        restarted.initialize({ store.draft!!.copy(pluginDraft = true) })
        assertFalse(restarted.existsInDatabase)
        assertEquals(waiting, restarted.state.value.pluginState)
        restarted.update { it.updateCurrentMessages(listOf(UIMessage.user("begin"))) }
        restarted.flush()
        assertEquals(1, store.preparations)
        assertTrue(restarted.existsInDatabase)
        assertNull(store.draft)
        assertFalse(store.saved!!.pluginDraft)
        assertEquals(waiting, store.saved!!.pluginState)
    }

    @Test
    fun failedDraftWriteIsRetriedAndClearingTheWaitDeletesItsRecord() = runBlocking {
        val id = Uuid.random()
        val store = Store()
        val initial = Conversation.ofId(id)
        val session = ConversationSession(id, initial, this, store, {})
        session.initialize({ null }, { initial })
        session.update { it.copy(pluginState = waiting) }
        store.fail = true
        assertTrue(runCatching { session.flush() }.isFailure)
        assertNull(store.draft)
        store.fail = false
        session.flush()
        assertEquals(waiting, store.draft!!.pluginState)
        session.update { it.copy(pluginState = JsonObject(emptyMap())) }
        session.flush()
        assertNull(store.draft)
        assertEquals(0, store.preparations)
    }

    private class Store : ConversationStore {
        var draft: Conversation? = null
        var saved: Conversation? = null
        var preparations = 0
        var fail = false
        override suspend fun prepareWrite(): (Conversation) -> Conversation {
            preparations++
            return { it }
        }
        override suspend fun write(previous: Conversation?, current: Conversation) {
            check(!fail)
            saved = current
            draft = null
        }
        override suspend fun writeDraft(current: Conversation) {
            check(!fail)
            draft = current.takeIf { it.pluginState.isNotEmpty() }
        }
    }
}
