package com.example.demovbridge.translation

import com.example.demovbridge.pipeline.Direction

class Glossary {
    private val terms = mapOf(
        Direction.ViToEn to listOf(
            "VBridge" to "VBridge",
            "Trí tuệ nhân tạo" to "Artificial Intelligence"
        ),
        Direction.EnToVi to listOf(
            "VBridge" to "VBridge",
            "AI" to "Trí tuệ nhân tạo"
        )
    )

    fun apply(text: String, direction: Direction): String {
        var result = text
        terms[direction]?.forEach { (source, target) ->
            // Simple whole-word replacement
            val regex = Regex("\\b$source\\b", RegexOption.IGNORE_CASE)
            result = result.replace(regex, target)
        }
        return result
    }
}
