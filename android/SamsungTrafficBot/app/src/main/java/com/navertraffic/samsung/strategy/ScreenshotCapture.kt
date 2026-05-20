package com.navertraffic.samsung.strategy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

interface ScreenshotCapture {
    suspend fun captureBase64(): String?
}

class RootedScreenshotCapture : ScreenshotCapture {
    override suspend fun captureBase64(): String? = withContext(Dispatchers.IO) {
        val tmpPath = "/sdcard/zero_captcha_tmp.png"
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p $tmpPath"))
            if (proc.waitFor() != 0) return@withContext null
            val file = File(tmpPath)
            if (!file.exists()) return@withContext null
            val bytes = file.readBytes()
            file.delete()
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            val w = 540
            val h = w * src.height / src.width
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            src.recycle()
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            scaled.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }
}
