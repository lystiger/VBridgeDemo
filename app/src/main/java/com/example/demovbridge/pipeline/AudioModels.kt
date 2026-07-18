package com.example.demovbridge.pipeline

data class PcmAudio(
    val samples: ShortArray,
    val sampleRate: Int = 16_000,
    val channels: Int = 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PcmAudio
        if (!samples.contentEquals(other.samples)) return false
        if (sampleRate != other.sampleRate) return false
        if (channels != other.channels) return false
        return true
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        return result
    }
}

data class SynthesizedAudio(
    val pcm: ShortArray,
    val sampleRate: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SynthesizedAudio
        if (!pcm.contentEquals(other.pcm)) return false
        if (sampleRate != other.sampleRate) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + sampleRate
        return result
    }
}
