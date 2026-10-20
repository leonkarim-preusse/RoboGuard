package com.example.robocontrol.vision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * A marker colour as an HSV range in OpenCV units: hue 0–180 (degrees / 2), saturation and value 0–255.
 * If [hueMin] > [hueMax] the range wraps around red (e.g. 170..5).
 */
data class MarkerColor(val hueMin: Int, val hueMax: Int, val saturationMin: Int, val valueMin: Int) {
    companion object {
        /** Pink/magenta marker colour (OpenCV hue 140–175, i.e. about 280–350°). */
        val PINK = MarkerColor(hueMin = 140, hueMax = 175, saturationMin = 50, valueMin = 100)
    }
}

/**
 * A pixel rectangle in frame coordinates.
 */
data class ImageRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
    val area: Int get() = width * height
    /** Whether [p] lies inside this region. */
    fun contains(p: ImagePoint) = p.x >= x && p.x < x + width && p.y >= y && p.y < y + height
}

/**
 * One group of marker-coloured pixels.
 * @property search bounds of the pink pixels grown by the margin; the area ORB searches
 * @property bounds tight bounds of the pink pixels
 * @property pinkPixels number of marker-coloured pixels in the group
 * @property sideCoverage share (0–1) of each side of [bounds] with pink close to it: top, right, bottom, left
 * @property sides number of sides with at least [ColorMarker.minSideCoverage]
 */
data class MarkerRegion(
    val search: ImageRegion,
    val bounds: ImageRegion,
    val pinkPixels: Int,
    val sideCoverage: List<Double> = emptyList(),
    val sides: Int = 0,
    /** Accepted with one side fewer than required because a frame was accepted at the same place moments ago ([ColorMarker.holdMs]). */
    val held: Boolean = false,
    /** Share (0–1) of white pixels inside the pink bounds; −1 if not measured. */
    val whiteShare: Double = -1.0,
    /** Why the region was rejected: "frame" (too few sides) or "white" (too little white inside); null if accepted. */
    val rejectReason: String? = null
)

/**
 * Result of [ColorMarker.find].
 * @property regions accepted pink areas
 * @property pinkShare share of marker-coloured pixels in the frame (0–1)
 * @property millis duration of the search
 * @property overlay optional overlay: accepted pink pixels in [ColorMarker.overlayColor], rejected ones in [ColorMarker.rejectedColor]
 * @property rejected pink groups that were large enough but did not form a frame
 */
class MarkerResult(
    val regions: List<MarkerRegion>,
    val pinkShare: Double,
    val millis: Long,
    val overlay: Bitmap? = null,
    val rejected: List<MarkerRegion> = emptyList(),
    /**
     * Time per step in ms: [0] colour image from the camera data, [1] white balance + HSV + colour range, [2] cleaning + grouping
     * (closing, speck removal, dilation, connected components), [3] regions + shape checks. For finding where the pink search spends time.
     */
    val stageMs: LongArray = LongArray(4)
)

/**
 * Finds areas of a marker colour (e.g. a pink frame around a calendar) in a camera frame, so that ORB searches only
 * there. Steps: optional white balance, HSV colour range, morphological closing, removal of small specks, grouping
 * within [marginPx], and optionally the check that the pink forms a frame ([requireFrame]). Not thread-safe.
 * @param color colour range of the marker
 * @param marginPx distance within which pink pieces are grouped, in full-frame pixels
 * @param minSpeckPixels pink specks smaller than this are dropped
 * @param minRegionPixels minimum number of pink pixels of a group
 * @param whiteBalance apply a grey-world white balance first
 * @param requireFrame keep only groups that run along at least [minSides] sides
 */
