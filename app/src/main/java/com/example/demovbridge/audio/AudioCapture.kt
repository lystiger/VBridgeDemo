package com.example.demovbridge.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class AudioCapture(
    private val sampleRate: Int = 16000,
    private val bufferSizeFactor: Int = 2
) {
    private val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    @SuppressLint("MissingPermission")
    fun capture(): Flow<ShortArray> = flow {
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * bufferSizeFactor
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            throw RuntimeException("AudioRecord initialization failed")
        }

        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(audioRecord.audioSessionId)?.let {
                it.enabled = true
            }
        }

        val buffer = ShortArray(480) // 30ms at 16kHz
        try {
            audioRecord.startRecording()
            // Set thread priority for audio capture
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            
            var totalRead = 0
            var allZeros = true
            while (coroutineContext.isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    if (allZeros) {
                        for (i in 0 until read) {
                            if (buffer[i] != 0.toShort()) {
                                allZeros = false
                                break
                            }
                        }
                    }
                    totalRead += read
                    emit(buffer.copyOf(read))
                } else if (read < 0) {
                    android.util.Log.e("AudioCapture", "AudioRecord read error: $read")
                    break
                }
            }
            android.util.Log.d("AudioCapture", "Capture finished. Total samples: $totalRead, All Zeros: $allZeros")
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
    }
}
