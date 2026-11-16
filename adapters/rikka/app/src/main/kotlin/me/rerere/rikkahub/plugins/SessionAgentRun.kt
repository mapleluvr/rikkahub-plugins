package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.RunControl

import dev.rikkahub.plugins.protocol.*
import me.rerere.ai.agent.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.service.ConversationSession
import kotlin.uuid.Uuid

data class BoundaryOperation(
    val definition: ConversationOperation,
    val input: kotlinx.serialization.json.JsonElement,
    val commandId: String
)

/** Adapts a logical persisted run to coroutine segments, without knowing individual operation semantics. */
class SessionAgentRun private constructor(
    private val session: ConversationSession,
    val ref: RunRef,
    private val policy: (AgentBoundaryContext, RunRef) -> BoundaryOperation?,
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

    fun bind(request: AgentRequest, nodeId: Uuid): AgentRequest {
        replyNodeId = nodeId
        val saved = snapshot()
        val control = object : AgentControl {
            override suspend fun decide(context: AgentBoundaryContext): AgentDecision {
                saveCursor(context.cursor)
                blocked()?.let { return AgentDecision.Yield(it) }
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
                            state = RunControl.addGate(it.state, Gate(operation.commandId, GateKind.OPERATION_DEPENDENCY)),
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

            override suspend fun commit(message: UIMessage) {
                session.commitExtension { it.upsertMessage(replyNodeId, message) }
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
            val sources = current.pluginSources().associateBy { it.id }
            preparedSources = preparedIds.map { requireNotNull(sources[it]) { "Request source is missing" } }
            return base.prepareStep(current.projectedContext(messages))
        }
    }

    private fun staleInput(): String? {
        val current = session.state.value
        val sources = current.pluginSources().associateBy { it.id }
        return if (current.currentMessages.take(preparedIds.size).map { it.id.toString() } != preparedIds ||
            preparedSources.any { sources[it.id] != it }) "Request source or selected branch changed" else null
    }

    suspend fun accept(event: AgentEvent) {
        when (event) {
            is AgentEvent.Yielded -> {
                cursor = event.cursor
                saveCursor(event.cursor)
            }

            is AgentEvent.Finished -> {
                if (event.reason is AgentFinishReason.AwaitingApproval) {
                    val next = event.message.cursor(AgentBoundary.ToolPreflight, event.completedSteps)
                    saveCursor(next)
                    store.addGate(ref, Gate(APPROVAL, GateKind.TOOL_APPROVAL))
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
        val sameReply = session.state.value.currentMessages.lastOrNull()?.id?.toString() == cursor?.messageId
        store.update(ref) {
            it.copy(
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

    suspend fun cancel() = store.cancel(ref)

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
        private const val APPROVAL = "host.tool-approval"
        suspend fun open(
            session: ConversationSession,
            history: List<UIMessage>,
            policy: (AgentBoundaryContext, RunRef) -> BoundaryOperation?,
        ): SessionAgentRun {
            val last = history.lastOrNull()
            val branch = history.lastOrNull { it.role == MessageRole.USER && !it.isContextCheckpoint }?.id?.toString() ?: "draft"
            val ledger = session.state.value.executionLedger()
            val previous = ledger.runs.values.singleOrNull {
                it.state.status == RunStatus.ACTIVE && (
                    (it.messageId != null && it.messageId == last?.id?.toString()) ||
                    ((it.messageId == null || it.pendingOperation != null) && it.state.ref.branchId == branch)
                )
            }
            val store = SessionRunStore(session)
            val saved = if (previous != null) {
                if (last?.getTools().orEmpty().none { it.isPending }) {
                    store.resolveGate(previous.state.ref, APPROVAL)
                } else {
                    previous
                }
            } else {
                for (old in ledger.runs.values.filter {
                    it.state.status == RunStatus.ACTIVE
                }) {
                    store.cancel(old.state.ref)
                }
                val ref = RunRef(
                    session.id.toString(),
                    branch,
                    Uuid.random().toString(),
                    session.incarnation.toLong()
                )
                store.create(RunCheckpoint(RunState(ref, 256, 1024), AgentBoundary.BeforeModel.name, 0))
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
                        last?.getTools().orEmpty().any { !it.isExecuted && it.startedAt != null }) {
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

private fun UIMessage.cursor(boundary: AgentBoundary, step: Int) = AgentCursor(
    boundary,
    step,
    messageId = id.toString(),
    toolBatch = getTools().map {
        AgentToolBinding(it.toolCallId, it.toolName, it.input)
    }
)
