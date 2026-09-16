package com.example.robocontrol.movement

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * The map tool's map image, `/sdcard/robot/map/<mapName>/navi_data/map.pgm`, which is also where RobotOS keeps the
 * **no-go lines** drawn in the map tool.
 *
 * Format and coordinate conversion were read out of RobotOS's map tool (MapTool.apk: `MapUtils.loadMap`,
 * `pose2PixelByRoverMap`, `saveRoverMapToPgm`) and verified on robot ZTT18P1000A0 (2026-09-16):
 * ```
 * "P5\n<width> <height>\n255\n"
 * width × height bytes, row 0 = TOP of the map (highest world y)
 * 16 extra bytes: double LE resolution (m/px), float LE origin x (m), float LE origin y (m)
 * ```
 * Pixel values: 255 free, 150 unknown, 5 walls/obstacles, **0 no-go line**.
 * Conversion: `px = (x - originX) / resolution`, `py = height - (y - originY) / resolution`.
 *
 * Instances are immutable; [withLine] returns a modified copy.
 */
class PgmMap(
    val width: Int,
    val height: Int,
    val resolution: Double,
    val originX: Double,
    val originY: Double,
    private val pixels: ByteArray,
    private val extra: ByteArray
) {
    init {
        require(pixels.size == width * height) { "expected ${width * height} pixels, got ${pixels.size}" }
        require(extra.size == EXTRA_BYTES) { "expected $EXTRA_BYTES trailing bytes, got ${extra.size}" }
    }

    fun inBounds(px: Int, py: Int): Boolean = px in 0 until width && py in 0 until height

    /** Pixel value at column [px], row [py] (row 0 = top). */
    fun value(px: Int, py: Int): Int = pixels[py * width + px].toInt() and 0xFF

    /** Continuous pixel coordinates of a world position, as the map tool computes them. */
    fun toPixel(p: Point2D): Pair<Double, Double> =
        Pair((p.x - originX) / resolution, height - (p.y - originY) / resolution)

    /** World position of continuous pixel coordinates (inverse of [toPixel]). */
    fun toWorld(px: Double, py: Double): Point2D =
        Point2D(originX + px * resolution, originY + (height - py) * resolution)

    /** All pixels that are part of a no-go line, as (column, row). */
    fun noGoPixels(): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        for (py in 0 until height) for (px in 0 until width) {
            if (value(px, py) == VALUE_NO_GO) result += Pair(px, py)
        }
        return result
    }

    /**
     * A line that blocks the direct way between [a] and [b]: it crosses the midpoint of a–b at a right angle and extends
     * in both directions until it meets something that is not free space (wall, existing no-go line, unknown area),
     * plus [beyondObstacleM] so it overlaps the wall instead of leaving a gap. Each side is capped at [maxHalfLengthM].
     *
     * @return the two end points of the line, in world coordinates
     */
    fun lineAcross(a: Point2D, b: Point2D, maxHalfLengthM: Double = 8.0, beyondObstacleM: Double = 0.10): Pair<Point2D, Point2D> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy)
        require(length >= resolution) { "A and B are too close together" }
        val mid = Point2D((a.x + b.x) / 2, (a.y + b.y) / 2)
        val nx = -dy / length
        val ny = dx / length

        fun extend(sign: Int): Point2D {
            val step = resolution / 2
            var t = 0.0
            while (t < maxHalfLengthM) {
                val (px, py) = toPixel(Point2D(mid.x + sign * nx * t, mid.y + sign * ny * t))
                val ix = floor(px).toInt()
                val iy = floor(py).toInt()
                if (!inBounds(ix, iy)) break
                if (value(ix, iy) != VALUE_FREE) {
                    t += beyondObstacleM
                    break
                }
                t += step
            }
            return Point2D(mid.x + sign * nx * t, mid.y + sign * ny * t)
        }
        return Pair(extend(+1), extend(-1))
    }

    /** A copy with a no-go line from [from] to [to], [thicknessPx] pixels wide (square brush). */
    fun withLine(from: Point2D, to: Point2D, thicknessPx: Int): PgmMap {
        val copy = pixels.copyOf()
        val (fx, fy) = toPixel(from)
        val (tx, ty) = toPixel(to)
        var x0 = floor(fx).toInt()
        var y0 = floor(fy).toInt()
        val x1 = floor(tx).toInt()
        val y1 = floor(ty).toInt()
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        val ddx = abs(x1 - x0)
        val ddy = -abs(y1 - y0)
        var err = ddx + ddy
        val lo = -(thicknessPx - 1) / 2
        val hi = thicknessPx / 2
        // Bresenham over the pixel grid; every step stamps a thickness × thickness square.
        while (true) {
            for (ox in lo..hi) for (oy in lo..hi) {
                val px = x0 + ox
                val py = y0 + oy
                if (inBounds(px, py)) copy[py * width + px] = VALUE_NO_GO.toByte()
            }
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * err
            if (e2 >= ddy) { err += ddy; x0 += sx }
            if (e2 <= ddx) { err += ddx; y0 += sy }
        }
        return PgmMap(width, height, resolution, originX, originY, copy, extra)
    }

    /** Serialises in exactly the map tool's format ([MapUtils.saveRoverMapToPgm]): header, pixels, trailing bytes. */
    fun toBytes(): ByteArray {
        val header = "P5\n$width $height\n255\n".toByteArray(Charsets.US_ASCII)
        return header + pixels + extra
    }

    /** Length in metres between two world points; convenience for logging. */
    fun lengthOf(a: Point2D, b: Point2D): Double = max(0.0, hypot(b.x - a.x, b.y - a.y))

    companion object {
        const val VALUE_NO_GO = 0
        const val VALUE_WALL = 5
        const val VALUE_UNKNOWN = 150
        const val VALUE_FREE = 255
        private const val EXTRA_BYTES = 16

        fun file(mapName: String): File = File(RobotMapFile.mapDirectory(mapName), "navi_data/map.pgm")

        fun load(mapName: String): PgmMap = parse(file(mapName).readBytes())

        /** Parses the format described on the class. Throws on anything unexpected, so a bad file is never rewritten. */
        fun parse(bytes: ByteArray): PgmMap {
            var pos = 0
            fun token(): String {
                while (pos < bytes.size && bytes[pos].toInt().toChar().isWhitespace()) pos++
                val start = pos
                while (pos < bytes.size && !bytes[pos].toInt().toChar().isWhitespace()) pos++
                return String(bytes, start, pos - start, Charsets.US_ASCII)
            }
            check(token() == "P5") { "not a binary PGM (P5)" }
            val width = token().toInt()
            val height = token().toInt()
            check(token() == "255") { "unexpected max value" }
            pos++ // exactly one whitespace byte after the max value
            val pixelCount = width * height
            check(bytes.size - pos == pixelCount + EXTRA_BYTES) {
                "unexpected size: ${bytes.size - pos} bytes after the header, expected ${pixelCount + EXTRA_BYTES}"
            }
            val pixels = bytes.copyOfRange(pos, pos + pixelCount)
            val extra = bytes.copyOfRange(pos + pixelCount, bytes.size)
            val buf = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
            val resolution = buf.getDouble(0)
            val originX = buf.getFloat(8).toDouble()
            val originY = buf.getFloat(12).toDouble()
            check(resolution > 0 && resolution < 1) { "implausible resolution $resolution" }
            return PgmMap(width, height, resolution, originX, originY, pixels, extra)
        }
    }
}
