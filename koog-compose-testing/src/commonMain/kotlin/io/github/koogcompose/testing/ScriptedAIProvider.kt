package io.github.koogcompose.testing

import io.github.koogcompose.session.AIProvider
import io.github.koogcompose.session.AIResponseChunk
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.session.ChatMessage
import io.github.koogcompose.session.KoogComposeContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/**
 * A testing provider that allows developers to script a sequence of AI responses.
 * Essential for deterministic testing of UI and orchestration logic in CMP.
 */
public class ScriptedAIProvider(
    private val steps: List<Step>,
    private val fallback: Step = Step.Text("Demo response")
) : AIProvider {
    private var nextStepIndex: Int = 0

    override fun <S> stream(
        context: KoogComposeContext<S>,
        history: List<ChatMessage>,
        systemPrompt: String,
        attachments: List<Attachment>
    ): Flow<AIResponseChunk> = flow {
        val currentStep = if (nextStepIndex < steps.size) {
            steps[nextStepIndex++]
        } else {
            fallback
        }

        currentStep.toChunks(context).forEach { emit(it) }
        emit(AIResponseChunk.End)
    }

    public sealed class Step {
        /** Simply returns text. */
        public data class Text(
            val text: String,
            val chunkSize: Int = text.length.coerceAtLeast(1)
        ) : Step()

        /** Simulates a tool call from the AI. */
        public data class ToolCall(
            val toolCallId: String,
            val toolName: String,
            val args: JsonObject
        ) : Step()

        /** Validates the current orchestration state before responding. */
        public data class PhaseValidation(
            val expectedPhase: String,
            val onMatch: Step,
            val onMismatch: Step? = null
        ) : Step()

        /** Returns a custom sequence of raw chunks. */
        public data class Sequence(
            val chunks: List<AIResponseChunk>
        ) : Step()
    }
}

private fun ScriptedAIProvider.Step.toChunks(context: KoogComposeContext<*>): List<AIResponseChunk> = when (this) {
    is ScriptedAIProvider.Step.Text -> {
        val parts = text.chunked(chunkSize.coerceAtLeast(1))
        if (parts.size <= 1) {
            listOf(AIResponseChunk.TextComplete(text))
        } else {
            parts.map(AIResponseChunk::TextDelta)
        }
    }

    is ScriptedAIProvider.Step.ToolCall -> listOf(
        AIResponseChunk.ToolCallRequest(
            toolCallId = toolCallId,
            toolName = toolName,
            args = args
        )
    )

    is ScriptedAIProvider.Step.PhaseValidation -> {
        if (context.activePhaseName == expectedPhase) {
            onMatch.toChunks(context)
        } else {
            onMismatch?.toChunks(context) ?: listOf(
                AIResponseChunk.Error(
                    "Phase validation failed: Expected phase '$expectedPhase' but active phase was '${context.activePhaseName ?: "none"}'"
                )
            )
        }
    }

    is ScriptedAIProvider.Step.Sequence -> chunks
}
