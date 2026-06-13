package io.github.koogcompose.workflow

import io.github.koogcompose.layout.AgentLayoutDirective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingDirectiveAssemblerTest {

    @Test
    fun showDirectivePreviewsOnceSlotArrives() {
        val assembler = StreamingDirectiveAssembler()
        assembler.startCall(0)

        // Nothing renderable until directiveType + componentId + slotId have all streamed in.
        assertNull(assembler.consume(0, """{"directiveType":"Sh"""))
        assertNull(assembler.consume(0, """ow","componentId":"promo_ban"""))
        assertNull(assembler.consume(0, """ner",""")) // componentId value not yet closed on first fragment

        val preview = assembler.consume(0, """"slotId":"hero","reason":"greet"}""")
        assertTrue(preview is AgentLayoutDirective.ShowComponent)
        preview as AgentLayoutDirective.ShowComponent
        assertEquals("promo_banner", preview.componentId.value)
        assertEquals("hero", preview.slotId.value)
        assertEquals("greet", preview.reason)
    }

    @Test
    fun sameStateDoesNotRepreview() {
        val assembler = StreamingDirectiveAssembler()
        assembler.startCall(0)
        val first = assembler.consume(0, """{"directiveType":"Show","componentId":"a","slotId":"s"}""")
        assertTrue(first is AgentLayoutDirective.ShowComponent)
        // Trailing whitespace adds no new renderable state → no duplicate preview.
        assertNull(assembler.consume(0, "  \n"))
    }

    @Test
    fun hideDirectivePreviews() {
        val assembler = StreamingDirectiveAssembler()
        assembler.startCall(1)
        val preview = assembler.consume(1, """{"directiveType":"Hide","componentId":"promo","reason":"done"}""")
        assertTrue(preview is AgentLayoutDirective.HideComponent)
        preview as AgentLayoutDirective.HideComponent
        assertEquals("promo", preview.componentId.value)
    }

    @Test
    fun startCallResetsBufferBetweenTurns() {
        val assembler = StreamingDirectiveAssembler()
        assembler.startCall(0)
        assembler.consume(0, """{"directiveType":"Show","componentId":"a","slotId":"s"}""")

        // A fresh tool call at the same index must not see stale args from the previous one.
        assembler.startCall(0)
        assertNull(assembler.consume(0, "{\"directiveType\":\"Show\","))
        val preview = assembler.consume(0, "\"componentId\":\"b\",\"slotId\":\"s2\"}")
        assertTrue(preview is AgentLayoutDirective.ShowComponent)
        preview as AgentLayoutDirective.ShowComponent
        assertEquals("b", preview.componentId.value)
        assertEquals("s2", preview.slotId.value)
    }
}