class ColorMarker(
    var color: MarkerColor = MarkerColor.PINK,
    var marginPx: Int = 40,
    var minSpeckPixels: Int = 12,
    var minRegionPixels: Int = 150,
    var whiteBalance: Boolean = true,
    var requireFrame: Boolean = true,
    var minSides: Int = 3,
    var minSideCoverage: Double = 0.3,
    /** For this long after a frame was accepted, a region at the same place is accepted with one side fewer. */
    var holdMs: Long = 1_500,
    /** Share of pink pixels ignored at each end per axis when computing [MarkerRegion.bounds], so single stray pixels do not stretch them. */
    var boundsTrim: Double = 0.02,
    /** Scale factor of the frame used for the colour analysis; results are in full-frame coordinates. */
    var analysisScale: Double = 0.5,
    /**
     * Second shape identifier: the inside of the pink bounds must be mostly white (paper). Rejects pink objects, skin edges or
     * clothing that happen to form three sides. White = saturation ≤ [whiteMaxSaturation] and value ≥ [whiteMinValue] after white balance.
     */
    var requireWhiteInside: Boolean = true,
    var minWhiteShare: Double = 0.5,
    var whiteMaxSaturation: Int = 60,
    var whiteMinValue: Int = 140,
    var overlayColor: Int = 0xFF00E676.toInt(),
    var rejectedColor: Int = 0xFFFFEA00.toInt()
) {
    // Reused between calls (same frame size every time): avoids ~1 MB of new arrays per check for the garbage collector.
    private var labelBuf = IntArray(0)
    private var cleanBuf = ByteArray(0)

    /** Bounds of frames accepted without hold, with the time (elapsedRealtime); for [holdMs]. */
    private var recentFrames: List<Pair<ImageRegion, Long>> = emptyList()

    /** @param withOverlay also build [MarkerResult.overlay] (costs one frame-sized bitmap) */
    fun find(frame: Bitmap, withOverlay: Boolean = false): MarkerResult {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val rgba = Mat(); val rgb = Mat()
        try {
            Utils.bitmapToMat(frame, rgba)
            val scale = analysisScale.coerceIn(0.1, 1.0)
            if (scale < 1.0) Imgproc.resize(rgba, rgba, Size(), scale, scale, Imgproc.INTER_AREA)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            return analyse(rgb, scale, withOverlay, t0)
        } finally {
            rgba.release(); rgb.release()
        }
    }

    /**
     * Same as the bitmap overload, straight from the camera's YUV data (no bitmap round trip). At [analysisScale] 0.5 the camera's
     * native half-resolution colour planes are used directly.
     */
    fun find(frame: CameraFrame, withOverlay: Boolean = false): MarkerResult {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val scale = analysisScale.coerceIn(0.1, 1.0)
        val rgb = frame.rgbMat(half = scale == 0.5)
        try {
            if (scale != 0.5 && scale < 1.0) Imgproc.resize(rgb, rgb, Size(), scale, scale, Imgproc.INTER_AREA)
            val rgbMs = elapsed(t0)
            return analyse(rgb, scale, withOverlay, t0).also { it.stageMs[0] = rgbMs }
        } finally {
            rgb.release()
        }
    }

    /** [rgb]: CV_8UC3 at [scale] of the full frame; it is modified (white balance). */
    private fun analyse(rgb: Mat, scale: Double, withOverlay: Boolean, t0: Long): MarkerResult {
        val hsv = Mat(); val mask = Mat()
        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        try {
            val area = scale * scale
            val minRegion = (minRegionPixels * area).toInt().coerceAtLeast(1)
            val minSpeck = (minSpeckPixels * area).toInt().coerceAtLeast(2)
            val margin = (marginPx * scale).toInt().coerceAtLeast(1)
            fun up(r: ImageRegion) = ImageRegion((r.x / scale).toInt(), (r.y / scale).toInt(),
                kotlin.math.ceil(r.width / scale).toInt(), kotlin.math.ceil(r.height / scale).toInt())
            fun upPixels(n: Int) = (n / area).toInt()
            val stage = LongArray(4)
            var tStage = android.os.SystemClock.elapsedRealtime()
            fun lap(i: Int) { val now = android.os.SystemClock.elapsedRealtime(); stage[i] = now - tStage; tStage = now }
            if (whiteBalance) balanceWhite(rgb)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            inRange(hsv, mask)
            val width = mask.cols(); val height = mask.rows()
            val total = Core.countNonZero(mask)
            val share = total.toDouble() / (width * height)
            // The raw mask is only needed for the drawn overlay.
            val raw = if (withOverlay) ByteArray(width * height).also { mask.get(0, 0, it) } else null
            lap(1)
            if (total < minRegion) {
                return MarkerResult(emptyList(), share, elapsed(t0), raw?.let { overlay(true, it, null, null, width, height) }, stageMs = stage)
            }

            // Close small gaps along a thin line (a 2 px line breaks into pieces after chroma subsampling), then remove specks.
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                if (scale < 0.75) Size(3.0, 3.0) else Size(5.0, 5.0)))
            val specks = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
            // All component stats in one copy (one JNI call per component was slow with many noise specks).
            val statCols = stats.cols()
            val statData = IntArray(specks * statCols).also { if (specks > 0) stats.get(0, 0, it) }
            val keep = BooleanArray(specks) { it != 0 && statData[it * statCols + Imgproc.CC_STAT_AREA] >= minSpeck }
            if (labelBuf.size != width * height) { labelBuf = IntArray(width * height); cleanBuf = ByteArray(width * height) }
            val labelData = labelBuf.also { labels.get(0, 0, it) }
            val clean = cleanBuf
            for (i in labelData.indices) clean[i] = if (keep[labelData[i]]) 1 else 0
            mask.put(0, 0, clean)

            val grown = Mat()
            val groups = Mat()
            try {
                val k = 2 * margin + 1
                Imgproc.dilate(mask, grown, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble())))
                val n = Imgproc.connectedComponentsWithStats(grown, groups, stats, centroids, 8, CvType.CV_32S)
                val groupCols = stats.cols()
                val groupStats = IntArray(n * groupCols).also { if (n > 0) stats.get(0, 0, it) }
                lap(2)
                // Pink pixels and their tight bounds per grown group.
                val count = IntArray(n)
                val minX = IntArray(n) { Int.MAX_VALUE }; val minY = IntArray(n) { Int.MAX_VALUE }
                val maxX = IntArray(n) { -1 }; val maxY = IntArray(n) { -1 }
                groups.get(0, 0, labelData)
                for (y in 0 until height) {
                    val row = y * width
                    for (x in 0 until width) {
                        if (clean[row + x].toInt() == 0) continue
                        val g = labelData[row + x]
                        count[g]++
                        if (x < minX[g]) minX[g] = x
                        if (x > maxX[g]) maxX[g] = x
                        if (y < minY[g]) minY[g] = y
                        if (y > maxY[g]) maxY[g] = y
                    }
                }
                // White pixels are only needed if some group is big enough to be checked.
                val white: ByteArray? = if (!requireWhiteInside || (1 until n).none { count[it] >= minRegion }) null else {
                    val w = Mat()
                    try {
                        Core.inRange(hsv, Scalar(0.0, 0.0, whiteMinValue.toDouble()), Scalar(180.0, whiteMaxSaturation.toDouble(), 255.0), w)
                        ByteArray(width * height).also { w.get(0, 0, it) }
                    } finally {
                        w.release()
                    }
                }
                val accepted = BooleanArray(n)
                val regions = ArrayList<MarkerRegion>()
                val rejected = ArrayList<MarkerRegion>()
                val nowMs = android.os.SystemClock.elapsedRealtime()
                val recent = recentFrames.filter { nowMs - it.second <= holdMs }
                val freshFrames = ArrayList<Pair<ImageRegion, Long>>()
                for (g in 1 until n) {
                    if (count[g] < minRegion) continue
                    val loose = ImageRegion(minX[g], minY[g], maxX[g] - minX[g] + 1, maxY[g] - minY[g] + 1)
                    val bounds = trimmedBounds(clean, labelData, g, loose, count[g], width)
                    val shape = orientedShape(clean, labelData, g, loose, count[g], width, white)
                    val coverage = shape.coverage
                    val sides = coverage.count { it >= minSideCoverage }
                    val o = g * groupCols
                    val search = ImageRegion(groupStats[o + Imgproc.CC_STAT_LEFT], groupStats[o + Imgproc.CC_STAT_TOP],
                        groupStats[o + Imgproc.CC_STAT_WIDTH], groupStats[o + Imgproc.CC_STAT_HEIGHT])
                    val isFrame = !requireFrame || sides >= minSides
                    val held = !isFrame && sides >= minSides - 1 && recent.any { overlap(it.first, bounds) >= 0.4 }
                    val whiteShare = shape.whiteShare
                    val whiteOk = !requireWhiteInside || whiteShare >= minWhiteShare
                    val reason = when {
                        !isFrame && !held -> "frame"
                        !whiteOk -> "white"
                        else -> null
                    }
                    // Coverage and hold work in analysis pixels; the result is reported in full-frame pixels.
                    val region = MarkerRegion(up(search), up(bounds), upPixels(count[g]), coverage, sides, held, whiteShare, reason)
                    if (reason == null) {
                        accepted[g] = true
                        regions += region
                        if (isFrame) freshFrames += bounds to nowMs
                    } else {
                        rejected += region
                    }
                }
                // Held regions do not refresh the memory, so a frame that stays incomplete is dropped after holdMs.
                recentFrames = recent + freshFrames
                regions.sortByDescending { it.pinkPixels }
                lap(3)
                val overlayBitmap = raw?.let { overlay(true, it, clean, labelData to accepted, width, height) }
                return MarkerResult(regions, share, elapsed(t0), overlayBitmap, rejected, stage)
            } finally {
                grown.release()
                groups.release()
            }
        } finally {
            hsv.release(); mask.release()
            labels.release(); stats.release(); centroids.release()
        }
    }

    /** Bounds of group [g] ignoring [boundsTrim] of its pixels at each end of each axis. */
    private fun trimmedBounds(clean: ByteArray, labels: IntArray, g: Int, b: ImageRegion, pixels: Int, width: Int): ImageRegion {
        val cols = IntArray(b.width)
        val rows = IntArray(b.height)
        for (y in b.y until b.y + b.height) {
            val row = y * width
            for (x in b.x until b.x + b.width) {
                val i = row + x
                if (clean[i].toInt() != 0 && labels[i] == g) { cols[x - b.x]++; rows[y - b.y]++ }
            }
        }
        val skip = (pixels * boundsTrim).toInt()
        fun lowIndex(h: IntArray): Int { var sum = 0; for (i in h.indices) { sum += h[i]; if (sum > skip) return i }; return 0 }
        fun highIndex(h: IntArray): Int { var sum = 0; for (i in h.indices.reversed()) { sum += h[i]; if (sum > skip) return i }; return h.size - 1 }
        val x0 = lowIndex(cols); val x1 = highIndex(cols)
        val y0 = lowIndex(rows); val y1 = highIndex(rows)
        if (x1 <= x0 || y1 <= y0) return b
        return ImageRegion(b.x + x0, b.y + y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /** Shape numbers of one pink group: side coverage (top, right, bottom, left), white share inside and the main angle. */
    private class Shape(val coverage: List<Double>, val whiteShare: Double, val angleDeg: Double)

    /**
     * Side coverage and white share measured in the orientation of the pink pixels (principal axes), so that rotated or
     * tilted frames are measured correctly.
     * @return coverage per side (top, right, bottom, left), white share and angle
     */
    private fun orientedShape(clean: ByteArray, labels: IntArray, g: Int, b: ImageRegion, pixels: Int, width: Int, white: ByteArray?): Shape {
        val xs = IntArray(pixels); val ys = IntArray(pixels)
        var n = 0
        var sx = 0.0; var sy = 0.0
        for (y in b.y until b.y + b.height) {
            val row = y * width
            for (x in b.x until b.x + b.width) {
                val i = row + x
                if (clean[i].toInt() != 0 && labels[i] == g && n < pixels) { xs[n] = x; ys[n] = y; n++; sx += x; sy += y }
            }
        }
        if (n < 8) return Shape(listOf(0.0, 0.0, 0.0, 0.0), if (white == null) -1.0 else 0.0, 0.0)
        val cx = sx / n; val cy = sy / n
        var cxx = 0.0; var cyy = 0.0; var cxy = 0.0
        for (i in 0 until n) { val dx = xs[i] - cx; val dy = ys[i] - cy; cxx += dx * dx; cyy += dy * dy; cxy += dx * dy }
        // A rectangular ring's principal axes are its sides (for a square ring they are undefined; then image axes are used).
        val theta = if (kotlin.math.abs(cxx - cyy) + kotlin.math.abs(cxy) < 1e-6 * (cxx + cyy)) 0.0 else 0.5 * kotlin.math.atan2(2 * cxy, cxx - cyy)
        val cos = kotlin.math.cos(theta); val sin = kotlin.math.sin(theta)
        val us = DoubleArray(n); val vs = DoubleArray(n)
        for (i in 0 until n) {
            val dx = xs[i] - cx; val dy = ys[i] - cy
            us[i] = dx * cos + dy * sin
            vs[i] = -dx * sin + dy * cos
        }
        val su = us.sortedArray(); val sv = vs.sortedArray()
        val k = (n * boundsTrim).toInt().coerceAtMost(n / 4)
        val u0 = su[k]; val u1 = su[n - 1 - k]; val v0 = sv[k]; val v1 = sv[n - 1 - k]
        val lenU = (u1 - u0).toInt() + 1; val lenV = (v1 - v0).toInt() + 1
        if (lenU < 4 || lenV < 4) return Shape(listOf(0.0, 0.0, 0.0, 0.0), if (white == null) -1.0 else 0.0, Math.toDegrees(theta))
        val band = maxOf(2.0, minOf(lenU, lenV) * 0.12)
        val top = BooleanArray(lenU); val bottom = BooleanArray(lenU)
        val left = BooleanArray(lenV); val right = BooleanArray(lenV)
        for (i in 0 until n) {
            val u = us[i]; val v = vs[i]
            if (u < u0 || u > u1 || v < v0 || v > v1) continue
            val iu = (u - u0).toInt().coerceIn(0, lenU - 1); val iv = (v - v0).toInt().coerceIn(0, lenV - 1)
            if (v - v0 < band) top[iu] = true
            if (v1 - v < band) bottom[iu] = true
            if (u - u0 < band) left[iv] = true
            if (u1 - u < band) right[iv] = true
        }
        fun share(a: BooleanArray) = a.count { it }.toDouble() / a.size
        var whiteShare = -1.0
        if (white != null) {
            // Inner rectangle (inside the band) in rotated coordinates, scanned over the image pixels of its bounding box.
            val iu0 = u0 + band; val iu1 = u1 - band; val iv0 = v0 + band; val iv1 = v1 - band
            if (iu1 - iu0 < 4 || iv1 - iv0 < 4) {
                whiteShare = 0.0
            } else {
                val height = clean.size / width
                var inside = 0; var whites = 0
                for (y in b.y until b.y + b.height) {
                    if (y < 0 || y >= height) continue
                    val row = y * width
                    val dy = y - cy
                    for (x in b.x until b.x + b.width) {
                        val dx = x - cx
                        val u = dx * cos + dy * sin
                        val v = -dx * sin + dy * cos
                        if (u < iu0 || u > iu1 || v < iv0 || v > iv1) continue
                        inside++
                        if (white[row + x].toInt() != 0) whites++
                    }
                }
                whiteShare = if (inside == 0) 0.0 else whites.toDouble() / inside
            }
        }
        return Shape(listOf(share(top), share(right), share(bottom), share(left)), whiteShare, Math.toDegrees(theta))
    }

    /** Share of white pixels inside [b] without the border band that holds the pink line. */
    private fun whiteInside(white: ByteArray, b: ImageRegion, width: Int): Double {
        val band = maxOf(2, (minOf(b.width, b.height) * 0.12).toInt())
        val x0 = b.x + band; val x1 = b.x + b.width - band
        val y0 = b.y + band; val y1 = b.y + b.height - band
        if (x1 - x0 < 4 || y1 - y0 < 4) return 0.0
        var n = 0
        for (y in y0 until y1) {
            val row = y * width
            for (x in x0 until x1) if (white[row + x].toInt() != 0) n++
        }
        return n.toDouble() / ((x1 - x0) * (y1 - y0))
    }

    /** Intersection over the smaller of the two areas (0–1). */
    private fun overlap(a: ImageRegion, b: ImageRegion): Double {
        val w = minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)
        val h = minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)
        if (w <= 0 || h <= 0) return 0.0
        return (w.toDouble() * h) / minOf(a.area, b.area).coerceAtLeast(1)
    }

    /** Share of positions along each side (top, right, bottom, left) that have pink in a band along that side. */
    private fun sideCoverage(clean: ByteArray, labels: IntArray, g: Int, b: ImageRegion, width: Int): List<Double> {
        val band = maxOf(3, (minOf(b.width, b.height) * 0.12).toInt())
        val top = BooleanArray(b.width); val bottom = BooleanArray(b.width)
        val left = BooleanArray(b.height); val right = BooleanArray(b.height)
        for (y in b.y until b.y + b.height) {
            val row = y * width
            for (x in b.x until b.x + b.width) {
                val i = row + x
                if (clean[i].toInt() == 0 || labels[i] != g) continue
                val dx = x - b.x; val dy = y - b.y
                if (dy < band) top[dx] = true
                if (b.height - 1 - dy < band) bottom[dx] = true
                if (dx < band) left[dy] = true
                if (b.width - 1 - dx < band) right[dy] = true
            }
        }
        fun share(a: BooleanArray) = if (a.isEmpty()) 0.0 else a.count { it }.toDouble() / a.size
        return listOf(share(top), share(right), share(bottom), share(left))
    }

    /** Grey-world white balance using only bright pixels (paper, walls), so a colour cast of the camera does not shift the hue. */
    private fun balanceWhite(rgb: Mat) {
        val gray = Mat(); val bright = Mat()
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.threshold(gray, bright, 150.0, 255.0, Imgproc.THRESH_BINARY)
            if (Core.countNonZero(bright) < 1000) return
            val m = Core.mean(rgb, bright).`val`
            val avg = (m[0] + m[1] + m[2]) / 3
            if (m[0] <= 0 || m[1] <= 0 || m[2] <= 0) return
            Core.multiply(rgb, Scalar(avg / m[0], avg / m[1], avg / m[2]), rgb)
        } finally {
            gray.release(); bright.release()
        }
    }

    /** The overlay picture for the camera view (green = accepted pink, yellow = rejected), or null if not [wanted]. */
    private fun overlay(
        wanted: Boolean, raw: ByteArray, clean: ByteArray?, groups: Pair<IntArray, BooleanArray>?, width: Int, height: Int
    ): Bitmap? {
        if (!wanted) return null
        val px = IntArray(width * height)
        for (i in raw.indices) if (raw[i].toInt() != 0) px[i] = rejectedColor
        if (clean != null && groups != null) {
            val (labels, accepted) = groups
            for (i in clean.indices) if (clean[i].toInt() != 0 && accepted[labels[i]]) px[i] = overlayColor
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Marks the pixels of [hsv] whose hue, saturation and value lie in the marker colour's range. */
    private fun inRange(hsv: Mat, out: Mat) {
        val c = color
        val low = Scalar(c.hueMin.toDouble(), c.saturationMin.toDouble(), c.valueMin.toDouble())
        val high = Scalar(c.hueMax.toDouble(), 255.0, 255.0)
        if (c.hueMin <= c.hueMax) {
            Core.inRange(hsv, low, high, out)
            return
        }
        val upper = Mat()
        try {
            Core.inRange(hsv, low, Scalar(180.0, 255.0, 255.0), out)
            Core.inRange(hsv, Scalar(0.0, c.saturationMin.toDouble(), c.valueMin.toDouble()), high, upper)
            Core.bitwise_or(out, upper, out)
        } finally {
            upper.release()
        }
    }

    /** Milliseconds since [t0]. */
    private fun elapsed(t0: Long) = android.os.SystemClock.elapsedRealtime() - t0
}
