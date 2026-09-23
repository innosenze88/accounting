package com.example.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Keeps the original image of every scanned document in app-private storage
 * (filesDir/document_images) so each accounting entry has its source evidence.
 */
class DocumentImageStore(context: Context) {

    private val dir: File = File(context.applicationContext.filesDir, "document_images").apply { mkdirs() }

    /** Saves [bitmap] as JPEG and returns its absolute path. */
    suspend fun save(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val scaled = scaleDown(source, MAX_DIMENSION)
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { out ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                "Could not encode document image"
            }
        }
        file.absolutePath
    }

    suspend fun load(path: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext null
        val file = File(path)
        if (!file.exists()) return@withContext null
        BitmapFactory.decodeFile(file.absolutePath)
    }

    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext
        val file = File(path)
        // Only ever delete files inside our own folder.
        if (file.parentFile?.canonicalPath == dir.canonicalPath) file.delete()
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDimension && h <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    companion object {
        private const val MAX_DIMENSION = 2400
        private const val JPEG_QUALITY = 90
    }
}
