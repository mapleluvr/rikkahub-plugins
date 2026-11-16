package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

class ChatServiceRecoveryTest {
    @Test
    fun coldOrdinarySendKeepsPersistedPauseAndStopCancelsWithoutLiveJob() = runBlocking {
        val repo = GlobalContext.get().get<ConversationRepository>()
        val service = GlobalContext.get().get<ChatService>()
        val id = Uuid.random()
        val ref = RunRef(id.toString(), "draft", "paused-run")
        val checkpoint = RunCheckpoint(
            RunState(ref, 5, 10, gates = mapOf("user" to Gate("user", GateKind.USER_PAUSE))),
            "BeforeModel",
            0,
        )
        repo.writePluginDraft(
            Conversation.ofId(id).withExecutionLedger(ExecutionLedger(runs = mapOf(ref.runId to checkpoint)))
        )
        service.addConversationReference(id)
        try {
            service.sendMessage(id, listOf(UIMessagePart.Text("queued input")), answer = false)
            withTimeout(10000) {
                service.getConversationFlow(id).first { it.pluginState.isNotEmpty() }
                service.getGenerationJobStateFlow(id).first { it == null }
            }
            val waiting = service.getConversation(id)!!
            assertTrue(waiting.messageNodes.isEmpty())
            assertEquals(1, service.getInboxFlow(id).value.messages.size)
            assertEquals(checkpoint, waiting.executionLedger().runs.getValue(ref.runId))
            assertEquals(checkpoint, repo.getConversationById(id)!!.executionLedger().runs.getValue(ref.runId))
            service.stopGeneration(id)
            assertEquals(
                RunStatus.CANCELLED,
                repo.getConversationById(id)!!.executionLedger().runs.getValue(ref.runId).state.status
            )
            assertTrue(service.getInboxFlow(id).value.paused)
        } finally {
            service.deleteConversation(id)
            service.removeConversationReference(id)
        }
    }
}
