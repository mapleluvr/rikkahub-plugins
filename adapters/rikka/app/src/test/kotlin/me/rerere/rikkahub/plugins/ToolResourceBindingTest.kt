package me.rerere.rikkahub.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ToolResourceBindingTest {
    private val server = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(
            name = "same",
            headers = listOf("Authorization" to "secret"),
            tools = listOf(McpTool(name = "effect", needsApproval = true))
        ),
        url = "https://one.invalid/mcp",
    )

    @Test
    fun sameSchemaAndNameCannotHideDifferentServerEndpointCredentialsOrPolicy() {
        val original = Settings(mcpServers = listOf(server)).bindMcpExecutionVersions(Settings())
        val saved = original.mcpServers.single() as McpServerConfig.StreamableHTTPServer
        val binding = saved.toolExecutionVersion("effect")!!
        val changes = listOf(
            saved.copy(id = Uuid.random()),
            saved.copy(url = "https://two.invalid/mcp"),
            saved.copy(commonOptions = saved.commonOptions.copy(headers = listOf("Authorization" to "changed"))),
            saved.copy(commonOptions = saved.commonOptions.copy(tools = listOf(McpTool(name = "effect"))))
        )
        for (changed in changes) {
            val next = original.copy(mcpServers = listOf(changed)).bindMcpExecutionVersions(original)
            assertNotEquals(binding, next.mcpServers.single().toolExecutionVersion("effect"))
        }
        assertFalse(binding.contains("secret"))
        assertFalse(binding.contains("https"))
        assertEquals(original, original.bindMcpExecutionVersions(original))
    }

    @Test
    fun tokenRefreshPreservesGrantButNewAuthorizationInvalidatesApproval() {
        val oauth = McpOAuthState(enabled = true, grantId = "grant-a", accessToken = "old", refreshToken = "old-r")
        val original = Settings(
            mcpServers = listOf(server.copy(commonOptions = server.commonOptions.copy(oauth = oauth)))
        )
            .bindMcpExecutionVersions(Settings())
        val saved = original.mcpServers.single()
        val refreshed = saved.clone(
            commonOptions = saved.commonOptions.copy(
                oauth = oauth.copy(accessToken = "new", refreshToken = "new-r", expiresAt = 999999)
            )
        )
        val next = original.copy(mcpServers = listOf(refreshed)).bindMcpExecutionVersions(original)
        assertEquals(saved.toolExecutionVersion("effect"), next.mcpServers.single().toolExecutionVersion("effect"))
        val otherGrant = refreshed.clone(
            commonOptions = refreshed.commonOptions.copy(oauth = oauth.copy(grantId = "grant-b"))
        )
        val changed = next.copy(mcpServers = listOf(otherGrant)).bindMcpExecutionVersions(next)
        assertNotEquals(
            saved.toolExecutionVersion("effect"),
            changed.mcpServers.single().toolExecutionVersion("effect")
        )
    }

    @Test
    fun workspaceGuardSurvivesDispatcherHopAndRejectsRetargetedBackend() = runBlocking {
        val original = WorkspaceEntity("id", "name", "root-a", createdAt = 1, updatedAt = 1)
        var effects = 0
        withContext(WorkspaceExecutionBinding(original.toolExecutionVersion())) {
            withContext(Dispatchers.IO) {
                WorkspaceExecutionBinding.requireResource(original)
                effects++
                val error = runCatching {
                    WorkspaceExecutionBinding.requireResource(original.copy(root = "root-b"))
                    effects++
                }.exceptionOrNull()
                assertTrue(error is IllegalStateException)
            }
        }
        assertEquals(1, effects)
        assertEquals(original.toolExecutionVersion(), original.copy(lastAccessAt = 20).toolExecutionVersion())
        WorkspaceExecutionBinding.requireResource(original.copy(root = "outside-bound-execution"))
    }
}
