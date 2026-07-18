package com.example.demovbridge.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class AndroidTts(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false
    private var onDoneCallback: (() -> Unit)? = null

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                onDoneCallback?.invoke()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
        } else {
            Log.e("AndroidTts", "Initialization failed")
        }
    }

    fun speak(text: String, locale: Locale, onDone: () -> Unit) {
        if (isReady) {
            onDoneCallback = onDone
            tts?.language = locale
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vbridge_tts")
        } else {
            onDone()
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
