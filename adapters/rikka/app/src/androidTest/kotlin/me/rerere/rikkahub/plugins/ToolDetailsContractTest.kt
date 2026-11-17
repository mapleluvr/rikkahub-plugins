package me.rerere.rikkahub.plugins

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionResult
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDetailsContractTest {
    @Test fun detailsSurviveSerializationButNeverEnterProviderRequests() = runBlocking {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """{"id":"result","object":"chat.completion","model":"fixture","choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}""".toResponseBody(
                        "application/json".toMediaType()
                    )
                )
                .build()
        }.build()
        try {
            val details = JsonObject(mapOf("private-ui-field" to JsonPrimitive("ui-only-value")))
            val tool = Tool(
                name = "review",
                description = "review",
                executeDetailed = { ToolExecutionResult(listOf(UIMessagePart.Text("model answer")), details) },
                execute = { error("Detailed tools must not invoke the legacy handler") },
            )
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool(toolCallId = "review-call", toolName = tool.name, input = "{}")
            ))

            @Suppress("UNCHECKED_CAST")
            val provider = OpenAIProvider(client) as Provider<ProviderSetting>
            val hooks = object : AgentHooks {
                override suspend fun prepareStep(messages: List<UIMessage>) =
                    StepRequest(messages, TextGenerationParams(Model(modelId = "fixture")))
            }
            val events = runAgent(AgentRequest(
                provider,
                ProviderSetting.OpenAI(baseUrl = "https://fixture.invalid/v1"),
                listOf(UIMessage.user("go"), message),
                tools = listOf(tool),
                stream = false
            ), hooks).toList()
            val finished = (events.last() as AgentEvent.Finished).message
            val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(finished))
            assertEquals(details, restored.getTools().single().details)
            assertEquals(1, requests.size)
            assertFalse(requests.single().contains("private-ui-field"))
            assertFalse(requests.single().contains("ui-only-value"))
            val sent = Json.parseToJsonElement(requests.single()).jsonObject.getValue("messages").jsonArray
            assertTrue(sent.any { it.jsonObject["content"]?.jsonPrimitive?.content == "model answer" })
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
