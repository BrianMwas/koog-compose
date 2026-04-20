package io.github.koogcompose.event

import ai.koog.agents.features.eventHandler.feature.EventHandlerConfig
import io.github.koogcompose.tool.ToolResult
import kotlin.time.Clock

/**
 * Installs the Koog [EventHandler] feature into a FeatureContext block and maps
 * its callbacks to [EventHandlers.dispatch] calls.
 *
 * Call this directly inside the `installFeatures { }` block of an AIAgent.
 *
 * @param eventHandlers   The koog-compose event handler registry.
 * @param phaseName       Lambda returning the active phase name.
 * @param turnIdProvider  Lambda returning the current turn ID.
 */
internal fun EventHandlerConfig.installKoogEventHandlers(
    eventHandlers: EventHandlers,
    phaseName: () -> String?,
    turnIdProvider: () -> String,
) {
    onToolCallStarting { eventContext ->
        runCatching {
            eventHandlers.dispatch(
                KoogEvent.ToolCallRequested(
                    timestampMs = Clock.System.now().toEpochMilliseconds(),
                    turnId = turnIdProvider(),
                    phaseName = phaseName(),
                    toolCallId = null,
                    toolName = eventContext.toolName,
                    args = eventContext.toolArgs
                        .let { kotlinx.serialization.json.Json.parseToJsonElement(it.toString()) }
                        .let { it as? kotlinx.serialization.json.JsonObject }
                        ?: kotlinx.serialization.json.buildJsonObject { }
                )
            )
        }
    }

    onToolCallCompleted { eventContext ->
        runCatching {
            val result = ToolResult.Success(eventContext.toolResult.toString())
            eventHandlers.dispatch(
                KoogEvent.ToolExecutionCompleted(
                    timestampMs = Clock.System.now().toEpochMilliseconds(),
                    turnId = turnIdProvider(),
                    phaseName = phaseName(),
                    toolCallId = eventContext.toolCallId,
                    toolName = eventContext.toolName,
                    result = result,
                )
            )
        }
    }

    onAgentCompleted { eventContext ->
        runCatching {
            eventHandlers.dispatch(
                KoogEvent.TurnCompleted(
                    timestampMs = Clock.System.now().toEpochMilliseconds(),
                    turnId = turnIdProvider(),
                    phaseName = phaseName(),
                    assistantMessageId = eventContext.result?.toString() ?: "",
                    toolNames = emptyList()
                )
            )
        }
    }

    onAgentExecutionFailed { eventContext ->
        runCatching {
            eventHandlers.dispatch(
                KoogEvent.TurnFailed(
                    timestampMs = Clock.System.now().toEpochMilliseconds(),
                    turnId = turnIdProvider(),
                    phaseName = phaseName(),
                    message = eventContext.throwable.message ?: "Unknown error"
                )
            )
        }
    }
}
