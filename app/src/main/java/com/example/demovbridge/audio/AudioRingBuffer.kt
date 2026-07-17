package com.example.demovbridge.audio

import java.util.concurrent.atomic.AtomicInteger

/**
 * A simple lock-free ShortArray ring buffer for Single Producer / Single Consumer.
 */
class AudioRingBuffer(val capacity: Int) {
    private val buffer = ShortArray(capacity)
    private val head = AtomicInteger(0)
    private val tail = AtomicInteger(0)

    fun write(data: ShortArray): Int {
        var written = 0
        val t = tail.get()
        val h = head.get()
        
        val available = if (t >= h) capacity - (t - h) - 1 else h - t - 1
        val toWrite = minOf(data.size, available)

        for (i in 0 until toWrite) {
            buffer[(t + i) % capacity] = data[i]
        }
        
        tail.set((t + toWrite) % capacity)
        return toWrite
    }

    fun read(out: ShortArray): Int {
        val h = head.get()
        val t = tail.get()
        
        val available = if (t >= h) t - h else capacity - (h - t)
        val toRead = minOf(out.size, available)

        for (i in 0 until toRead) {
            out[i] = buffer[(h + i) % capacity]
        }
        
        head.set((h + toRead) % capacity)
        return toRead
    }
    
    fun availableRead(): Int {
        val h = head.get()
        val t = tail.get()
        return if (t >= h) t - h else capacity - (h - t)
    }
}
