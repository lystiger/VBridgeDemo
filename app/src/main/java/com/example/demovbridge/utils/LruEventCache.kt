package com.example.demovbridge.utils

import java.util.*

class LruEventCache(private val maxSize: Int) {
    private val cache = object : LinkedHashMap<String, Boolean>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun markIfNew(id: String): Boolean {
        if (cache.containsKey(id)) {
            return false
        }
        cache[id] = true
        return true
    }
}
