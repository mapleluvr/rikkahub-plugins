package me.rerere.rikkahub.plugins

import kotlinx.coroutines.ThreadContextElement
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/** Resource edits get opaque identities; connection credentials never enter an execution ledger. */
fun Settings.bindMcpExecutionVersions(previous: Settings): Settings {
    val old = previous.mcpServers.associateBy { it.id }
    val servers = mcpServers.map { server ->
        val prior = old[server.id]
        if (prior != null && prior.executionConfiguration() == server.executionConfiguration()) {
            server.clone(
                commonOptions = server.commonOptions.copy(executionRevision = prior.commonOptions.executionRevision)
            )
        } else {
            server.clone(commonOptions = server.commonOptions.copy(executionRevision = Uuid.random().toString()))
        }
    }
    return if (servers == mcpServers) this else copy(mcpServers = servers)
}

/** A refreshed token retains the grant; a new login, policy or resource changes it. */
private fun McpServerConfig.executionConfiguration(): McpServerConfig = clone(commonOptions = commonOptions.copy(
    executionRevision = "",
    oauth = commonOptions.oauth?.copy(accessToken = null, refreshToken = null, expiresAt = 0),
))

fun McpServerConfig.toolExecutionVersion(toolName: String): String? =
    if (commonOptions.enable && commonOptions.tools.any { it.name == toolName && it.enable }) {
        "mcp/$id/${commonOptions.executionRevision}/$toolName"
    } else {
        null
    }

fun WorkspaceEntity.toolExecutionVersion(): String {
    // sshConfig contains ciphertext, never plaintext credentials. Unrelated access timestamps are excluded.
    val fields = listOf(
        id,
        root,
        type,
        shellStatus,
        shellCompatibilityMode.toString(),
        sshConfig.orEmpty(),
        storageMounts,
        toolApprovals
    )
    val bytes = JsonInstant.encodeToString(fields).toByteArray()
    return "workspace/" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Carries the admitted resource through dispatcher changes to every actual backend acquisition. */
class WorkspaceExecutionBinding(private val version: String) :
    AbstractCoroutineContextElement(Key),
    ThreadContextElement<String?> {
    override fun updateThreadContext(context: CoroutineContext): String? = expected.get().also { expected.set(version) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
        expected.set(oldState)
    }

    companion object Key : CoroutineContext.Key<WorkspaceExecutionBinding> {
        private val expected = ThreadLocal<String?>()
        fun requireResource(workspace: WorkspaceEntity) {
            expected.get()?.let {
                check(it == workspace.toolExecutionVersion()) { "Workspace execution resource changed" }
            }
        }
    }
}
