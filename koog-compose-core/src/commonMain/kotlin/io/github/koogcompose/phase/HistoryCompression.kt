package io.github.koogcompose.phase

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.prompt.message.Message
import io.github.koogcompose.session.Concept
import io.github.koogcompose.session.FactType
import io.github.koogcompose.session.HistoryCompression

/**
 * Maps koog-compose's [HistoryCompression] sealed class to Koog's
 * internal [HistoryCompressionStrategy].
 *
 * This is the bridge that lets koog-compose's user-facing DSL drive
 * Koog's actual compression nodes at the graph level.
 */
internal fun HistoryCompression.toKoogStrategy(): HistoryCompressionStrategy =
    when (this) {
        is HistoryCompression.WholeHistory ->
            HistoryCompressionStrategy.WholeHistory

        is HistoryCompression.FromLastN ->
            HistoryCompressionStrategy.FromLastNMessages(n)

        is HistoryCompression.Chunked ->
            HistoryCompressionStrategy.Chunked(chunkSize)

        is HistoryCompression.RetrieveFactsFromHistory ->
            RetrieveFactsHistoryCompressionStrategy(concepts)
    }

/**
 * Custom implementation of [HistoryCompressionStrategy] that retrieves specific facts
 * from the conversation history based on provided concepts.
 */
internal class RetrieveFactsHistoryCompressionStrategy(
    private val concepts: List<Concept>
) : HistoryCompressionStrategy() {
    override suspend fun compress(
        llmSession: AIAgentLLMWriteSession,
        memoryMessages: List<Message>
    ) {
        val tldrMessages = with(llmSession) {
            // Internal implementation uses session methods to trigger a TLDR pass
            // focused on concept extraction.
            dropTrailingToolCalls()
            appendPrompt {
                user {
                    val instruction = buildString {
                        appendLine("Retrieve the following concepts from the history and summarize them as facts:")
                        concepts.forEach { concept ->
                            val typeLabel = if (concept.factType == FactType.SINGLE) "single" else "multiple"
                            appendLine("- ${concept.keyword}: ${concept.description} (Extract $typeLabel fact(s))")
                        }
                    }
                    content(instruction)
                }
            }
            listOf(requestLLMWithoutTools())
        }

        updateHistory(llmSession) { original ->
            composeMessageHistory(
                originalMessages = original,
                tldrMessages = tldrMessages,
                memoryMessages = memoryMessages
            )
        }
    }

    /**
     * Helper to update history since [AIAgentLLMWriteSession] might have 
     * different naming for history updates in the current Koog version.
     */
    private suspend fun updateHistory(
        session: AIAgentLLMWriteSession,
        block: (List<Message>) -> List<Message>
    ) {
        val current = session.getHistory()
        val updated = block(current)
        session.setHistory(updated)
    }
}
