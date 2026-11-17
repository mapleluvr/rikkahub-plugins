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
    fun coldStopRevokesApprovalAndLateDecisionCannotStartAnotherRun() = runBlocking {
        val repo = GlobalContext.get().get<ConversationRepository>()
        val service = GlobalContext.get().get<ChatService>()
        val id = Uuid.random()
        val user = me.rerere.ai.ui.UIMessage.user("request")
        val approvalId = Uuid.random().toString()
        val tool = UIMessagePart.Tool(
            "call",
            "effect",
            "{}",
            approvalState = me.rerere.ai.ui.ToolApprovalState.Pending,
            approvalId = approvalId
        )
        val reply = me.rerere.ai.ui.UIMessage(role = me.rerere.ai.core.MessageRole.ASSISTANT, parts = listOf(tool))
        val ref = RunRef(id.toString(), user.id.toString(), "approval-run")
        val call = ToolCallStamp("call", "effect", "{}")
        val checkpoint = RunCheckpoint(
            RunState(ref, 5, 10, gates = mapOf(TOOL_APPROVAL_GATE to Gate(TOOL_APPROVAL_GATE, GateKind.TOOL_APPROVAL))),
            "ToolPreflight",
            0,
            messageId = reply.id.toString(),
            toolBatch = listOf(call),
            inputIds = listOf(user.id.toString(), reply.id.toString()),
            toolDefinitions = mapOf("effect" to "resource-a"),
            approvalRequests = mapOf(
                approvalId to ToolApprovalRequestRecord(approvalId, reply.id.toString(), call, "resource-a")
            ),
        )
        repo.insertConversation(
            Conversation.ofId(id).copy(title = "approval recovery")
                .updateCurrentMessages(listOf(user, reply))
                .withExecutionLedger(ExecutionLedger(runs = mapOf(ref.runId to checkpoint)))
        )
        service.addConversationReference(id)
        try {
            service.stopGeneration(id)
            val stopped = repo.getConversationById(id)!!
            assertTrue(stopped.currentMessages.last().getTools().single().isExecuted)
            service.handleToolApproval(id, approvalId, approved = true)
            withTimeout(10000) { service.getGenerationJobStateFlow(id).first { it == null } }
            assertEquals(setOf(ref.runId), service.getConversation(id)!!.executionLedger().runs.keys)
            assertEquals(
                RunStatus.CANCELLED,
                service.getConversation(id)!!.executionLedger().runs.getValue(ref.runId).state.status
            )
            service.sendMessage(id, listOf(UIMessagePart.Text("ordinary after stop")), answer = false)
            withTimeout(10000) {
                service.getConversationFlow(id).first {
                    it.currentMessages.lastOrNull()?.toText() ==
                        "ordinary after stop"
                }
                service.getGenerationJobStateFlow(id).first { it == null }
            }
            assertEquals("ordinary after stop", repo.getConversationById(id)!!.currentMessages.last().toText())
            assertTrue(service.getInboxFlow(id).value.messages.isEmpty())
        } finally {
            service.deleteConversation(id)
            service.removeConversationReference(id)
        }
    }

    @Test
    fun missingHistoryColdResumeKeepsTheRunWaitingAndStopStillAllowsInput() = runBlocking {
        val repo = GlobalContext.get().get<ConversationRepository>()
        val service = GlobalContext.get().get<ChatService>()
        val user = me.rerere.ai.ui.UIMessage.user("surviving input")
        val initial = Conversation.ofId(Uuid.random()).copy(title = "damaged history")
            .updateCurrentMessages(listOf(user))
        val missing = Uuid.random().toString()
        val ref = RunRef(initial.id.toString(), user.id.toString(), "damaged-run")
        val checkpoint = RunCheckpoint(
            RunState(ref, 5, 10),
            "BeforeModel",
            0,
            inputIds = listOf(user.id.toString(), missing),
            messageId = missing,
        )
        repo.insertConversation(initial.withExecutionLedger(ExecutionLedger(runs = mapOf(ref.runId to checkpoint))))
        service.addConversationReference(initial.id)
        try {
            service.resumePluginRun(initial.id)
            withTimeout(10000) { service.getGenerationJobStateFlow(initial.id).first { it == null } }
            val waiting = repo.getConversationById(initial.id)!!
            val saved = waiting.executionLedger().runs.getValue(ref.runId)
            assertEquals(RunStatus.ACTIVE, saved.state.status)
            assertEquals(checkpoint.inputIds, saved.inputIds)
            assertEquals(missing, saved.messageId)
            assertTrue(MISSING_RUN_HISTORY_GATE in saved.state.gates)
            assertTrue(service.errors.value.none { it.conversationId == initial.id })
            assertEquals(0, saved.state.attemptsUsed)
            assertEquals(listOf(user), waiting.currentMessages)
            service.stopGeneration(initial.id)
            service.sendMessage(initial.id, listOf(UIMessagePart.Text("after stop")), answer = false)
            withTimeout(10000) {
                service.getConversationFlow(initial.id).first {
                    it.currentMessages.lastOrNull()?.toText() ==
                        "after stop"
                }
                service.getGenerationJobStateFlow(initial.id).first { it == null }
            }
            assertEquals("after stop", repo.getConversationById(initial.id)!!.currentMessages.last().toText())
        } finally {
            service.deleteConversation(initial.id)
            service.removeConversationReference(initial.id)
        }
    }

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
