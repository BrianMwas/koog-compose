package io.github.koogcompose.phase

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import io.modelcontextprotocol.kotlin.sdk.Role.assistant
import io.modelcontextprotocol.kotlin.sdk.Role.user
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * Wraps a PromptExecutor and intercepts execute() for phases
 * that declare a PhaseOutput.
 *
 * On each call it:
 *  1. Appends JSON schema + serialized examples to the prompt system block.
 *     This fixes Koog's assumption that native JSON Schema providers
 *     don't need example priming — they do.
 *  2. Calls the underlying executor and parses the response as O.
 *  3. Retries up to PhaseOutput.retries on SerializationException.
 */
internal class StructuredPhaseExecutor<O>(
    private val delegate: PromptExecutor,
    private val output: PhaseOutput<O>,
    private val model: LLModel,
) : PromptExecutor() {

    suspend fun executeStructured(basePrompt: Prompt): O {
        val primedPrompt = basePrompt.withStructureHint(output)
        var lastError: Exception? = null
        // On validation failure, re-execute with the same prompt.
        // The retry loop relies on the LLM seeing the same schema hint
        // and attempting a different response.
        repeat(output.retries) {
            try {
                val responses = delegate.execute(primedPrompt, model)
                val raw = responses
                    .filterIsInstance<Message.Assistant>()
                    .joinToString("") { it.content }
                return output.parse(raw)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            "Structured output failed after ${output.retries} attempts: ${lastError?.message}",
            lastError
        )
    }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): List<Message.Response> {
        return delegate.execute(prompt, model, tools)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        return delegate.executeStreaming(prompt, model, tools)
    }

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel
    ): ModerationResult {
        return delegate.moderate(prompt, model)
    }


    override suspend fun models() = delegate.models()
    override fun close() {
        return delegate.close()
    }
}

/** Appends schema + examples to the system block, creating one if absent. */
private fun <O> Prompt.withStructureHint(output: PhaseOutput<O>): Prompt {
    val hint = buildString {
        appendLine("\n## Required output format (schema v${output.version})")
        appendLine("Respond ONLY with a JSON object matching this schema:")
        appendLine(Json.encodeToString(output.structure.schema.schema))
        val examples = output.structure.examples
        if (examples.isNotEmpty()) {
            appendLine("\n## Example output")
            examples.forEach { appendLine(output.structure.pretty(it)) }
        }
        appendLine("\nOutput must start with { and end with }. No backticks.")
    }
    return prompt(id) {
        messages.forEach { msg ->
            when (msg) {
                is Message.System    -> system(msg.content + hint)
                is Message.User      -> user { +msg.content }
                is Message.Assistant -> assistant(msg.content)
                else                  -> Unit
            }
        }
    }
}
