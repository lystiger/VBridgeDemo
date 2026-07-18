package com.example.demovbridge.translation.onnx

import ai.djl.sentencepiece.SpTokenizer
import org.json.JSONObject
import java.io.File

class MarianTokenizer(dir: File) {
    private val eosId = 0
    private val unkId = 1
    private val padId = 53684

    private val vocab: Map<String, Int> = loadVocab(File(dir, "vocab.json"))
    private val idToPiece: Map<Int, String> = vocab.entries.associate { (k, v) -> v to k }

    // Load source.spm as bytes to avoid java.nio.file.Path (API 26)
    private val tokenizer = SpTokenizer(File(dir, "source.spm").readBytes())
    private val sp = tokenizer.getProcessor()

    fun encode(text: String): LongArray {
        val normalized = normalize(text)
        // sp.encode returns IntArray on DJL
        val pieces = sp.tokenize(normalized)
        val ids = pieces.map { vocab[it] ?: unkId }
        return (ids + eosId).map { it.toLong() }.toLongArray()
    }

    fun decode(ids: List<Int>): String {
        val text = ids
            .filter { it != eosId && it != padId }
            .mapNotNull { idToPiece[it] }
            .joinToString("")
            .replace("\u2581", " ")   // ▁ -> space
            .trim()
        return text
    }

    private fun loadVocab(file: File): Map<String, Int> {
        val json = file.readText()
        val obj = JSONObject(json)
        val map = mutableMapOf<String, Int>()
        obj.keys().forEach { key ->
            map[key] = obj.getInt(key)
        }
        return map
    }

    private fun normalize(text: String): String {
        return text.lowercase()
    }
    
    fun close() {
        tokenizer.close()
    }
}
