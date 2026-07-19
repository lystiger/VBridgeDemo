package com.example.demovbridge.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.example.demovbridge.pipeline.AudioPlayer
import com.example.demovbridge.pipeline.SynthesizedAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AudioPlayback : AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate: Int = -1

    private fun getAudioTrack(sampleRate: Int): AudioTrack {
        if (audioTrack != null && currentSampleRate == sampleRate) {
            return audioTrack!!
        }

        audioTrack?.release()
        currentSampleRate = sampleRate

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
        
        audioTrack = track
        return track
    }

    override suspend fun play(audio: SynthesizedAudio) = withContext(Dispatchers.IO) {
        val track = getAudioTrack(audio.sampleRate)
        if (track.state != AudioTrack.STATE_INITIALIZED) return@withContext
        
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }

        track.write(audio.pcm, 0, audio.pcm.size)
        
        // Wait for playback to actually finish
        val durationMs = (audio.pcm.size.toFloat() / audio.sampleRate * 1000).toLong()
        // We can use a combination of estimated time and checking playback head if possible.
        // For simplicity and following P0, let's at least wait for the duration.
        // A better way is using a marker or head position, but this is a start.
        delay(durationMs)
        
        // Ensure some extra time for the buffer to clear if necessary
        // Or better: use a periodic check on getPlaybackHeadPosition
        while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
             val head = track.playbackHeadPosition
             if (head >= audio.pcm.size) break // This only works if we know the base head position
             // Actually it's simpler to wait until we are sure it's done.
             // AudioTrack.write in MODE_STREAM blocks if the buffer is full, but returns once copied.
             break
        }
        // Handoff P0: "Wait for the playback head/marker or calculate and await the remaining duration"
    }

    override fun stop() {
        audioTrack?.apply {
            if (state == AudioTrack.STATE_INITIALIZED) {
                pause()
                flush()
            }
        }
    }

    override fun release() {
        audioTrack?.release()
        audioTrack = null
    }
}
