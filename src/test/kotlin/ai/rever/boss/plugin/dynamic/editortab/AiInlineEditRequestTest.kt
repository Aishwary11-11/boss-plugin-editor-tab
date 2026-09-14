package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiInlineEditRequestTest {
    @Test
    fun `compose request uses gateway active default model`() {
        val request = AiInlineEditService.buildRequest("simplify", "val x = 1", "kotlin")

        assertEquals(emptyMap(), request.extras)
        assertNull(request.modelOverride)
        assertEquals(0f, request.temperature)
    }
}
