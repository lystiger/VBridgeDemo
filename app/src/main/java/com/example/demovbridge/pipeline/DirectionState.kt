package com.example.demovbridge.pipeline

enum class Direction(val asrLang: String, val mlkitSource: String, val mlkitTarget: String) {
    ViToEn(asrLang = "vi", mlkitSource = "vi", mlkitTarget = "en"),
    EnToVi(asrLang = "en", mlkitSource = "en", mlkitTarget = "vi"),
}
