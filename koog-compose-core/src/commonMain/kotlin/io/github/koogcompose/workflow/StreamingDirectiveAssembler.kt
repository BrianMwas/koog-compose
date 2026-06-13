package io.github.koogcompose.workflow

import io.github.koogcompose.layout.AgentLayoutDirective
import io.github.koogcompose.layout.ComponentId
import io.github.koogcompose.layout.DirectiveId
import io.github.koogcompose.layout.Position
import io.github.koogcompose.layout.SlotId
import kotlin.time.Clock

/**
 * Accumulates streamed tool-call argument fragments for [EmitLayoutDirectiveTool.TOOL_NAME]
 * and, as soon as enough fields have arrived, produces a best-effort *preview*
 * [AgentLayoutDirective] so the UI can animate a component in *as the JSON streams* —
 * before the tool call completes and the authoritative directive is committed.
 *
 * It is deliberately tolerant of incomplete JSON: it scans the partial buffer for
 * complete `"field":"value"` pairs and only previews the directive types whose target
 * is renderable from a few fields ([AgentLayoutDirective.ShowComponent] /
 * [AgentLayoutDirective.HideComponent]). Reorder/Swap/Lock wait for the commit.
 *
 * Safety: the layout reducer keys component presence on `componentId`, so a preview and
 * its later committed directive converge idempotently even when their correlation ids
 * differ (the model often omits correlationId and the tool generates one at commit time).
 */
internal class StreamingDirectiveAssembler {
    private val buffers = mutableMapOf<Int, StringBuilder>()
    private val lastPreviewKey = mutableMapOf<Int, String>()

    /** Marks the start of a fresh tool call at [index], clearing any prior buffer. */
    fun startCall(index: Int) {
        buffers[index] = StringBuilder()
        lastPreviewKey.remove(index)
    }

    /**
     * Appends [delta] for the tool call at [index] and returns a preview directive when the
     * accumulated arguments first become renderable (and on each subsequent meaningful change),
     * or null when there is nothing new to preview yet.
     */
    fun consume(index: Int, delta: String?): AgentLayoutDirective? {
        if (delta.isNullOrEmpty()) return null
        val buf = buffers.getOrPut(index) { StringBuilder() }
        buf.append(delta)
        val raw = buf.toString()

        val type = extractString(raw, "directiveType") ?: return null
        val componentId = extractString(raw, "componentId") ?: return null
        val slotId = extractString(raw, "slotId")
        val reason = extractString(raw, "reason")
        val correlationId = extractString(raw, "correlationId") ?: "stream-preview-$index"

        val directive = when (type) {
            "Show" -> {
                // Show needs a slot before we can place anything.
                val slot = slotId ?: return null
                AgentLayoutDirective.ShowComponent(
                    componentId = ComponentId(componentId),
                    slotId = SlotId(slot),
                    position = Position.End,
                    correlationId = DirectiveId(correlationId),
                    issuedAt = Clock.System.now(),
                    reason = reason,
                )
            }
            "Hide" -> AgentLayoutDirective.HideComponent(
                componentId = ComponentId(componentId),
                slotId = slotId?.let { SlotId(it) },
                correlationId = DirectiveId(correlationId),
                issuedAt = Clock.System.now(),
                reason = reason,
            )
            else -> return null
        }

        val key = "$type:$componentId:$slotId"
        if (lastPreviewKey[index] == key) return null
        lastPreviewKey[index] = key
        return directive
    }

    /**
     * Extracts a *complete* string value for [field] from partial JSON, or null if the field
     * is absent or its closing quote hasn't streamed in yet.
     */
    private fun extractString(json: String, field: String): String? {
        val marker = "\"$field\""
        val markerIndex = json.indexOf(marker)
        if (markerIndex < 0) return null
        var i = markerIndex + marker.length
        while (i < json.length && (json[i] == ' ' || json[i] == ':' || json[i] == '\t' || json[i] == '\n' || json[i] == '\r')) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val value = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> { value.append(json[i + 1]); i += 2 }
                c == '"' -> return value.toString()
                else -> { value.append(c); i++ }
            }
        }
        return null
    }
}
