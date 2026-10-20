package com.example.robocontrol.vision

import android.graphics.Bitmap
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * An 8-bit grayscale image; pixel (x, y) is `pixels[y * width + x]`, read as `pixels[i].toInt() and 0xFF`.
 * @property width width in pixels
 * @property height height in pixels
 * @property pixels pixel values, row by row
 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {

    init {
        require(width > 0 && height > 0) { "invalid size ${width}x$height" }
        require(pixels.size == width * height) { "expected ${width * height} pixels, got ${pixels.size}" }
    }

    /** Converts to an ARGB bitmap, e.g. to show a captured frame or a reference image on screen. */
    fun toBitmap(): Bitmap {
        val argb = IntArray(pixels.size) { i ->
            val v = pixels[i].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }
}

/** A position in image pixel coordinates: x to the right, y downwards, origin top-left. */
data class ImagePoint(val x: Double, val y: Double)

/** Copies into a new single-channel OpenCV Mat. The caller must release() it. */
internal fun GrayImage.toMat(): Mat = Mat(height, width, CvType.CV_8UC1).also { it.put(0, 0, pixels) }

/** Copies a single-channel 8-bit Mat into a [GrayImage]. The Mat is not released. */
internal fun Mat.toGrayImage(): GrayImage {
    require(type() == CvType.CV_8UC1) { "expected CV_8UC1, got ${CvType.typeToString(type())}" }
    val bytes = ByteArray(rows() * cols())
    get(0, 0, bytes)
    return GrayImage(cols(), rows(), bytes)
}
