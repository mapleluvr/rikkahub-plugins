package me.rerere.ai.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration

class AgentControlContractTest {
    private val provider = ScriptedProvider()
    private val model = Model(modelId = "controlled")
    private val hooks = object : AgentHooks {
        override suspend fun prepareStep(
            messages: List<UIMessage>
        ) = StepRequest(messages, TextGenerationParams(model = model))
    }

    private fun request(control: AgentControl = AgentControl.Default) = AgentRequest(
        provider,
        ProviderSetting.OpenAI(),
        listOf(UIMessage.user("hello")),
        stream = false,
        control = control
    )

    @Test
    fun firstBoundaryCanYieldWithoutInventingAnAssistantMessage() = runTest {
        val control = object : AgentControl {
            override suspend fun decide(context: AgentBoundaryContext) = AgentDecision.Yield("user-pause")
        }
        val events = runAgent(request(control), hooks).toList()
        val yield = events.single() as AgentEvent.Yielded
        assertNull(yield.message)
        assertEquals(AgentCursor(AgentBoundary.BeforeModel, 0), yield.cursor)
        assertEquals(0, provider.calls)
    }

    @Test
    fun permissionRevokedDuringPreparationPreventsProviderCall() = runTest {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var allowed = true
        val control = object : AgentControl {
            override fun admitModel(step: Int, attempt: Int) = if (allowed) null else "revoked"
        }
        val delayed = object : AgentHooks by hooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                entered.complete(Unit)
                released.await()
                return hooks.prepareStep(messages)
            }
        }
        val result = async { runAgent(request(control), delayed).toList() }
        entered.await()
        allowed = false
        released.complete(Unit)
        assertEquals("revoked", (result.await().last() as AgentEvent.Yielded).token)
        assertEquals(0, provider.calls)
    }

    @Test
    fun everyRetryRechecksAdmissionForStreamingAndNonStreamingRequests() = runTest {
        for (stream in listOf(false, true)) {
            val transport = ScriptedProvider().apply { fail = true }
            var allowed = true
            val control = object : AgentControl {
                override fun admitModel(step: Int, attempt: Int) = if (allowed) null else "paused"
            }
            val events = mutableListOf<AgentEvent>()
            runAgent(request(control).copy(
                provider = transport,
                stream = stream,
                retry = RetryPolicy(3, Duration.ZERO, retryOn = { true })
            ), hooks).collect {
                events += it
                if (it is AgentEvent.Retrying) allowed = false
            }
            assertEquals(1, transport.calls)
            assertEquals("paused", (events.last() as AgentEvent.Yielded).token)
        }
    }

    @Test
    fun pausingBetweenToolsResumesOnlyTheUnfinishedTool() = runTest {
        val executed = mutableListOf<String>()
        val tools = listOf("one", "two").map { name ->
            Tool(name = name, description = name, execute = {
                executed += name
                listOf(UIMessagePart.Text("done:$name"))
            })
        }
        val pending = UIMessage(role = MessageRole.ASSISTANT, parts = tools.map { tool ->
            UIMessagePart.Tool(toolCallId = tool.name, toolName = tool.name, input = "{}")
        })
        var preflights = 0
        var paused = false
        val control = object : AgentControl {
            override suspend fun decide(context: AgentBoundaryContext): AgentDecision {
                if (context.cursor.boundary == AgentBoundary.ToolPreflight) preflights++
                if (context.cursor.toolCallId == "two" && !paused) {
                    paused = true
                    return AgentDecision.Yield("wait")
                }
                return AgentDecision.Proceed
            }
        }
        val first = request(
            control
        ).copy(messages = listOf(UIMessage.user("go"), pending), tools = tools, completedSteps = 1)
        val yield = runAgent(first, hooks).toList().last() as AgentEvent.Yielded
        assertEquals(listOf("one"), executed)
        assertTrue(yield.message!!.getTools()[0].isExecuted)
        assertFalse(yield.message!!.getTools()[1].isExecuted)
        val resumed = runAgent(first.copy(
            messages = listOf(UIMessage.user("go"), yield.message!!),
            resumeCursor = yield.cursor
        ), hooks).toList().last() as AgentEvent.Finished
        assertEquals(listOf("one", "two"), executed)
        assertEquals(1, preflights)
        assertEquals(AgentFinishReason.Completed, resumed.reason)
        assertEquals(2, resumed.completedSteps)
    }

    @Test
    fun completionCanRequestAnotherStepButCannotResetTheLogicalBudget() = runTest {
        val control = object : AgentControl {
            override suspend fun decide(context: AgentBoundaryContext): AgentDecision =
                if (context.cursor.boundary == AgentBoundary.BeforeComplete) {
                    AgentDecision.Continue(UIMessagePart.Notice(title = "Continue", text = "Check the result"))
                } else {
                    AgentDecision.Proceed
                }
        }
        val result = runAgent(
            request(control).copy(maxSteps = 3, completedSteps = 2),
            hooks
        ).toList().last() as AgentEvent.Finished
        assertEquals(AgentFinishReason.StepLimitReached, result.reason)
        assertEquals(1, provider.calls)
        assertEquals(3, result.completedSteps)
    }

    @Test
    fun completionYieldResumesAtCompletionWithoutRepeatingTheModel() = runTest {
        var pause = true
        val control = object : AgentControl {
            override suspend fun decide(context: AgentBoundaryContext): AgentDecision {
                if (context.cursor.boundary == AgentBoundary.BeforeComplete && pause) {
                    pause = false
                    return AgentDecision.Yield("review")
                }
                return AgentDecision.Proceed
            }
        }
        val initial = request(control)
        val yield = runAgent(initial, hooks).toList().last() as AgentEvent.Yielded
        val result = runAgent(initial.copy(
            messages = initial.messages + yield.message!!,
            completedSteps = yield.cursor.nextStep,
            resumeCursor = yield.cursor
        ), hooks).toList().last() as AgentEvent.Finished
        assertEquals(AgentFinishReason.Completed, result.reason)
        assertEquals(1, provider.calls)
    }

    @Test
    fun afterToolsWaitIsRecheckedBeforePreparingAnotherRequest() = runTest {
        val tool = UIMessagePart.Tool(
            toolCallId = "one", toolName = "one", input = "{}",
            output = listOf(UIMessagePart.Text("settled"))
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val visited = mutableListOf<AgentBoundary>()
        val control = object : AgentControl {
            override suspend fun decide(context: AgentBoundaryContext): AgentDecision {
                visited += context.cursor.boundary
                return AgentDecision.Yield("still-waiting")
            }
        }
        val result = runAgent(request(control).copy(
            messages = listOf(UIMessage.user("go"), message),
            completedSteps = 1,
            resumeCursor = AgentCursor(AgentBoundary.AfterTools, 1)
        ), hooks).toList().last() as AgentEvent.Yielded
        assertEquals(listOf(AgentBoundary.AfterTools), visited)
        assertEquals(AgentBoundary.AfterTools, result.cursor.boundary)
        assertEquals(0, provider.calls)
    }

    private class ScriptedProvider : Provider<ProviderSetting> {
        var calls = 0
        var fail = false
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams
        ): TextGenerationResult {
            calls++
            if (fail) throw IOException("network")
            return TextGenerationResult("result", "controlled", UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("answer"))
            ))
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams
        ): Flow<StreamChunk> = flow {
            calls++
            if (fail) throw IOException("network")
            emit(StreamChunk.TextStart("text"))
            emit(StreamChunk.TextDelta("text", "answer"))
            emit(StreamChunk.TextEnd("text"))
            emit(StreamChunk.Finish("stop"))
        }
    }
}
