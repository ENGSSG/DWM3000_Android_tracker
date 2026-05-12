package com.dwm3000.tracker.vision

import android.content.Context
import java.io.File

internal object AssetFiles {
    fun copyToCache(context: Context, assetPath: String): File {
        val target = File(context.cacheDir, assetPath.substringAfterLast('/'))
        if (target.exists() && target.length() > 0L) return target

        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output, bufferSize = 16 * 1024)
            }
        }
        return target
    }
}
