package com.example.demovbridge.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object ResourceUtils {
    fun copyAssetsDir(context: Context, assetPath: String, targetDir: File) {
        val assets = context.assets.list(assetPath) ?: return
        if (assets.isEmpty()) {
            // It's a file
            copyAssetFile(context, assetPath, targetDir)
        } else {
            // It's a directory
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            for (asset in assets) {
                val subAssetPath = if (assetPath.isEmpty()) asset else "$assetPath/$asset"
                copyAssetsDir(context, subAssetPath, File(targetDir, asset))
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
        if (targetFile.exists()) return
        
        context.assets.open(assetPath).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}
