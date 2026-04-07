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





