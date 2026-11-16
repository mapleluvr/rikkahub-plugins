package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.agent.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.service.ConversationStore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Exercise the shipped serializer and inspect the HTTP body at the transport boundary. */
class ProjectionProviderContractTest {
    @Test
    fun noSummaryReductionChangesRealProviderPayloadWhileRetainingHistory() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(
            UIMessage.user("old input"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
                toolCallId = "old-call",
                toolName = "search",
                input = "{}",
                output = listOf(UIMessagePart.Text("old result"))
            ))),
            UIMessage.user("current input"),
        ))
        val store = object : ConversationStore {
            var persisted: Conversation? = initial
            override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
            override suspend fun write(previous: Conversation?, current: Conversation) { persisted = current }
        }
        val session = ConversationSession(initial.id, initial, this, store, {
        }).also { it.initialize({ store.persisted }) }
        val omit = initial.currentMessages.take(2).mapTo(HashSet()) { it.id.toString() }
        val operation =
            ConversationOperation("test/reduce", "Reduce", PackageLease("test", "v1", 1), setOf("context.write")) {
                    source,
                    _
                ->
                PreparedConversationOperation(source.pluginSources(), execute = {
                    OperationResult(projection = ProjectionPlan(omit))
                })
            }
        SessionOperationDispatcher(listOf(operation)) { _, _ -> true }
            .execute(session, initial.operationCall(operation, JsonNull))
        assertEquals(initial.currentMessages, store.persisted!!.currentMessages)
        var body: JsonObject? = null
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            body = Json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """{"id":"result","object":"chat.completion","model":"fixture","choices":[{"index":0,"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}]}""".toResponseBody(
                        "application/json".toMediaType()
                    )
                ).build()
        }.build()
        try {
            @Suppress("UNCHECKED_CAST")
            val provider = OpenAIProvider(client) as Provider<ProviderSetting>
            val run = SessionAgentRun.open(session, initial.currentMessages) { _, _ -> null }
            val hooks = object : AgentHooks {
                override suspend fun prepareStep(
                    messages: List<UIMessage>
                ) = StepRequest(messages, TextGenerationParams(Model(modelId = "fixture")))
            }
            runAgent(
                run.bind(
                    AgentRequest(
                        provider,
                        ProviderSetting.OpenAI(baseUrl = "https://fixture.invalid/v1"),
                        initial.currentMessages,
                        stream = false
                    ),
                    Uuid.random()
                ),
                run.hooks(hooks)
            ).collect(run::accept)
            val wire = body!!.getValue("messages").jsonArray
            assertEquals(1, requests)
            assertEquals(1, wire.size)
            assertEquals("current input", wire.single().jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals(initial.currentMessages, session.state.value.currentMessages.take(3))
            assertTrue(session.state.value.currentMessages.none { it.isContextCheckpoint })
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
