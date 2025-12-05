package com.xalies.meshvault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

private const val DEFAULT_MAX_DIMENSION = 800

fun resizeThumbnailBytes(original: ByteArray, maxDimension: Int = DEFAULT_MAX_DIMENSION): ByteArray? {
    if (original.isEmpty()) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(original, 0, original.size, bounds)

    val sourceWidth = bounds.outWidth
    val sourceHeight = bounds.outHeight
    if (sourceWidth <= 0 || sourceHeight <= 0) return null

    var sampleSize = 1
    while (sourceWidth / sampleSize > maxDimension || sourceHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, decodeOptions) ?: return null

    val scale = (maxDimension.toFloat() / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
    val targetWidth = (decoded.width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (decoded.height * scale).roundToInt().coerceAtLeast(1)

    val scaledBitmap = if (decoded.width != targetWidth || decoded.height != targetHeight) {
        Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
    } else {
        decoded
    }

    val outputStream = ByteArrayOutputStream()
    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)

    if (scaledBitmap != decoded) {
        decoded.recycle()
    }
    scaledBitmap.recycle()

    return outputStream.toByteArray()
}

fun encodeThumbnailToBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

fun decodeBase64Image(raw: String?): ByteArray? {
    return raw?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
}
