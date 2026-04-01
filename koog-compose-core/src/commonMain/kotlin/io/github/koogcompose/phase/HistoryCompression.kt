package io.github.koogcompose.phase

import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import io.github.koogcompose.session.Concept
import io.github.koogcompose.session.HistoryCompression

/**
 * Maps koog-compose's [HistoryCompression] to Koog's [HistoryCompressionStrategy].
 *
 * Note: [HistoryCompression.RetrieveFactsFromHistory] has no direct Koog equivalent.
 * We approximate it by using WholeHistory compression with a concept-focused
 * summary prompt. The concepts are injected into the agent's system prompt
 * context before the compression node runs — see [PhaseStrategyBuilder].
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
            // Koog has no concept-extraction strategy — approximate with WholeHistory.
            // The concept keywords are surfaced in the compression prompt via
            // the phase instructions so the LLM knows what to preserve.
            HistoryCompressionStrategy.WholeHistory
    }




