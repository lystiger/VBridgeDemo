package com.example.demovbridge.translation

import android.content.Context
import android.os.SystemClock
import com.example.demovbridge.pipeline.Direction
import com.example.demovbridge.translation.onnx.MarianOnnxEngine
import com.example.demovbridge.translation.onnx.MarianTokenizer
import com.example.demovbridge.translation.onnx.OnnxAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhoMtOnnxTranslator(context: Context) : Translator {
    private val viEnDir = OnnxAssets.copyAssetDir(context, "mt-vi-en/onnx_int8")
    private val enViDir = OnnxAssets.copyAssetDir(context, "mt-en-vi/onnx_int8")

    private var viEn: MarianOnnxEngine? = null
    private var enVi: MarianOnnxEngine? = null
    private var viEnTok: MarianTokenizer? = null
    private var enViTok: MarianTokenizer? = null

    init {
        // We'll initialize lazily or via warmUp to not block the main thread
    }

    override suspend fun translate(text: String, direction: Direction): TranslationResult =
        withContext(Dispatchers.Default) {
            if (text.isBlank()) return@withContext TranslationResult("", 0, "phomt-onnx")
            
            ensureInitialized()
            
            val t0 = SystemClock.elapsedRealtime()
            val (engine, tok) = when (direction) {
                Direction.ViToEn -> viEn!! to viEnTok!!
                Direction.EnToVi -> enVi!! to enViTok!!
            }
            
            val ids = engine.translateIds(tok.encode(text))
            val out = tok.decode(ids)
            
            TranslationResult(out, SystemClock.elapsedRealtime() - t0, "phomt-onnx")
        }

    suspend fun warmUp() {
        withContext(Dispatchers.Default) {
            try {
                ensureInitialized()
                // Run one dummy translate per direction so first real turn is fast
                translate("warmup", Direction.ViToEn)
                translate("warmup", Direction.EnToVi)
                android.util.Log.i("PhoMtOnnx", "Warm-up complete")
            } catch (e: Exception) {
                android.util.Log.e("PhoMtOnnx", "Warm-up failed", e)
            }
        }
    }

    @Synchronized
    private fun ensureInitialized() {
        if (viEn == null) {
            viEn = MarianOnnxEngine(viEnDir)
            viEnTok = MarianTokenizer(viEnDir)
        }
        if (enVi == null) {
            enVi = MarianOnnxEngine(enViDir)
            enViTok = MarianTokenizer(enViDir)
        }
    }

    fun close() {
        viEn?.close()
        enVi?.close()
        viEnTok?.close()
        enViTok?.close()
    }
}
