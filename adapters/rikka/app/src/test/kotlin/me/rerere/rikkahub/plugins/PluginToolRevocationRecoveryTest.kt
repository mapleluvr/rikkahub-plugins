package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.ExecutionAuthority
import dev.rikkahub.plugins.core.ExecutionBinding
import dev.rikkahub.plugins.core.ExecutionResultUnavailable
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.RunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** Consumer-level protection: an interrupted owning scope has no confirmed tool outcome. */
class PluginToolRevocationRecoveryTest {
    @Test
    fun revocationBeforeToolDispatchCannotBecomeAConfirmedFailure() = runBlocking {
        verifyRevocation(afterDispatch = false)
    }

    @Test
    fun revocationAfterToolDispatchPreservesUnknownEffectAcrossColdRecovery() = runBlocking {
        verifyRevocation(afterDispatch = true)
    }

    @Test
    fun rolledBackRevocationStillCannotConfirmOrContinueAnInterruptedTool() = runBlocking {
        verifyRevocation(afterDispatch = false, rolledBack = true)
    }

    @Test
    fun transportLossBeforeDispatchCannotBecomeAConfirmedToolResult() = runBlocking {
        verifyRevocation(afterDispatch = false, transportDisconnected = true)
    }

    @Test
    fun transportLossAfterDispatchPreservesOriginalAuthorizedRunAcrossRecovery() = runBlocking {
        verifyRevocation(afterDispatch = true, transportDisconnected = true)
    }

    @Test
    fun stopWinningBeforeFailureClassificationCannotBeRevivedByARecoveryGate() = runBlocking {
        val initial = conversation()
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val provider = ToolProvider()
        val authority = ExecutionAuthority { true }
        val owner = Job()
        val scope = authority.open(ExecutionBinding(emptyList()), owner)
        val run = SessionAgentRun.open(session, initial.currentMessages) { _, _ -> null }
        var terminalStatus: RunStatus? = null
        val tool = Tool(name = "effect", description = "effect", execute = {
            run.cancel()
            terminalStatus = session.state.value.executionLedger().runs.getValue(run.ref.runId).state.status
            authority.close()
            scope.check()
            listOf(UIMessagePart.Text("not reachable"))
        })
        try {
            runAgent(run.bind(request(provider, initial.currentMessages, tool), Uuid.random()), run.hooks(hooks))
                .collect(run::accept)
            val saved = session.state.value.executionLedger().runs.getValue(run.ref.runId)
            assertNotNull(terminalStatus)
            assertFalse(terminalStatus == RunStatus.ACTIVE)
            assertEquals(terminalStatus, saved.state.status)
            assertFalse("Stop must not be replaced by a new recovery wait", "host.recovery" in saved.state.gates)
            assertEquals(1, provider.calls)
        } finally {
            scope.close()
            owner.cancel()
        }
    }

    @Test
    fun ordinaryToolFailureStillProducesAConfirmedFailureResult() = runBlocking {
        val initial = conversation()
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val provider = ToolProvider()
        val tool = Tool(name = "effect", description = "effect", execute = { error("ordinary domain failure") })
        val run = SessionAgentRun.open(session, initial.currentMessages) { _, _ -> null }
        runAgent(run.bind(request(provider, initial.currentMessages, tool), Uuid.random()), run.hooks(hooks))
            .collect(run::accept)
        val part = session.state.value.messageNodes.flatMap { it.messages }.flatMap { it.getTools() }.single()
        assertTrue(part.isExecuted)
        assertNotNull(part.finishedAt)
        assertTrue(part.output.joinToString().contains("ordinary domain failure"))
        assertEquals(2, provider.calls)
    }

