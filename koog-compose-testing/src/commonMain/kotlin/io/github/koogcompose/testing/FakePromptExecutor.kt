package io.github.koogcompose.testing

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import io.github.koogcompose.phase.PhaseRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock

/**
 * Deterministic [PromptExecutor] for unit tests.
 *
 * It drives the real Koog graph runtime with scripted assistant responses or tool calls,
 * letting tests prove phase transitions, tool execution, and shared state mutation without
 * hitting a live model.
 */
public class FakePromptExecutor internal constructor(
    private val rules: List<Rule>,
    private val fallback: Rule?,
    private val phaseCatalog: PhaseCatalog,
    initialPhaseName: String?
) : PromptExecutor() {

    private val invocations: MutableList<Invocation> = mutableListOf()
    private val emittedToolCalls: MutableList<ToolCallRecord> = mutableListOf()
    private val observedToolResults: MutableList<ToolResultRecord> = mutableListOf()

    private var activeRule: ActiveRule? = null
    private var turnIndex: Int = 0
    private var observedToolResultCount: Int = 0
    private val initialPhaseName: String? = initialPhaseName
    private var confirmedPhaseName: String? = initialPhaseName
    private var pendingTransitionTarget: String? = null

    /**
     * Full prompt-execution transcript captured during the test run.
     */
    public val transcript: Transcript
        get() = Transcript(
            invocations = invocations.toList(),
            toolCalls = emittedToolCalls.toList(),
            toolResults = observedToolResults.toList()
        )

    /**
     * The most recent phase confirmed by the active tool scope or pending transition.
     */
    public val currentPhaseName: String?
        get() = confirmedPhaseName ?: pendingTransitionTarget

    /**
     * Clears all scripted state and captured transcript for a fresh test run.
     */
    public fun reset(): Unit {
        invocations.clear()
        emittedToolCalls.clear()
        observedToolResults.clear()
        activeRule = null
        turnIndex = 0
        observedToolResultCount = 0
        confirmedPhaseName = initialPhaseName
        pendingTransitionTarget = null
    }

    /**
     * Forces the fake executor to treat subsequent turns as running in [phaseName].
     */
    public fun forcePhase(phaseName: String?): Unit {
        confirmedPhaseName = phaseName
        pendingTransitionTarget = null
        activeRule = null
    }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): List<Message.Response> {
        return listOf(nextResponse(prompt, model, tools))
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = flow {
        when (val response = nextResponse(prompt, model, tools)) {
            is Message.Assistant -> {
                emit(StreamFrame.TextComplete(response.content))
                emit(StreamFrame.End(metaInfo = response.metaInfo))
            }

            is Message.Tool.Call -> {
                emit(
                    StreamFrame.ToolCallComplete(
                        id = response.id,
                        name = response.tool,
                        content = response.content
                    )
                )
                emit(StreamFrame.End(metaInfo = response.metaInfo))
            }

            is Message.Reasoning -> {
                emit(StreamFrame.ReasoningComplete(text = listOf(response.content)))
                emit(StreamFrame.End(metaInfo = response.metaInfo))
            }
        }
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        return ModerationResult(isHarmful = false, categories = emptyMap())
    }

    override fun close(): Unit = Unit

    private fun nextResponse(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Response {
        val availableToolNames = tools.map(ToolDescriptor::name).toSet()
        val resolvedPhase = phaseCatalog.resolvePhase(availableToolNames)
        if (resolvedPhase != null) {
            confirmedPhaseName = resolvedPhase
            if (pendingTransitionTarget == resolvedPhase) {
                pendingTransitionTarget = null
            }
        }

        recordToolResults(prompt)

        val latestUserMessage = prompt.messages.lastOrNull { message -> message is Message.User }?.content
        val active = if (activeRule != null && activeRule?.remainingSteps?.isNotEmpty() == true) {
            val currentRule = requireNotNull(activeRule)
            if (latestUserMessage != null && currentRule.userMessage != latestUserMessage) {
                error(
                    "koog-compose-testing: Active scripted turn for '${currentRule.userMessage}' was interrupted " +
                        "by new user input '$latestUserMessage'."
                )
            }
            currentRule
        } else {
            activeRule = null
            val matchedRule = rules.firstOrNull { rule ->
                rule.matches(
                    userMessage = latestUserMessage,
                    phaseName = currentPhaseName
                )
            } ?: fallback ?: error(
                "koog-compose-testing: No scripted response matched user message " +
                    "'${latestUserMessage ?: "<none>"}' in phase '${currentPhaseName ?: "<none>"}'."
            )

            ActiveRule(
                userMessage = latestUserMessage,
                source = matchedRule,
                remainingSteps = matchedRule.steps.toMutableList(),
                turnIndex = ++turnIndex
            ).also { activeRule = it }
        }

        val step = active.remainingSteps.removeFirstOrNull()
            ?: error(
                "koog-compose-testing: Script for '${active.userMessage ?: "<no user message>"}' was exhausted " +
                    "before the agent finished the turn. Add a final respondWith(...) step."
            )

        val invocation = Invocation(
            turnIndex = active.turnIndex,
            latestUserMessage = latestUserMessage,
            detectedPhaseName = currentPhaseName,
            availableToolNames = availableToolNames,
            prompt = prompt,
            modelId = model.id
        )
        invocations += invocation

        return when (step) {
            is Step.AssistantText -> {
                if (active.remainingSteps.isEmpty()) {
                    activeRule = null
                }
                Message.Assistant(step.text, ResponseMetaInfo.create(Clock.System))
            }

            is Step.ToolCall -> {
                require(step.toolName in availableToolNames) {
                    "koog-compose-testing: Script tried to call tool '${step.toolName}' while only " +
                        "${availableToolNames.sorted()} were available in phase '${currentPhaseName ?: "<none>"}'."
                }
                emittedToolCalls += ToolCallRecord(
                    turnIndex = active.turnIndex,
                    toolCallId = step.toolCallId,
                    toolName = step.toolName,
                    args = step.args
                )
                if (step.toolName.startsWith("transition_to_")) {
                    pendingTransitionTarget = step.toolName.removePrefix("transition_to_")
                }
                Message.Tool.Call(
                    id = step.toolCallId,
                    tool = step.toolName,
                    content = step.args.toString(),
                    metaInfo = ResponseMetaInfo.create(Clock.System)
                )
            }
        }
    }

    private fun recordToolResults(prompt: Prompt): Unit {
        val toolResultsInPrompt = prompt.messages.filterIsInstance<Message.Tool.Result>()
        if (toolResultsInPrompt.size <= observedToolResultCount) {
            return
        }

        toolResultsInPrompt
            .drop(observedToolResultCount)
            .forEach { result ->
                val flags = parseToolResultFlags(result.content)
                observedToolResults += ToolResultRecord(
                    toolCallId = result.id,
                    toolName = result.tool,
                    content = result.content,
                    denied = flags.denied,
                    failed = flags.failed
                )
            }

        observedToolResultCount = toolResultsInPrompt.size
    }

    /**
     * Immutable snapshot of what the fake executor observed during a test run.
     */
    public data class Transcript(
        val invocations: List<Invocation>,
        val toolCalls: List<ToolCallRecord>,
        val toolResults: List<ToolResultRecord>
    )

    /**
     * One LLM invocation captured by the fake executor.
     */
    public data class Invocation(
        val turnIndex: Int,
        val latestUserMessage: String?,
        val detectedPhaseName: String?,
        val availableToolNames: Set<String>,
        val prompt: Prompt,
        val modelId: String
    )

    /**
     * A scripted tool call emitted by the fake executor.
     */
    public data class ToolCallRecord(
        val turnIndex: Int,
        val toolCallId: String?,
        val toolName: String,
        val args: JsonObject
    )

    /**
     * A tool result observed in prompt history after Koog executed a tool.
     */
    public data class ToolResultRecord(
        val toolCallId: String?,
        val toolName: String,
        val content: String,
        val denied: Boolean,
        val failed: Boolean
    )

    /**
     * Builder for scripted fake-executor conversations.
     */
    public class Builder {
        private val rules: MutableList<RuleBuilder> = mutableListOf()
        private var fallback: RuleBuilder? = null

        /**
         * Registers a scripted turn for an exact user message.
         *
         * Optionally scope the turn to a specific phase when the same user text can appear
         * in different parts of the routine.
         */
        public fun on(message: String, phase: String? = null): RuleBuilder {
            return RuleBuilder(message = message, phase = phase).also(rules::add)
        }

        /**
         * Registers and configures a scripted turn for an exact user message.
         */
        public fun on(
            message: String,
            phase: String? = null,
            block: RuleBuilder.() -> Unit
        ): Unit {
            on(message = message, phase = phase).apply(block)
        }

        /**
         * Registers a fallback script used when no exact [on] rule matches.
         */
        public fun fallback(): RuleBuilder {
            return RuleBuilder(message = null, phase = null).also { fallback = it }
        }

        /**
         * Registers and configures a fallback script used when no exact [on] rule matches.
         */
        public fun fallback(block: RuleBuilder.() -> Unit): Unit {
            fallback().apply(block)
        }

        internal fun build(
            phaseCatalog: PhaseCatalog,
            initialPhaseName: String?
        ): FakePromptExecutor {
            return FakePromptExecutor(
                rules = rules.map(RuleBuilder::build),
                fallback = fallback?.build(),
                phaseCatalog = phaseCatalog,
                initialPhaseName = initialPhaseName
            )
        }
    }
}

private data class ToolResultFlags(
    val denied: Boolean,
    val failed: Boolean
)

private fun parseToolResultFlags(content: String): ToolResultFlags {
    val parsedStatus = try {
        fakePromptExecutorJson
            .parseToJsonElement(content)
            .jsonObject["status"]
            ?.jsonPrimitive
            ?.contentOrNull
    } catch (_: Exception) {
        null
    }

    return when (parsedStatus) {
        "denied" -> ToolResultFlags(denied = true, failed = false)
        "error" -> ToolResultFlags(denied = false, failed = true)
        else -> ToolResultFlags(
            denied = content.startsWith("Denied:"),
            failed = content.startsWith("Error:")
        )
    }
}

private val fakePromptExecutorJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Builder for a single scripted user turn.
 */
public class RuleBuilder internal constructor(
    private val message: String?,
    private val phase: String?
) {
    private val steps: MutableList<Step> = mutableListOf()

    /**
     * Appends a final assistant text response.
     */
    public infix fun respondWith(text: String): Unit {
        assistant(text)
    }

    /**
     * Appends an assistant text response.
     */
    public fun assistant(text: String): Unit {
        steps += Step.AssistantText(text)
    }

    /**
     * Appends a tool call step.
     */
    public fun callTool(
        name: String,
        args: JsonObject = buildJsonObject { },
        toolCallId: String? = null
    ): Unit {
        steps += Step.ToolCall(
            toolCallId = toolCallId ?: nextToolCallId(name),
            toolName = name,
            args = args
        )
    }

    /**
     * Appends a phase transition tool call.
     */
    public fun transitionTo(
        phaseName: String,
        toolCallId: String? = null
    ): Unit {
        callTool(
            name = "transition_to_$phaseName",
            args = buildJsonObject { },
            toolCallId = toolCallId ?: "transition_to_${phaseName}_${steps.size + 1}"
        )
    }

    internal fun build(): Rule {
        require(steps.isNotEmpty()) {
            "koog-compose-testing: scripted turn for '${message ?: "<fallback>"}' must contain at least one step."
        }
        return Rule(
            message = message,
            phase = phase,
            steps = steps.toList()
        )
    }

    private fun nextToolCallId(name: String): String {
        val nextIndex = steps.count { step -> step is Step.ToolCall && step.toolName == name } + 1
        return "${name}_$nextIndex"
    }
}

/**
 * Creates a [FakePromptExecutor] without phase-aware assertions.
 *
 * Prefer [testPhaseSession] when you want deterministic phase-session tests; it automatically
 * binds the executor to the context's phase registry.
 */
public fun fakePromptExecutor(
    block: FakePromptExecutor.Builder.() -> Unit
): FakePromptExecutor {
    return FakePromptExecutor.Builder()
        .apply(block)
        .build(phaseCatalog = PhaseCatalog.Empty, initialPhaseName = null)
}

internal data class Rule(
    val message: String?,
    val phase: String?,
    val steps: List<Step>
) {
    internal fun matches(userMessage: String?, phaseName: String?): Boolean {
        val matchesMessage = message == null || message == userMessage
        val matchesPhase = phase == null || phase == phaseName
        return matchesMessage && matchesPhase
    }
}

internal sealed interface Step {
    data class AssistantText(val text: String) : Step
    data class ToolCall(
        val toolCallId: String?,
        val toolName: String,
        val args: JsonObject
    ) : Step
}

internal data class ActiveRule(
    val userMessage: String?,
    val source: Rule,
    val remainingSteps: MutableList<Step>,
    val turnIndex: Int
)

internal class PhaseCatalog private constructor(
    private val toolsByPhase: Map<String, Set<String>>
) {
    internal fun resolvePhase(toolNames: Set<String>): String? {
        return toolsByPhase
            .entries
            .singleOrNull { entry -> entry.value == toolNames }
            ?.key
    }

    internal companion object {
        internal val Empty: PhaseCatalog = PhaseCatalog(emptyMap())

        internal fun from(registry: PhaseRegistry): PhaseCatalog {
            if (registry.all.isEmpty()) {
                return Empty
            }

            return PhaseCatalog(
                registry.all.associate { phase ->
                    phase.name to (
                        phase.toolRegistry.all.map { tool -> tool.name }.toSet() +
                            phase.transitions.map { transition -> transition.toolName }.toSet()
                        )
                }
            )
        }
    }
}
