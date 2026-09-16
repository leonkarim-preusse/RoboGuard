package com.example.robocontrol.movement

import android.os.Environment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A rectangle of map cells, inclusive on both ends. */
data class CellBounds(val minCol: Int, val maxCol: Int, val minRow: Int, val maxRow: Int) {
    val width: Int get() = maxCol - minCol + 1
    val height: Int get() = maxRow - minRow + 1
}

/**
 * A RobotOS navigation map, read from the robot's storage.
 *
 * RobotOS keeps each map in `/sdcard/robot/map/<mapName>/`. The SDK has no call that returns a map's resolution
 * and origin, but `navi_data/probabilitymap.data` contains both (format decoded on robot ZTT18P1000A0, 2026-09-16):
 *
 * ```
 * int32 little-endian   length of the rest of the file
 * protobuf message:
 *   field 1 (double)    resolution in metres per cell       e.g. 0.05
 *   field 2 (varint)    width in cells                      e.g. 660
 *   field 3 (varint)    height in cells                     e.g. 600
 *   field 4 (double)    origin x in metres (cell 0,0 corner) e.g. -16.0
 *   field 5 (double)    origin y in metres                  e.g. -15.0
 *   field 6 (bytes)     width × height uint16 little-endian cell values
 * ```
 * Cells are stored row by row starting at the **lowest** world y, so row 0 is the bottom of the map.
 * Cell values: 0 = unknown, 1 = free, larger = more likely occupied (up to 32767).
 *
 * Coordinates are the same as the SDK's poses and places (`getCurrentPose`, `getPlaceList`), in metres.
 *
 * Not included: the no-go lines drawn in the map tool. They are only in `navi_data/map.pgm` (pixel value 0),
 * whose scale and origin are not yet known (see CLAUDE.md).
 *
 * @property cells width × height values, row 0 = lowest world y
 */
class RobotMapFile(
    val mapName: String,
    val resolution: Double,
    val width: Int,
    val height: Int,
    val originX: Double,
    val originY: Double,
    private val cells: ShortArray
) {
    init {
        require(resolution > 0) { "resolution must be positive, got $resolution" }
        require(cells.size == width * height) { "expected ${width * height} cells, got ${cells.size}" }
    }

    /** Raw cell value, 0..65535. Row 0 is the lowest world y. */
    fun cellValue(col: Int, row: Int): Int = cells[row * width + col].toInt() and 0xFFFF

    /** Continuous cell coordinates (column, row) of a world position; the integer part is the cell. */
    fun toCell(p: Point2D): Pair<Double, Double> = Pair((p.x - originX) / resolution, (p.y - originY) / resolution)

    /** World position of continuous cell coordinates. */
    fun toWorld(col: Double, row: Double): Point2D = Point2D(originX + col * resolution, originY + row * resolution)

    /**
     * The smallest rectangle containing every mapped (non-unknown) cell, grown by [margin] cells.
     * Maps are mostly unknown space around the recorded area, so this is what is worth displaying.
     */
    fun knownBounds(margin: Int): CellBounds {
        var minCol = width
        var maxCol = -1
        var minRow = height
        var maxRow = -1
        for (row in 0 until height) {
            for (col in 0 until width) {
                if (cells[row * width + col].toInt() != UNKNOWN) {
                    if (col < minCol) minCol = col
                    if (col > maxCol) maxCol = col
                    if (row < minRow) minRow = row
                    if (row > maxRow) maxRow = row
                }
            }
        }
        if (maxCol < 0) return CellBounds(0, width - 1, 0, height - 1)
        return CellBounds(
            (minCol - margin).coerceAtLeast(0), (maxCol + margin).coerceAtMost(width - 1),
            (minRow - margin).coerceAtLeast(0), (maxRow + margin).coerceAtMost(height - 1)
        )
    }

    companion object {
        const val UNKNOWN = 0
        const val FREE = 1

        /** `/sdcard/robot/map/<mapName>/`. Reading it needs READ_EXTERNAL_STORAGE. */
        fun mapDirectory(mapName: String): File = File(Environment.getExternalStorageDirectory(), "robot/map/$mapName")

        /** Loads [mapName]'s probability map. Throws if the file is missing, unreadable or not in the expected format. */
        fun load(mapName: String): RobotMapFile =
            parse(mapName, File(mapDirectory(mapName), "navi_data/probabilitymap.data").readBytes())

        /** Parses the file format described on the class. Unknown protobuf fields are skipped. */
        fun parse(mapName: String, bytes: ByteArray): RobotMapFile {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.int // length prefix; not needed, the message is read until the end of the file

            var resolution: Double? = null
            var width: Int? = null
            var height: Int? = null
            var originX: Double? = null
            var originY: Double? = null
            var cells: ShortArray? = null

            while (buf.remaining() > 0) {
                val key = readVarint(buf)
                val field = (key ushr 3).toInt()
                when ((key and 0x7).toInt()) {
                    WIRE_VARINT -> {
                        val value = readVarint(buf)
                        when (field) {
                            2 -> width = value.toInt()
                            3 -> height = value.toInt()
                        }
                    }
                    WIRE_FIXED64 -> {
                        val value = buf.double
                        when (field) {
                            1 -> resolution = value
                            4 -> originX = value
                            5 -> originY = value
                        }
                    }
                    WIRE_LENGTH_DELIMITED -> {
                        val length = readVarint(buf).toInt()
                        if (field == 6) {
                            val data = ShortArray(length / 2)
                            buf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data)
                            cells = data
                        }
                        buf.position(buf.position() + length)
                    }
                    WIRE_FIXED32 -> buf.position(buf.position() + 4)
                    else -> break // trailing bytes that are not a field; everything needed has been read
                }
            }

            return RobotMapFile(
                mapName = mapName,
                resolution = resolution ?: error("resolution (field 1) missing"),
                width = width ?: error("width (field 2) missing"),
                height = height ?: error("height (field 3) missing"),
                originX = originX ?: error("origin x (field 4) missing"),
                originY = originY ?: error("origin y (field 5) missing"),
                cells = cells ?: error("cell data (field 6) missing")
            )
        }

        private const val WIRE_VARINT = 0
        private const val WIRE_FIXED64 = 1
        private const val WIRE_LENGTH_DELIMITED = 2
        private const val WIRE_FIXED32 = 5

        private fun readVarint(buf: ByteBuffer): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf.get().toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
    }
}
