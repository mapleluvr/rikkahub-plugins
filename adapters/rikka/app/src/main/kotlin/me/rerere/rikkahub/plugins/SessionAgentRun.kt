package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.RunControl
import dev.rikkahub.plugins.protocol.*
import me.rerere.ai.agent.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.uniqueToolRegistrations
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.service.ConversationSession
import kotlin.uuid.Uuid

data class BoundaryOperation(
    val definition: ConversationOperation,
    val input: kotlinx.serialization.json.JsonElement,
    val commandId: String
)

data class SessionResumePlan(
    val history: List<UIMessage>,
    val replyNodeId: Uuid,
    val checkpoint: RunCheckpoint,
    val cursor: AgentCursor?,
)

/** Adapts a logical persisted run to coroutine segments, without knowing individual operation semantics. */
class SessionAgentRun private constructor(
    private val session: ConversationSession,
    val ref: RunRef,
    private val policy: suspend (AgentBoundaryContext, RunRef) -> BoundaryOperation?,
) {
    private val store = SessionRunStore(session)
    private var replyNodeId: Uuid = Uuid.random()
    private var preparedFrame = 0L
    private var preparedSources: List<SourceStamp> = emptyList()
    private var preparedIds: List<String> = emptyList()
    private var cursor: AgentCursor? = null
    var pending: OperationCall? = null
        private set

    private fun snapshot() = session.state.value.executionLedger().runs.getValue(ref.runId)
    private fun blocked(): String? = snapshot().state.let {
        when {
            it.status != RunStatus.ACTIVE -> "Run ended"
            it.gates.isNotEmpty() -> "Run has unresolved gates"
            else -> null
        }
    }

    /** Called after open consumes any settled operation, once for each execution segment. */
    fun resumePlan(): SessionResumePlan {
        val current = session.state.value
        val checkpoint = current.executionLedger().runs.getValue(ref.runId)
        val history = current.runInputMessages(ref.runId)
        val node = checkpoint.replyNodeId?.let(Uuid::parse)
            ?: history.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
                ?.let { current.getMessageNodeByMessageId(it.id)?.id }
            ?: Uuid.random()
        return SessionResumePlan(history, node, checkpoint, cursor)
    }

    fun bind(request: AgentRequest, plan: SessionResumePlan): AgentRequest {
        val current = snapshot()
        // Control gates may change after preparation; the live admission checks yield without cancelling the run.
        check(plan.checkpoint.copy(state = current.state) == current && plan.cursor == cursor) {
            "Resume input or execution position changed before binding"
        }
        check(request.messages == plan.history) { "Request and resume plan disagree" }
        return bind(request, plan.replyNodeId)
    }

    fun bind(request: AgentRequest, nodeId: Uuid): AgentRequest {
        val saved = snapshot()
        replyNodeId = saved.replyNodeId?.let(Uuid::parse) ?: nodeId
        val definitions = request.tools.uniqueToolRegistrations().mapValues { (_, tool) ->
            listOf(
                tool.executionVersion,
                tool.name,
                tool.description,
                tool.parameters().toString(),
                me.rerere.rikkahub.BuildConfig.VERSION_CODE.toString()
            ).joinToString("\n")
        }
        val control = object : AgentControl {
            override suspend fun decide(context: AgentBoundaryContext): AgentDecision {
                saveCursor(context.cursor)
                blocked()?.let { return AgentDecision.Yield(it) }
                val expected = snapshot().toolDefinitions
                if (expected.isNotEmpty() && expected != definitions) {
                    store.addGate(ref, Gate("host.tool-version", GateKind.PLUGIN_FAULT))
                    return AgentDecision.Yield("Tool definitions changed")
                }
                if (expected.isEmpty()) store.update(ref) { it.copy(toolDefinitions = definitions) }
                if (context.cursor.boundary == AgentBoundary.BeforeTool) {
                    val message = checkNotNull(context.message)
                    val tool = message.getTools().single { it.toolCallId == context.cursor.toolCallId }
                    snapshot().approvalFailure(message, tool)?.let {
                        store.addGate(ref, Gate("host.tool-approval-binding", GateKind.PLUGIN_FAULT))
                        return AgentDecision.Yield(it)
                    }
                }
                val operation = policy(context, ref)
                if (operation != null) {
                    pending =
                        session.state.value.operationCall(
                            operation.definition,
                            operation.input,
                            operation.commandId,
                            ref
                        )
                    store.update(ref) {
                        it.copy(
                            state = RunControl.addGate(
                                it.state,
                                Gate(operation.commandId, GateKind.OPERATION_DEPENDENCY)
                            ),
                            pendingOperation = pending,
                        )
                    }
                    return AgentDecision.Yield(operation.commandId)
                }
                preparedFrame = snapshot().state.frameRevision
                return AgentDecision.Proceed
            }

            override suspend fun prepareModel(step: Int, attempt: Int): String? =
                staleInput() ?: store.admit(ref, step, preparedFrame).reason

            override fun admitModel(step: Int, attempt: Int): String? = blocked() ?: staleInput()
                ?: if (snapshot().state.frameRevision != preparedFrame) "Prepared request is stale" else null

            override fun resumeBlock(): String? = blocked()

            override fun admitTool(message: UIMessage, tool: me.rerere.ai.ui.UIMessagePart.Tool): String? =
                blocked() ?: snapshot().approvalFailure(message, tool)
                    ?: if (snapshot().state.frameRevision != preparedFrame) "Tool admission changed" else null

            override suspend fun checkpoint(message: UIMessage, next: AgentCursor): AgentCheckpoint {
                var rejected: String? = null
                val committed = session.commitExtension { current ->
                    val ledger = current.executionLedger()
                    val saved = ledger.runs.getValue(ref.runId)
                    require(next.nextStep in saved.nextStep..saved.state.stepsUsed)
                    if (message.getTools().any { it.isPending }) {
                        val previous = current.messageNodes.flatMap { it.messages }.single { it.id == message.id }
                        check(saved.toolDefinitions == definitions && saved.state.status == RunStatus.ACTIVE)
                        check(
                            previous.getTools().map { ToolCallStamp(it.toolCallId, it.toolName, it.input) } ==
                                message.getTools().map { ToolCallStamp(it.toolCallId, it.toolName, it.input) }
                        ) {
                            "Approval input changed before publication"
                        }
                        check(previous.getTools().zip(message.getTools()).all { (before, after) ->
                            before.startedAt == after.startedAt && before.output == after.output &&
                                before.approvalId == after.approvalId &&
                                (before.approvalState == after.approvalState ||
                                    (before.approvalState == me.rerere.ai.ui.ToolApprovalState.Auto && after.isPending))
                        }) { "Approval authority changed before publication" }
                    }
                    var permits = saved.consumedToolPermits
                    if (next.boundary == AgentBoundary.BeforeTool && next.toolCallId != null) {
                        val part = message.getTools().single { it.toolCallId == next.toolCallId }
                        if (part.startedAt != null && !part.isExecuted) {
                            val previous = current.messageNodes.flatMap { it.messages }
                                .single { it.id == message.id }.getTools().single { it.toolCallId == part.toolCallId }
                            val key = "${message.id}/${part.toolCallId}"
                            rejected = when {
                                previous.input != part.input || previous.toolName != part.toolName ||
                                    previous.approvalState != part.approvalState || previous.startedAt != null ->
                                    "Tool approval or input changed before effect"

                                saved.state.status != RunStatus.ACTIVE || saved.state.gates.isNotEmpty() ||
                                    saved.state.frameRevision != preparedFrame -> "Tool admission changed"

                                saved.toolDefinitions != definitions -> "Tool implementation changed"

                                saved.approvalFailure(message, part) != null -> saved.approvalFailure(message, part)

                                key in permits -> "Tool permit already consumed"

                                else -> null
                            }
                            if (rejected != null) {
                                val waiting = saved.copy(
                                    state = RunControl.addGate(
                                        saved.state,
                                        Gate("host.tool-admission", GateKind.PLUGIN_FAULT)
                                    )
                                )
                                return@commitExtension current.withExecutionLedger(
                                    ledger.copy(runs = ledger.runs + (ref.runId to waiting))
                                )
                            }
                            permits = permits + (key to listOf(
                                part.toolName,
                                part.input,
                                part.approvalState.toString(),
                                definitions[part.toolName]
                            ).joinToString("\n"))
                        }
                    }
                    val checkpoint = saved.copy(
                        consumedToolPermits = permits,
                        boundary = next.boundary.name,
                        nextStep = next.nextStep,
                        toolCallId = next.toolCallId,
                        messageId = next.messageId,
                        toolBatch = next.toolBatch.map { ToolCallStamp(it.id, it.name, it.input) },
                        inputIds = (saved.inputIds + message.id.toString()).distinct(),
                        replyNodeId = replyNodeId.toString(),
                    )
                    current.upsertMessage(replyNodeId, message)
                        .withExecutionLedger(ledger.copy(runs = ledger.runs + (ref.runId to checkpoint)))
                        .withToolApprovalRequests(ref, message.id)
                }
                rejected?.let { return AgentCheckpoint.Rejected(it) }
                cursor = next
                val confirmedMessage = committed.messageNodes.flatMap { it.messages }.single { it.id == message.id }
                return AgentCheckpoint.Committed(confirmedMessage)
            }
        }
        return request.copy(
            maxSteps = saved.state.maxSteps,
            completedSteps = saved.nextStep,
            resumeCursor = cursor,
            control = control,
        )
    }

    fun hooks(base: AgentHooks): AgentHooks = object : AgentHooks by base {
        override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
            val current = session.state.value
            preparedIds = messages.map { it.id.toString() }
            preparedSources = messages.map { it.sourceStamp() }
            return base.prepareStep(current.projectedContext(messages))
        }
    }

    private fun staleInput(): String? {
        val current = session.state.value
        val sources = current.pluginSources().associateBy { it.id }
        val boundIds = runCatching { current.runInputMessages(ref.runId).map { it.id.toString() } }.getOrNull()
        return if (boundIds != preparedIds || preparedSources.any { sources[it.id] != it }) {
            "Request source or bound input path changed"
        } else {
            null
        }
    }

    suspend fun accept(event: AgentEvent) {
        when (event) {
            is AgentEvent.Yielded -> {
                cursor = event.cursor
                saveCursor(event.cursor)
            }

            is AgentEvent.Finished -> {
                if (event.reason is AgentFinishReason.AwaitingApproval) {
                    // Publication, handles, gate and cursor were confirmed together by checkpoint().
                    check(event.message.getTools().filter { it.isPending }.all { it.approvalId != null })
                } else {
                    store.update(ref) { old ->
                        old.copy(
                            nextStep = event.completedSteps,
                            state = old.state.copy(
                                status = RunStatus.COMPLETED,
                                revision = old.state.revision + 1,
                                frameRevision = old.state.frameRevision + 1
                            )
                        )
                    }
                }
            }

            is AgentEvent.BudgetExhausted -> store.update(ref) { old ->
                old.copy(state = old.state.copy(
                    status = RunStatus.COMPLETED,
                    revision = old.state.revision + 1,
                    frameRevision = old.state.frameRevision + 1
                ))
            }

            else -> Unit
        }
    }

    suspend fun operationFinished() {
        val call = checkNotNull(pending)
        val current = session.state.value
        val saved = snapshot()
        val ledger = current.executionLedger()
        val inserted = ledger.operations[call.id]?.adoptedMessageIds ?: ledger.messageLineages.filterValues {
            it == call.frame.map { stamp -> stamp.id }
        }.keys
        val included = (saved.inputIds + inserted).toSet()
        val inputIds = current.messageNodes.mapNotNull { node ->
            node.messages.singleOrNull { it.id.toString() in included }?.id?.toString()
        }
        check(inputIds.size == included.size) { "Operation result path is missing or ambiguous" }
        val sameReply = inputIds.lastOrNull() == cursor?.messageId
        store.update(ref) {
            it.copy(
                inputIds = inputIds,
                replyNodeId = if (sameReply) it.replyNodeId else null,
                state = RunControl.resolveGate(it.state, call.id),
                pendingOperation = null,
                boundary = if (sameReply) it.boundary else AgentBoundary.BeforeModel.name,
                messageId = if (sameReply) it.messageId else null,
                toolBatch = if (sameReply) it.toolBatch else emptyList(),
                toolCallId = if (sameReply) it.toolCallId else null,
            )
        }
        pending = null
        if (!sameReply) cursor = null
    }

    suspend fun cancel() = session.cancelPluginRuns()

    private suspend fun saveCursor(value: AgentCursor) {
        store.update(ref) { old ->
            require(value.nextStep in old.nextStep..old.state.stepsUsed)
            old.copy(
                boundary = value.boundary.name,
                nextStep = value.nextStep,
                toolCallId = value.toolCallId,
                messageId = value.messageId,
                toolBatch = value.toolBatch.map { ToolCallStamp(it.id, it.name, it.input) }
            )
        }
    }

    companion object {
        suspend fun open(
            session: ConversationSession,
            history: List<UIMessage>,
            initialReplyNodeId: Uuid? = null,
            policy: suspend (AgentBoundaryContext, RunRef) -> BoundaryOperation?,
        ): SessionAgentRun {
            val branch =
                history.lastOrNull { it.role == MessageRole.USER && !it.isContextCheckpoint }?.id?.toString() ?: "draft"
            val ledger = session.state.value.executionLedger()
            val active = ledger.runs.values.filter { it.state.status == RunStatus.ACTIVE }
            check(active.size <= 1) { "Multiple active runs require explicit reconciliation" }
            // Input arrival and UI branch selection never imply cancel/resume of a logical run.
            val previous = active.singleOrNull()
            val store = SessionRunStore(session)
            val saved = if (previous != null) {
                previous
            } else {
                val ref = RunRef(
                    session.id.toString(),
                    branch,
                    Uuid.random().toString(),
                    session.incarnation.toLong()
                )
                store.create(RunCheckpoint(
                    RunState(ref, 256, 1024),
                    AgentBoundary.BeforeModel.name,
                    0,
                    inputIds = history.map { it.id.toString() },
                    replyNodeId = initialReplyNodeId?.toString(),
                ))
            }
            return SessionAgentRun(session, saved.state.ref, policy).also { controller ->
                if (previous != null) {
                    controller.pending = saved.pendingOperation
                    controller.cursor = AgentCursor(
                        AgentBoundary.valueOf(saved.boundary),
                        saved.nextStep,
                        saved.toolCallId,
                        saved.messageId,
                        saved.toolBatch.map {
                            AgentToolBinding(it.id, it.name, it.input)
                        }
                    )
                    if (saved.state.stepsUsed > saved.nextStep ||
                        session.state.value.messageNodes.flatMap { it.messages }
                            .firstOrNull { it.id.toString() == saved.messageId }?.getTools().orEmpty()
                            .any { !it.isExecuted && it.startedAt != null }) {
                        store.addGate(saved.state.ref, Gate("host.recovery", GateKind.PLUGIN_FAULT))
                    }
                    saved.pendingOperation?.let { call ->
                        if (ledger.operations[call.id]?.status == AttemptStatus.SETTLED) controller.operationFinished()
                    }
                }
            }
        }
    }
}
