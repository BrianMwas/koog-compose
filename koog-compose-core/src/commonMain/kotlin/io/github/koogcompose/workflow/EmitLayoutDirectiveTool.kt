package io.github.koogcompose.workflow

import io.github.koogcompose.layout.AgentLayoutDirective
import io.github.koogcompose.layout.ComponentId
import io.github.koogcompose.layout.ComponentProps
import io.github.koogcompose.layout.DirectiveId
import io.github.koogcompose.layout.LayoutDirectiveProcessor
import io.github.koogcompose.layout.LockMode
import io.github.koogcompose.layout.Position
import io.github.koogcompose.layout.SlotId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Agent-facing tool that bridges a tool-calling LLM (Gemma 4, Claude, etc.) to
 * [LayoutDirectiveProcessor]. This is the glue between your Koog dispatch loop and
 * the Layer 1 engine.
 *
 * ## Integration steps
 *
 * **Step 1 — advertise the tool to the model.**
 * Include [TOOL_SCHEMA_JSON] in your tool list when building the agent prompt. The
 * schema body is model-agnostic; wrap it in the function-calling format your provider
 * expects.
 *
 * **Step 2 — handle the tool call.**
 * When your dispatch loop sees a tool call named [TOOL_NAME]:
 * ```kotlin
 * val tool = EmitLayoutDirectiveTool(processor, runtime)
 * val result = tool.handle(call.argumentsJson)
 * sendToolResult(call.id, result.toJson())
 * ```
 *
 * **Step 3 — feed outcomes back.**
 * Collect [LayoutDirectiveProcessor.outcomes] and inject each outcome as a system note
 * into the next agent turn so the model can correct course on rejections/rewrites.
 */
