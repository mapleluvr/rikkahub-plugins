package me.rerere.rikkahub.plugins

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ToolApprovalBindingTest {
    @Test
    fun identicalProviderCallIdsOnSiblingRepliesDoNotShareApproval() = runBlocking {
        val tool = UIMessagePart.Tool("same", "effect", "{}", approvalState = ToolApprovalState.Pending)
        val a = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val b = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool.copy(input = "{\"other\":true}")))
        val node = MessageNode(messages = listOf(a, b))
        val source = Conversation.ofId(Uuid.random(), messages = listOf(node))
        val approved = source.withToolApproval("same", ToolApprovalState.Approved)!!
        assertEquals(
            ToolApprovalState.Approved,
            approved.messageNodes.single().messages[0].getTools().single().approvalState
        )
        assertEquals(
            ToolApprovalState.Pending,
            approved.messageNodes.single().messages[1].getTools().single().approvalState
        )
    }
}
