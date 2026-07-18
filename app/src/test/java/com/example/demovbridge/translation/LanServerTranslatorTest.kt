package com.example.demovbridge.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LanServerTranslatorTest {
    @Test
    fun `parses translation JSON response`() {
        assertEquals(
            "Xin chào",
            LanServerTranslator.parseTranslationResponse("""{"translation":"Xin chào"}""")
        )
    }

    @Test
    fun `parses text JSON response`() {
        assertEquals(
            "Hello",
            LanServerTranslator.parseTranslationResponse("""{"text":"Hello"}""")
        )
    }

    @Test
    fun `decodes escaped JSON text`() {
        assertEquals(
            "Hello\nworld!",
            LanServerTranslator.parseTranslationResponse("""{"translation":"Hello\nworld\u0021"}""")
        )
    }

    @Test
    fun `accepts a bare string response`() {
        assertEquals("Hello", LanServerTranslator.parseTranslationResponse("  Hello  "))
    }

    @Test
    fun `rejects empty malformed and fieldless responses`() {
        listOf("", "{broken", "{}").forEach { body ->
            assertThrows(IllegalStateException::class.java) {
                LanServerTranslator.parseTranslationResponse(body)
            }
        }
    }
}
