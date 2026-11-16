package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.runBlocking
import me.rerere.ai.agent.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.plugins.builtin.BuiltinContextFeature
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class BuiltinContextPolicyTest {
    private val feature = BuiltinContextFeature { _, _, _, _ -> "summary" }
    private val settings = Settings(enableAutoCompress = true, autoCompressThreshold = 0.8f)
    private val model = Model(contextLength = 100)
    private val reply =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("response")),
            lastUsage = TokenUsage(promptTokens = 80),
            stepCount = 1
        )
    private val source = Conversation.ofId(
        Uuid.random()
    ).updateCurrentMessages(listOf(UIMessage.user("question"), reply))

    @Test
    fun disabledDefaultFeaturePreventsBothAutomaticEntryPoints() {
        val disabled = settings.copy(disabledPluginFeatures = setOf(BuiltinContextFeature.FEATURE))
        assertNull(feature.automaticCommand(source, disabled, model))
        assertNull(feature.boundaryOperation(boundary(), source, disabled, model, true, false))
        assertNotNull(feature.automaticCommand(source, settings, model))
        assertNotNull(feature.boundaryOperation(boundary(), source, settings, model, true, false))
    }

    @Test
    fun thresholdAndUnproductiveSummaryRemainFeaturePolicy() {
        assertNull(feature.automaticCommand(source, settings.copy(enableAutoCompress = false), model))
        assertNull(feature.automaticCommand(source, settings, model.copy(contextLength = 101)))
        assertNull(feature.automaticCommand(source, settings, model.copy(contextLength = 0)))
        assertNull(feature.automaticCommand(source, settings, model, reply, afterOperation = true))
        assertNull(feature.boundaryOperation(boundary(), source, settings, model, false, false))
        assertNull(
            feature.boundaryOperation(
                boundary().copy(cursor = AgentCursor(AgentBoundary.BeforeModel, 1)),
                source,
                settings,
                model,
                true,
                false
            )
        )
    }

    @Test
    fun persistedAttemptPreventsAutomaticRetryAfterSessionRecreation() = runBlocking {
        val store = FakeConversationStore(source)
        val session = ConversationSession(source.id, source, this, store, {
        }).also { it.initialize({ store.persisted }) }
        val command = feature.automaticCommand(source, settings, model)!!
        val call = source.operationCall(feature.operation, feature.input(), command)
        val operations = SessionOperationStore(session)
        operations.prepare(call, source.pluginSources())
        operations.start(call.id, "attempt")
        operations.recover()
        assertEquals(AttemptStatus.UNKNOWN, store.persisted!!.executionLedger().operations.getValue(call.id).status)
        assertNull(feature.automaticCommand(store.persisted!!, settings, model))
    }

    @Test
    fun unproductiveSummaryRemainsDisabledForLaterToolBatchesAndRecreation() = runBlocking {
        val store = FakeConversationStore(source)
        val session = ConversationSession(source.id, source, this, store, {}).also { it.initialize({ source }) }
        val run = SessionAgentRun.open(session, source.currentMessages) { _, _ -> null }
        assertNull(feature.boundaryDecision(session, run.ref, boundary(), settings, model, true, true))
        val reopened = ConversationSession(source.id, source, this, store, {}).also { it.initialize({ store.persisted }) }
        assertNull(feature.boundaryDecision(reopened, run.ref,
            boundary().copy(message = reply.copy(stepCount = 2)), settings, model, true, false))
        assertEquals(1, reopened.state.value.executionLedger().runs.getValue(run.ref.runId).featureFlags.size)
    }

    private fun boundary() = AgentBoundaryContext(AgentCursor(AgentBoundary.AfterTools, 1), reply)
}
