package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration

class AgentReviewRegressionTest {
    private val provider = Transport()
    private val hooks = object : AgentHooks {
        override suspend fun prepareStep(messages: List<UIMessage>) =
            StepRequest(messages, TextGenerationParams(model = Model(modelId = "fixture")))
    }
    private fun request(message: UIMessage? = null) = AgentRequest(
        provider,
        ProviderSetting.OpenAI(),
        listOfNotNull(UIMessage.user("go"), message),
        stream = false,
    )

    @Test
    fun toolIdentitySurvivesDenialAnswerArgumentFailureAndHandlerFailure() = runTest {
        val identity = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"plugin":{"pluginId":"old","packageHash":"v1","activationEpoch":1},"title":"V1 title","toolId":"action"}"""
        ) as kotlinx.serialization.json.JsonObject
        val definition = Tool(name = "action", description = "new definition", execute = {
            throw IllegalStateException("handler failed")
        })
        val tools = listOf(
            UIMessagePart.Tool("denied", "action", "{}", approvalState = me.rerere.ai.ui.ToolApprovalState.Denied("no"), details = identity),
            UIMessagePart.Tool("answered", "action", "{}", approvalState = me.rerere.ai.ui.ToolApprovalState.Answered("answer"), details = identity),
            UIMessagePart.Tool("arguments", "action", "{", details = identity),
            UIMessagePart.Tool("handler", "action", "{}", details = identity),
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = tools)
        val result = runAgent(request(message).copy(tools = listOf(definition)), hooks)
            .toList().last() as AgentEvent.Finished
        for (saved in result.message.getTools()) {
            assertTrue(saved.isExecuted)
            val encoded = kotlinx.serialization.json.Json.encodeToString(UIMessagePart.Tool.serializer(), saved)
            val reopened = kotlinx.serialization.json.Json.decodeFromString(UIMessagePart.Tool.serializer(), encoded)
            assertEquals("Lost original identity for ${saved.toolCallId}", identity, reopened.details)
        }
    }

    @Test
    fun mismatchedToolResumeRefusesBeforeAnyEffectOrModelCall() = runTest {
        var effects = 0
        val tools = listOf("A", "B").map { name ->
            Tool(name = name, description = name, execute = {
                effects++
                listOf(UIMessagePart.Text("done"))
            })
        }
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = tools.map {
            UIMessagePart.Tool(toolCallId = it.name, toolName = it.name, input = "{}")
        })
        val initial = request(message).copy(tools = tools, control = object : AgentControl {
            override suspend fun decide(context: AgentBoundaryContext) =
                if (context.cursor.boundary == AgentBoundary.BeforeTool) {
                    AgentDecision.Yield("pause")
                } else {
                    AgentDecision.Proceed
                }
        })
        val waiting = runAgent(initial, hooks).toList().last() as AgentEvent.Yielded
        assertEquals(0, effects)
        val resumed = initial.copy(control = AgentControl.Default, resumeCursor = waiting.cursor)
        for (invalid in listOf(
            resumed.copy(resumeCursor = waiting.cursor.copy(toolCallId = "B")),
            resumed.copy(messages = listOf(UIMessage.user("no tools"))),
            resumed.copy(messages = listOf(message.copy(parts = listOf(message.parts[1])))),
            resumed.copy(messages = listOf(message.copy(id = kotlin.uuid.Uuid.random()))),
            resumed.copy(messages = listOf(message.copy(parts = listOf(
                (message.parts[0] as UIMessagePart.Tool).copy(input = "{\"changed\":true}"),
                message.parts[1]
            )))),
        )) {
            assertTrue(runCatching { runAgent(invalid, hooks).toList() }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0, effects)
        assertEquals(0, provider.calls)
        runAgent(resumed, hooks).toList()
        assertEquals(2, effects)
        assertEquals(1, provider.calls)
    }

    @Test
    fun afterToolsResumeContinuesRemainingStageIncludingPausePolicy() = runTest {
        var pauses = 0
        var effects = 0
        val tool = Tool(name = "A", description = "A", execute = {
            effects++
            listOf(UIMessagePart.Text("done"))
        })
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(toolCallId = "A", toolName = "A", input = "{}"))
        )
        val policy = object : AgentHooks by hooks {
            override suspend fun shouldPause(message: UIMessage): Boolean {
                pauses++
                return true
            }
        }
        val initial = request(message).copy(tools = listOf(tool), control = object : AgentControl {
            override suspend fun decide(context: AgentBoundaryContext) =
                if (context.cursor.boundary == AgentBoundary.AfterTools) {
                    AgentDecision.Yield("pause")
                } else {
                    AgentDecision.Proceed
                }
        })
        val waiting = runAgent(initial, policy).toList().last() as AgentEvent.Yielded
        assertEquals(0, pauses)
        val result = runAgent(initial.copy(
            messages = listOf(waiting.message!!),
            resumeCursor = waiting.cursor,
            control = AgentControl.Default,
        ), policy).toList().last() as AgentEvent.Finished
        assertEquals(AgentFinishReason.Paused, result.reason)
        assertEquals(1, pauses)
        assertEquals(1, effects)
        assertEquals(0, provider.calls)
    }

    @Test
    fun failedStreamingPreviewIsExplicitlyRestoredBeforeRetryCanWait() = runTest {
        for (original in listOf(
            null,
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("old")))
        )) {
            val transport = Transport().apply { failPartial = true }
            var paused = false
            val control = object : AgentControl {
                override fun admitModel(step: Int, attempt: Int) = if (paused) "paused" else null
            }
            var visible = original
            val resets = mutableListOf<AgentEvent.PreviewReset>()
            val initial = request(original).copy(
                provider = transport,
                stream = true,
                control = control,
                retry = RetryPolicy(1, Duration.ZERO, retryOn = { it is IOException })
            )
            var wait: AgentEvent.Yielded? = null
            runAgent(initial, hooks).collect { event ->
                when (event) {
                    is AgentEvent.MessageUpdated -> visible = event.message

                    is AgentEvent.PreviewReset -> {
                        assertEquals(visible!!.id, event.replyId)
                        resets += event
                        visible = event.authoritative
                    }

                    is AgentEvent.Retrying -> paused = true

                    is AgentEvent.Yielded -> wait = event

                    else -> Unit
                }
            }
            assertEquals(1, resets.size)
            assertEquals(original, visible)
            assertEquals(original, wait!!.message)
            transport.failPartial = false
            paused = false
            val restored = initial.copy(
                messages = listOfNotNull(UIMessage.user("go"), visible),
                resumeCursor = wait!!.cursor
            )
            val result = runAgent(restored, hooks).toList().last() as AgentEvent.Finished
            assertTrue(result.message.getTools().isEmpty())
            assertFalse(result.message.toText().contains("partial"))
            assertEquals(2, transport.calls)
        }
    }

    @Test
    fun exhaustedRunDoesNotEmitWaitOrAppendUnconsumableContinuation() = runTest {
        assertEquals(
            listOf(AgentEvent.BudgetExhausted(1)),
            runAgent(request().copy(maxSteps = 1, completedSteps = 1), hooks).toList()
        )
        assertEquals(0, provider.calls)
        val control = object : AgentControl {
            override suspend fun decide(context: AgentBoundaryContext) =
                if (context.cursor.boundary == AgentBoundary.BeforeComplete) {
                    AgentDecision.Continue(UIMessagePart.Notice(title = "continue", text = "new input"))
                } else {
                    AgentDecision.Proceed
                }
        }
        val events = runAgent(request().copy(maxSteps = 1, control = control), hooks).toList()
        val result = events.last() as AgentEvent.Finished
        assertEquals(AgentFinishReason.StepLimitReached, result.reason)
        assertTrue(events.none { it is AgentEvent.Yielded })
        assertTrue(events.filterIsInstance<AgentEvent.MessageUpdated>().all {
            it.message.parts.none { part -> part is UIMessagePart.Notice }
        })
        assertEquals(1, provider.calls)
    }

    private class Transport : Provider<ProviderSetting> {
        var calls = 0
        var failPartial = false
        override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams
        ): TextGenerationResult {
            calls++
            return TextGenerationResult(
                "id",
                "fixture",
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer")))
            )
        }
        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams
        ): Flow<StreamChunk> = flow {
            calls++
            emit(StreamChunk.TextStart("text"))
            emit(StreamChunk.TextDelta("text", if (failPartial) "partial" else "answer"))
            if (failPartial) {
                emit(StreamChunk.ToolCallStart("unfinished", "tool"))
                emit(StreamChunk.ToolCallDelta("unfinished", inputDelta = "{\"x\":"))
                throw IOException("retry")
            }
            emit(StreamChunk.TextEnd("text"))
            emit(StreamChunk.Finish("stop"))
        }
    }
}
