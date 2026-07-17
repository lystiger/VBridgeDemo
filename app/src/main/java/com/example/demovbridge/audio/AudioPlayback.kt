package com.example.demovbridge.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioPlayback(
    private val sampleRate: Int = 16000
) {
    private val audioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    suspend fun play(pcm: ShortArray) = withContext(Dispatchers.IO) {
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) return@withContext
        
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.play()
        }
        audioTrack.write(pcm, 0, pcm.size)
    }

    fun stop() {
        if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
            audioTrack.pause()
            audioTrack.flush()
        }
    }

    fun release() {
        audioTrack.release()
    }
}
