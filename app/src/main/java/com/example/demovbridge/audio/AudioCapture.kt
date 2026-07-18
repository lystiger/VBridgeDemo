package com.example.demovbridge.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class AudioCapture(
    private val sampleRate: Int = 16000,
    private val bufferSizeFactor: Int = 2
) {
    private val minBufferSize by lazy {
        AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
    }

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

        val noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(audioRecord.audioSessionId)?.apply {
                enabled = true
            }
        } else null

        val echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply {
                enabled = true
            }
        } else null

        val gainControl = if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(audioRecord.audioSessionId)?.apply {
                enabled = true
            }
        } else null

        val buffer = ShortArray(480) // 30ms at 16kHz
        try {
            audioRecord.startRecording()
            // Set thread priority for audio capture
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            
            while (coroutineContext.isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    emit(buffer.copyOf(read))
                } else if (read < 0) {
                    throw RuntimeException("AudioRecord read error: $read")
                }
            }
        } finally {
            noiseSuppressor?.release()
            echoCanceler?.release()
            gainControl?.release()
            try {
                audioRecord.stop()
            } catch (e: Exception) {
                // Ignore stop error if already stopped
            }
            audioRecord.release()
        }
    }
}
