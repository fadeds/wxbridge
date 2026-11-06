package io.wxbridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** 图片解码/压缩的公共逻辑，OCR 和视觉模型都要用 */
object BitmapUtil {

    /**
     * 按最大边长降采样解码。
     * 微信的图片动辄 4000×3000，直接 decode 会 OOM，必须先读 bounds 再算 inSampleSize。
     */
    fun decodeSampled(file: File, maxDim: Int = 1280): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
    }

    /** 压成 JPEG。长边 1024 / 质量 75，单张大约 100~200KB，够模型看清又不浪费 token */
    fun toJpeg(file: File, maxDim: Int = 1024, quality: Int = 75): ByteArray? {
        val bmp = decodeSampled(file, maxDim) ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { bmp.recycle() }
        }
    }
}
