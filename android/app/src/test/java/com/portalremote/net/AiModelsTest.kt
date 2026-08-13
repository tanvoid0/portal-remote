package com.portalremote.net

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiModelsTest {
    @Test
    fun `parses providers, models and the current selection`() {
        val catalog = AiCatalog.parse(
            JSONObject(
                """
                {"current":{"provider":"ollama","model":"llama3.1:8b"},
                 "providers":[{"id":"ollama","configured":true},{"id":"gemini","configured":false}],
                 "models":[{"id":"llama3.1:8b","provider":"ollama","configured":true},
                           {"id":"gemini-2.0-flash","provider":"gemini","configured":false}]}
                """,
            ),
        )

        assertEquals("ollama", catalog.currentProvider)
        assertEquals("llama3.1:8b", catalog.currentModel)
        assertEquals(listOf(AiProvider("ollama", true), AiProvider("gemini", false)), catalog.providers)
        assertEquals(
            listOf(AiModel("llama3.1:8b", "ollama", true), AiModel("gemini-2.0-flash", "gemini", false)),
            catalog.models,
        )
    }

    @Test
    fun `an unset current is null, not an empty string`() {
        // "" and "not chosen yet" must read differently to the picker, or the zero-setup
        // state (docs/phase7-assistant.md §9) shows as an empty-looking chip instead of
        // "PC decides".
        val catalog = AiCatalog.parse(JSONObject("""{"current":{"provider":"","model":""}}"""))
        assertNull(catalog.currentProvider)
        assertNull(catalog.currentModel)
    }

    @Test
    fun `missing providers and models are empty lists rather than a crash`() {
        val catalog = AiCatalog.parse(JSONObject("{}"))
        assertEquals(emptyList(), catalog.providers)
        assertEquals(emptyList(), catalog.models)
    }

    @Test
    fun `withCurrent updates the selection and leaves the lists alone`() {
        val catalog = AiCatalog(null, null, listOf(AiProvider("ollama", true)), listOf(AiModel("m", "ollama", true)))
        val picked = catalog.withCurrent("ollama", "m")
        assertEquals("ollama", picked.currentProvider)
        assertEquals("m", picked.currentModel)
        assertEquals(catalog.providers, picked.providers)
        assertEquals(catalog.models, picked.models)
    }
}
