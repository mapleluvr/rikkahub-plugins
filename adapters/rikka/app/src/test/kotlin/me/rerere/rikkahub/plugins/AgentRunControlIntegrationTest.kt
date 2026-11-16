package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.RunControl
import dev.rikkahub.plugins.protocol.Gate
import dev.rikkahub.plugins.protocol.GateKind
import dev.rikkahub.plugins.protocol.RunRef
import dev.rikkahub.plugins.protocol.RunState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import me.rerere.ai.agent.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration

class AgentRunControlIntegrationTest {
    @Test
    fun frozenFrameAllowsTransientRetryButPauseResumeRevokesIt() = runTest {
        for (pauseDuringRetry in listOf(false, true)) {
            var state = RunState(RunRef("c", "b", "r", 1), 2, 3)
            val frozenFrame = state.frameRevision
            var calls = 0
            val provider = object : Provider<ProviderSetting> {
                override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
                override suspend fun streamText(
                    providerSetting: ProviderSetting,
                    messages: List<UIMessage>,
                    params: TextGenerationParams
                ): Flow<StreamChunk> = error("not streaming")
                override suspend fun generateText(
                    providerSetting: ProviderSetting,
                    messages: List<UIMessage>,
                    params: TextGenerationParams
                ): TextGenerationResult {
                    calls++
                    if (calls == 1) throw IOException("transient")
                    return TextGenerationResult(
                        "id",
                        "model",
                        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("done")))
                    )
                }
            }
            val control = object : AgentControl {
                override fun admitModel(step: Int, attempt: Int): String? {
                    val result = RunControl.admit(state, step, frozenFrame)
                    state = result.state
                    return result.reason
                }
            }
            val request = AgentRequest(
                provider,
                ProviderSetting.OpenAI(),
                listOf(UIMessage.user("go")),
                stream = false,
                retry = RetryPolicy(1, Duration.ZERO, retryOn = {
                    it is IOException
                }),
                control = control
            )
            val events = mutableListOf<AgentEvent>()
            runAgent(request, object : AgentHooks {
                override suspend fun prepareStep(
                    messages: List<UIMessage>
                ) = StepRequest(messages, TextGenerationParams(model = Model()))
            }).collect {
                events += it
                if (it is AgentEvent.Retrying && pauseDuringRetry) {
                    state = RunControl.addGate(state, Gate("pause", GateKind.USER_PAUSE))
                    state = RunControl.resolveGate(state, "pause")
                }
            }
            assertEquals(if (pauseDuringRetry) 1 else 2, calls)
            assertEquals(calls, state.attemptsUsed)
            assertEquals(1, state.stepsUsed)
            if (pauseDuringRetry) {
                assertTrue(events.last() is AgentEvent.Yielded)
            } else {
                assertEquals(AgentFinishReason.Completed, (events.last() as AgentEvent.Finished).reason)
            }
        }
    }
}
