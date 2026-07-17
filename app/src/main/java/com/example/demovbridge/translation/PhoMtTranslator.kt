package com.example.demovbridge.translation

import com.example.demovbridge.pipeline.Direction

/**
 * Placeholder for the upcoming PhoMT distilled student model.
 * Currently returns a clear error to trigger fallback or notice.
 */
class PhoMtTranslator : Translator {
    override suspend fun translate(text: String, direction: Direction): TranslationResult {
        // TODO: Integrate phomt_student.onnx when delivered
        throw NotImplementedError("PhoMT model not yet integrated. Use MlKitTranslator baseline.")
    }
}
