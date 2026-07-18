package com.example.demovbridge.translation.onnx

import android.content.Context
import java.io.File

object OnnxAssets {
    // Copies an asset subdir to filesDir once; returns the on-disk dir.
    fun copyAssetDir(context: Context, assetDir: String): File {
        val outDir = File(context.filesDir, assetDir)
        val am = context.assets
        val list = am.list(assetDir) ?: emptyArray()
        if (list.isEmpty()) {
            return outDir
        }
        
        if (!outDir.exists()) {
            outDir.mkdirs()
        }

        list.forEach { name ->
            val assetPath = "$assetDir/$name"
            val outFile = File(outDir, name)
            
            // Basic check: copy if not exists or size is 0
            if (!outFile.exists() || outFile.length() == 0L) {
                try {
                    am.open(assetPath).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    // Might be a subdirectory, ignore for now or recurse if needed
                }
            }
        }
        return outDir
    }
}
