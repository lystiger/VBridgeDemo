package com.example.demovbridge.translation.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.LongBuffer

class MarianOnnxEngine(dir: File, numThreads: Int = 2) {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(numThreads)
    }
    
    val encoder: OrtSession = env.createSession(File(dir, "encoder_model.onnx").path, opts)
    val decoder: OrtSession = env.createSession(File(dir, "decoder_model.onnx").path, opts)
    val decoderPast: OrtSession = env.createSession(File(dir, "decoder_with_past_model.onnx").path, opts)

    init {
        Log.d("MarianOnnx", "Engine initialized for ${dir.name}")
        Log.d("MarianOnnx", "Encoder inputs: ${encoder.inputNames}")
        Log.d("MarianOnnx", "Encoder outputs: ${encoder.outputNames}")
        Log.d("MarianOnnx", "Decoder inputs: ${decoder.inputNames}")
        Log.d("MarianOnnx", "Decoder outputs: ${decoder.outputNames}")
        Log.d("MarianOnnx", "DecoderPast inputs: ${decoderPast.inputNames}")
    }

    fun translateIds(inputIds: LongArray): List<Int> {
        val batchSize = 1L
        val seqLen = inputIds.size.toLong()

        // 1. Encoder
        val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(batchSize, seqLen))
        val attentionMask = LongArray(inputIds.size) { 1L }
        val attentionMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), longArrayOf(batchSize, seqLen))
        
        val encoderInputs = mutableMapOf<String, OnnxTensor>()
        encoderInputs[encoder.inputNames.first { it.contains("input_ids") }] = inputIdsTensor
        encoderInputs[encoder.inputNames.first { it.contains("attention_mask") }] = attentionMaskTensor
        
        val encoderOutput = encoder.run(encoderInputs)
        val encoderHiddenStates = encoderOutput.get(0) as OnnxTensor
        
        val resultTokens = mutableListOf<Int>()
        var currentToken = 53684L // decoder_start_token_id (pad)
        
        var lastDecoderResult: OrtSession.Result? = null
        val maxNewTokens = (seqLen * 2 + 16).toInt().coerceAtMost(512)
        
        try {
            for (i in 0 until maxNewTokens) {
                val currentDecoder = if (lastDecoderResult == null) decoder else decoderPast
                
                val decoderInputIds = OnnxTensor.createTensor(env, longArrayOf(currentToken).let { LongBuffer.wrap(it) }, longArrayOf(1, 1))
                
                val decoderInputs = mutableMapOf<String, OnnxTensor>()
                decoderInputs[currentDecoder.inputNames.first { it.contains("input_ids") }] = decoderInputIds
                decoderInputs[currentDecoder.inputNames.first { it.contains("encoder_hidden_states") }] = encoderHiddenStates
                decoderInputs[currentDecoder.inputNames.first { it.contains("encoder_attention_mask") }] = attentionMaskTensor
                
                lastDecoderResult?.forEach { (name, value) ->
                    val pastName = name.replace("present", "past_key_values")
                    if (currentDecoder.inputNames.contains(pastName)) {
                        decoderInputs[pastName] = value as OnnxTensor
                    }
                }

                val decoderResult = currentDecoder.run(decoderInputs)
                val logits = decoderResult.get(0) as OnnxTensor
                
                val nextToken = getNextToken(logits)
                
                decoderInputIds.close()
                if (nextToken == 0) {
                    decoderResult.close()
                    break // eos_token_id
                }
                resultTokens.add(nextToken)
                currentToken = nextToken.toLong()
                
                lastDecoderResult?.close()
                lastDecoderResult = decoderResult
            }
        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
            lastDecoderResult?.close()
            encoderOutput.close()
        }
        
        return resultTokens
    }

    private fun getNextToken(logits: OnnxTensor): Int {
        val floatBuffer = logits.floatBuffer
        val vocabSize = logits.info.shape[2].toInt()
        var maxLogit = Float.NEGATIVE_INFINITY
        var maxIdx = -1
        
        for (i in 0 until vocabSize) {
            if (i == 53684) continue // bad_words_ids = [pad]
            val logit = floatBuffer.get(i)
            if (logit > maxLogit) {
                maxLogit = logit
                maxIdx = i
            }
        }
        return maxIdx
    }

    fun close() {
        encoder.close()
        decoder.close()
        decoderPast.close()
    }
}
