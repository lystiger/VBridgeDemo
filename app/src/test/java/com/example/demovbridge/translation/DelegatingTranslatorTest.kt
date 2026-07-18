package com.example.demovbridge.translation

import com.example.demovbridge.pipeline.Direction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DelegatingTranslatorTest {
    private class FakeTranslator(
        private val result: TranslationResult? = null,
        private val failure: Throwable? = null
    ) : Translator {
        var calls = 0
        var closeCalls = 0

        override suspend fun translate(text: String, direction: Direction): TranslationResult {
            calls++
            failure?.let { throw it }
            return requireNotNull(result)
        }

        override fun close() {
            closeCalls++
        }
    }

    @Test
    fun `on-device is the default and remote can be enabled without recreation`() = runTest {
        val onDevice = FakeTranslator(TranslationResult("local", 10, "MLKit Baseline"))
        val remote = FakeTranslator(TranslationResult("remote", 5, "LAN"))
        val translator = DelegatingTranslator(onDevice, remote)

        assertFalse(translator.useRemote)
        assertEquals("local", translator.translate("hello", Direction.EnToVi).text)

        translator.useRemote = true
        assertEquals("remote", translator.translate("hello", Direction.EnToVi).text)
        assertEquals(1, onDevice.calls)
        assertEquals(1, remote.calls)
    }

    @Test
    fun `remote failure always falls back to on-device`() = runTest {
        val onDevice = FakeTranslator(TranslationResult("fallback", 10, "MLKit Baseline"))
        val remote = FakeTranslator(failure = IllegalStateException("offline"))
        val translator = DelegatingTranslator(onDevice, remote).apply { useRemote = true }

        val result = translator.translate("hello", Direction.EnToVi)

        assertEquals("fallback", result.text)
        assertEquals("MLKit (fallback)", result.modelName)
        assertEquals(1, onDevice.calls)
        assertEquals(1, remote.calls)
    }

    @Test
    fun `close owns each leaf exactly once`() {
        val onDevice = FakeTranslator(TranslationResult("local", 10, "MLKit Baseline"))
        val remote = FakeTranslator(TranslationResult("remote", 5, "LAN"))
        val translator = DelegatingTranslator(onDevice, remote)

        translator.close()

        assertEquals(1, onDevice.closeCalls)
        assertEquals(1, remote.closeCalls)
    }
}