public class EmitLayoutDirectiveTool(
    private val processor: LayoutDirectiveProcessor,
    private val runtime: WorkflowRuntime? = null,
    private val correlationIdGenerator: () -> String = ::defaultCorrelationId,
) {
    /**
     * Parses [rawArgsJson], validates against the current workflow phase, and submits
     * the directive to [processor]. Returns immediately (non-blocking); the actual
     * outcome arrives asynchronously via [LayoutDirectiveProcessor.outcomes].
     */
    public fun handle(rawArgsJson: String): ToolResult {
        val args = try {
            json.decodeFromString(EmitDirectiveArgs.serializer(), rawArgsJson)
        } catch (t: Throwable) {
            return ToolResult.Error("Could not parse arguments: ${t.message}")
        }

        val type = runCatching { DirectiveType.valueOf(args.directiveType) }.getOrNull()
            ?: return ToolResult.Error(
                "Unknown directiveType '${args.directiveType}'. Valid values: " +
                    DirectiveType.values().joinToString { it.name }
            )

        if (runtime != null && !runtime.isDirectivePermittedNow(type)) {
            return ToolResult.Error(
                "Directive type '${type.name}' is not permitted in the current phase " +
                    "'${runtime.currentPhase.value.value}'"
            )
        }

        val directive = try {
            buildDirective(type, args)
        } catch (t: Throwable) {
            return ToolResult.Error("Invalid directive arguments: ${t.message}")
        }

        processor.send(directive)
        return ToolResult.Submitted(correlationId = directive.correlationId.value)
    }

    // ── Directive construction ────────────────────────────────────────────────

    private fun buildDirective(type: DirectiveType, args: EmitDirectiveArgs): AgentLayoutDirective {
        val corrId = DirectiveId(args.correlationId ?: correlationIdGenerator())
        val now    = Clock.System.now()
        val props  = ComponentProps(args.props ?: emptyMap())

        return when (type) {
            DirectiveType.Show -> AgentLayoutDirective.ShowComponent(
                componentId   = ComponentId(req(args.componentId, "componentId")),
                slotId        = SlotId(req(args.slotId, "slotId")),
                position      = args.position?.toDomain() ?: Position.End,
                props         = props,
                correlationId = corrId,
                issuedAt      = now,
                reason        = args.reason,
            )
            DirectiveType.Hide -> AgentLayoutDirective.HideComponent(
                componentId   = ComponentId(req(args.componentId, "componentId")),
                slotId        = args.slotId?.let { SlotId(it) },
                correlationId = corrId,
                issuedAt      = now,
                reason        = args.reason,
            )
            DirectiveType.Reorder -> AgentLayoutDirective.ReorderComponents(
                slotId               = SlotId(req(args.slotId, "slotId")),
                orderedComponentIds  = req(args.orderedComponentIds, "orderedComponentIds")
                    .map { ComponentId(it) },
                correlationId        = corrId,
                issuedAt             = now,
                reason               = args.reason,
            )
            DirectiveType.Swap -> AgentLayoutDirective.SwapComponent(
                slotId            = SlotId(req(args.slotId, "slotId")),
                removeComponentId = ComponentId(req(args.removeComponentId, "removeComponentId")),
                insertComponentId = ComponentId(req(args.insertComponentId, "insertComponentId")),
                props             = props,
                correlationId     = corrId,
                issuedAt          = now,
                reason            = args.reason,
            )
            DirectiveType.Lock -> AgentLayoutDirective.LockComponent(
                componentId   = ComponentId(req(args.componentId, "componentId")),
                slotId        = SlotId(req(args.slotId, "slotId")),
                lockMode      = parseLockMode(req(args.lockMode, "lockMode")),
                correlationId = corrId,
                issuedAt      = now,
                reason        = args.reason,
            )
        }
    }

    private fun parseLockMode(raw: String): LockMode =
        runCatching { LockMode.valueOf(raw) }.getOrElse {
            error("Unknown lockMode '$raw'. Valid values: ${LockMode.values().joinToString { it.name }}")
        }

    // ── Result type ───────────────────────────────────────────────────────────

    public sealed class ToolResult {
        public abstract fun toJson(): String

        public data class Submitted(val correlationId: String) : ToolResult() {
            override fun toJson(): String =
                """{"status":"submitted","correlationId":"$correlationId"}"""
        }

        public data class Error(val message: String) : ToolResult() {
            override fun toJson(): String {
                val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
                return """{"status":"error","message":"$escaped"}"""
            }
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    public companion object {
        public const val TOOL_NAME: String = "emit_layout_directive"

        /**
         * JSON Schema for advertising this tool to a tool-calling LLM. The schema body
         * is provider-agnostic; wrap it in the function-calling envelope your provider
         * requires (Anthropic `tools`, OpenAI `functions`, Gemma local function call, etc.).
         */
        public val TOOL_SCHEMA_JSON: String = """
            {
              "name": "$TOOL_NAME",
              "description": "Modify the user interface by emitting a layout directive. Use this to show, hide, reorder, swap, or lock components in declared slots. Each call performs ONE directive. Always include a 'reason' field with a short justification.",
              "parameters": {
                "type": "object",
                "required": ["directiveType", "reason"],
                "properties": {
                  "directiveType": {
                    "type": "string",
                    "enum": ["Show", "Hide", "Reorder", "Swap", "Lock"],
                    "description": "The type of layout change to perform."
                  },
                  "componentId": { "type": "string", "description": "Target component id." },
                  "slotId":      { "type": "string", "description": "Target slot id." },
                  "position": {
                    "type": "object",
                    "description": "Where to insert the component. Defaults to End.",
                    "properties": {
                      "type":      { "type": "string", "enum": ["Start","End","Index","Before","After"] },
                      "index":     { "type": "integer" },
                      "reference": { "type": "string" }
                    }
                  },
                  "orderedComponentIds": {
                    "type": "array",
                    "items": { "type": "string" },
                    "description": "For Reorder: desired component order."
                  },
                  "removeComponentId": { "type": "string", "description": "For Swap: component to remove." },
                  "insertComponentId": { "type": "string", "description": "For Swap: component to insert." },
                  "lockMode": {
                    "type": "string",
                    "enum": ["ReadOnly", "Disabled", "Hidden"],
                    "description": "For Lock: how to restrict the component."
                  },
                  "props": {
                    "type": "object",
                    "additionalProperties": { "type": "string" },
                    "description": "Key-value props passed into the rendered component."
                  },
                  "reason": {
                    "type": "string",
                    "description": "Short justification for this change. Required."
                  },
                  "correlationId": {
                    "type": "string",
                    "description": "Optional caller-supplied id to match this directive to its outcome."
                  }
                }
              }
            }
        """.trimIndent()

        internal val json = Json { ignoreUnknownKeys = true }

        private fun defaultCorrelationId(): String =
            "dir-${Clock.System.now().toEpochMilliseconds()}-${(0..9999).random()}"

        private fun <T : Any> req(value: T?, name: String): T =
            value ?: error("Missing required field '$name'")
    }
}

// ── Serializable arg types ────────────────────────────────────────────────────

@Serializable
internal data class EmitDirectiveArgs(
    val directiveType: String,
    val componentId: String? = null,
    val slotId: String? = null,
    val position: PositionArg? = null,
    val orderedComponentIds: List<String>? = null,
    val removeComponentId: String? = null,
    val insertComponentId: String? = null,
    val lockMode: String? = null,
    val props: Map<String, String>? = null,
    val reason: String? = null,
    val correlationId: String? = null,
)

@Serializable
internal data class PositionArg(
    val type: String,
    val index: Int? = null,
    val reference: String? = null,
)

private fun PositionArg.toDomain(): Position = when (type) {
    "Start"  -> Position.Start
    "End"    -> Position.End
    "Index"  -> Position.Index(index ?: error("Position.Index requires 'index'"))
    "Before" -> Position.Before(ComponentId(reference ?: error("Position.Before requires 'reference'")))
    "After"  -> Position.After(ComponentId(reference ?: error("Position.After requires 'reference'")))
    else     -> error("Unknown Position type '$type'")
}
