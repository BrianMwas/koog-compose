package io.github.koogcompose.demo

import io.github.koogcompose.session.*
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.tool.StatefulTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Demo tool that records the user's name into shared state.
 */
class GreetUserTool(
    override val stateStore: KoogStateStore<AppState>
) : StatefulTool<AppState>() {

    override val name: String = "GreetUser"
    override val description: String = "Record the user's name and greet them"
    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE

    override suspend fun execute(args: JsonObject): ToolResult {
        val userName = args["name"]?.jsonPrimitive?.contentOrNull ?: "Friend"
        stateStore.update { it.copy(userName = userName, greetingShown = true) }
        return ToolResult.Success("Hello, $userName! Welcome to koog-compose.")
    }
}

/**
 * Demo tool that tracks the current conversation topic in shared state.
 */
class TrackTopicTool(
    override val stateStore: KoogStateStore<AppState>
) : StatefulTool<AppState>() {

    override val name: String = "TrackTopic"
    override val description: String = "Track the current conversation topic"
    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE

    override suspend fun execute(args: JsonObject): ToolResult {
        val topic = args["topic"]?.jsonPrimitive?.contentOrNull ?: "general"
        stateStore.update { it.copy(lastTopic = topic) }
        return ToolResult.Success("Topic tracked: $topic")
    }
}

/**
 * Builds the koog-compose demo context with phases, tools, and config.
 * All in commonMain — runs on Android, iOS, and Desktop.
 *
 * Usage:
 * ```kotlin
 * val context = buildDemoContext()
 * val executor = context.createExecutor()
 * val session = PhaseSession(context, executor, "my_session", scope = viewModelScope)
 * ```
 */
fun buildDemoContext(): KoogDefinition<AppState> {
    val greetTool = GreetUserTool(KoogStateStore(AppState()))
    val trackTool = TrackTopicTool(KoogStateStore(AppState()))

    val result = koogCompose<AppState> {
        provider {
            openAI(apiKey = "your-api-key-here") {
                model = "gpt-4o-mini"
            }
        }

        initialState { AppState() }

        phases {
            phase("greeting", initial = true) {
                instructions {
                    """
                    You are a friendly assistant demo.
                    Greet the user and ask their name.
                    Once they tell you their name, use ${greetTool.ref} to record it.
                    After greeting, transition to the chat phase.
                    Use the transition tool "go to chat" when ready.
                    """.trimIndent()
                }
            }
            phase("chat") {
                instructions {
                    """
                    You are now in a general chat phase.
                    Be helpful and conversational.
                    """.trimIndent()
                }
            }
        }

        config {
            retry {
                maxAttempts = 2
                initialDelayMs = 500L
            }
        }
    }

    // Single-agent result — extract the context
    val context = result as KoogComposeContext<AppState>
    val stateStore = checkNotNull(context.stateStore)

    // Register tools with the actual stateStore and return enriched context
    return context
        .withTool(GreetUserTool(stateStore))
        .withTool(TrackTopicTool(stateStore))
}
