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
    fun providerCallIdsAloneCannotGrantApprovalToEitherSibling() = runBlocking {
        val tool = UIMessagePart.Tool("same", "effect", "{}", approvalState = ToolApprovalState.Pending)
        val a = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val b = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool.copy(input = "{\"other\":true}")))
        val node = MessageNode(messages = listOf(a, b))
        val source = Conversation.ofId(Uuid.random(), messages = listOf(node))
        assertNull(source.withToolApproval("same", ToolApprovalState.Approved))
        assertTrue(source.messageNodes.single().messages.all {
            it.getTools().single().approvalState == ToolApprovalState.Pending
        })
    }
}