    private suspend fun CoroutineScope.verifyRevocation(
        afterDispatch: Boolean,
        rolledBack: Boolean = false,
        transportDisconnected: Boolean = false,
    ) {
        val initial = conversation()
        val store = FakeConversationStore(initial)
        val session = open(initial, store)
        val issuer = Uuid.random().toString()
        val leases = listOf("a", "b").mapIndexed { index, id ->
            PackageLease("dev.example.$id", (index + 1).toString().repeat(64), 1, issuer)
        }
        var authorized = true
        val authority = ExecutionAuthority { authorized }
        val owner = Job()
        val scope = authority.open(ExecutionBinding(leases), owner)
        var effects = 0
        val provider = ToolProvider()
        val tool = Tool(name = "effect", description = "effect", execute = {
            // Exercise the real core revocation signal, not a fabricated tool-error exception.
            if (afterDispatch) scope.dispatch { effects++ }
            if (transportDisconnected) throw ExecutionResultUnavailable("Plugin runtime disconnected")
            authorized = false
            authority.beginRevocation(leases.first().pluginId)
            if (rolledBack) {
                authorized = true
                authority.endRevocation(leases.first().pluginId)
            }
            scope.check()
            listOf(UIMessagePart.Text("not reachable"))
        })
        try {
            val run = SessionAgentRun.open(
                session,
                initial.currentMessages,
                pluginLeases = leases,
                packageAuthorization = { authorized }
            ) { _, _ -> null }
            runAgent(run.bind(request(provider, initial.currentMessages, tool), Uuid.random()), run.hooks(hooks))
                .collect(run::accept)
            assertEquals(if (afterDispatch) 1 else 0, effects)
            val part = session.state.value.messageNodes.flatMap { it.messages }.flatMap { it.getTools() }.single()
            assertNotNull("Tool-start evidence must remain", part.startedAt)
            assertFalse("An interrupted scope is not a confirmed failure result", part.isExecuted)
            assertNull("Do not claim the interrupted tool finished", part.finishedAt)
            assertTrue(part.output.isEmpty())
            assertEquals(1, provider.calls)
            if (transportDisconnected) {
                assertTrue("Transport loss must not pretend the original leases were revoked", authorized)
                assertFalse(
                    "host.package-authorization" in
                        session.state.value.executionLedger().runs.getValue(run.ref.runId).state.gates
                )
            }
            assertTrue(
                "The live run must wait even if persistent revocation rolled back",
                "host.recovery" in session.state.value.executionLedger().runs.getValue(run.ref.runId).state.gates
            )

            // Reconstruct from durable state and restore the original authority explicitly.
            // Even then an unconfirmed admitted effect may not be skipped or replayed automatically.
            authorized = true
            authority.endRevocation(leases.first().pluginId)
            val reopened = open(initial, store)
            val recovered = SessionAgentRun.open(
                reopened,
                reopened.state.value.currentMessages,
                packageAuthorization = { authorized }
            ) { _, _ -> null }
            assertEquals(run.ref, recovered.ref)
            val saved = reopened.state.value.executionLedger().runs.getValue(run.ref.runId)
            assertTrue("Unknown tool outcome requires reconciliation", "host.recovery" in saved.state.gates)
            val plan = recovered.resumePlan()
            runAgent(recovered.bind(request(provider, plan.history, tool), plan), recovered.hooks(hooks))
                .collect(recovered::accept)
            assertEquals("Recovery must not issue the next model request", 1, provider.calls)
            assertEquals(if (afterDispatch) 1 else 0, effects)
            val afterRecovery = reopened.state.value.executionLedger().runs.getValue(run.ref.runId)
            assertEquals(1, afterRecovery.state.stepsUsed)
            assertEquals(leases, afterRecovery.pluginLeases)
        } finally {
            scope.close()
            owner.cancel()
        }
    }

    private val hooks = object : AgentHooks {
        override suspend fun prepareStep(messages: List<UIMessage>) =
            StepRequest(messages, TextGenerationParams(Model()))
    }

    private fun request(provider: ToolProvider, messages: List<UIMessage>, tool: Tool) =
        AgentRequest(provider, ProviderSetting.OpenAI(), messages, tools = listOf(tool), stream = false)

    private fun conversation() = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("start")))

    private suspend fun CoroutineScope.open(initial: Conversation, store: FakeConversationStore) =
        ConversationSession(initial.id, initial, this, store, {}).also { it.initialize({ store.persisted }) }

    private class ToolProvider : Provider<ProviderSetting> {
        var calls = 0
        override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> = error("Non-streaming fixture")

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            calls++
            val parts = if (calls == 1) {
                listOf(UIMessagePart.Tool(toolCallId = "call", toolName = "effect", input = "{}"))
            } else {
                listOf(UIMessagePart.Text("answer"))
            }
            return TextGenerationResult("id", "model", UIMessage(role = MessageRole.ASSISTANT, parts = parts))
        }
    }
}
