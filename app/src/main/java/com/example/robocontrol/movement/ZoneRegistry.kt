package com.example.robocontrol.movement

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Stores the [MapZones] of each map as JSON in app-private storage, one file per map, encrypted when a [cipher] is set.
 * Writes go to a temporary file that is synced and renamed.
 * @param baseDir folder for the files
 * @param cipher encryption; null = plain JSON
 */
class ZoneRegistry(private val baseDir: File, private val cipher: ZoneCipher? = null) {
    private val dir: File by lazy {
        File(baseDir, ZONES_DIR).apply { if (!exists()) mkdirs() }
    }

    private val extension get() = if (cipher != null) ".zones" else ".json"

    /**
     * The file that holds the zones of [mapName].
     * @param mapName map name
     * @return the file
     */
    private fun fileFor(mapName: String) = File(dir, "${sanitize(mapName)}$extension")

    /**
     * Associated data that binds a file to its name, i.e. to its map.
     * @param file file
     * @return the associated data
     */
    private fun associatedData(file: File) = "roboguard-zones:${file.name}".toByteArray(Charsets.UTF_8)

    /**
     * Reads [f] and decrypts it when a cipher is set; plain JSON otherwise.
     * @param f file
     * @return the JSON text
     */
    private fun readText(f: File): String =
        cipher?.decrypt(f.readBytes(), associatedData(f))?.toString(Charsets.UTF_8) ?: f.readText()

    /**
     * Returns the stored zones for [mapName], or an empty set if none exist yet.
     * @param mapName map name
     * @return the zones
     */
    fun load(mapName: String): MapZones {
        val f = fileFor(mapName)
        if (!f.exists()) return MapZones(mapName)
        return try {
            parse(JSONObject(readText(f)), mapName)
        } catch (e: Exception) {
            throw ZoneStoreCorrupt(mapName, e)
        }
    }

    /**
     * Like [load] but swallows corruption, returning null so the caller can react.
     * @param mapName map name
     * @return the zones, or null if the file is unreadable
     */
    fun loadOrNull(mapName: String): MapZones? = try {
        load(mapName)
    } catch (_: ZoneStoreCorrupt) {
        null
    }

    /**
     * Writes all zones of one map. Temp file, fsync, then rename, so a crash never leaves a half-written file.
     * @param zones zones of one map
     */
    fun save(zones: MapZones) {
        val f = fileFor(zones.mapName)
        val tmp = File(f.parentFile, f.name + ".tmp")
        val json = serialize(zones).toString(2).toByteArray(Charsets.UTF_8)
        val bytes = cipher?.encrypt(json, associatedData(f)) ?: json
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        // Atomic replace, so a crash mid-write cannot leave a partial file.
        if (!tmp.renameTo(f)) {
            f.writeBytes(tmp.readBytes())
            tmp.delete()
        }
    }

    /**
     * Names of all maps that have stored zones. Unreadable files are skipped.
     * @return the map names
     */
    fun listMaps(): List<String> =
        dir.listFiles { _, n -> n.endsWith(extension) }
            ?.mapNotNull { runCatching { JSONObject(readText(it)).optString(KEY_MAP) }.getOrNull() }
            ?.filter { it.isNotBlank() }
            ?.sorted()
            ?: emptyList()

    /**
     * Deletes the stored zones of [mapName].
     * @param mapName map name
     * @return true if a file was deleted
     */
    fun delete(mapName: String): Boolean = fileFor(mapName).delete()

    /**
     * True if a stored file exists for [mapName] (readable or not).
     * @param mapName map name
     * @return true if a file exists
     */
    fun exists(mapName: String): Boolean = fileFor(mapName).exists()

    /**
     * Turns [m] into the JSON that is stored (map name, zones with polygon, privacy level and entry point).
     * @param m zones
     * @return the JSON object
     */
    private fun serialize(m: MapZones): JSONObject {
        val zoneArr = JSONArray()
        m.zones.forEach { z ->
            val poly = JSONArray()
            z.polygon.forEach { p ->
                poly.put(JSONObject().put("x", p.x).put("y", p.y))
            }
            zoneArr.put(
                JSONObject()
                    .put("name", z.name)
                    .put("privacy", z.privacy.name)
                    .put("polygon", poly)
                    .apply { z.entryPoint?.let { put("entryPoint", it) } }
            )
        }
        return JSONObject()
            .put(KEY_VERSION, SCHEMA_VERSION)
            .put(KEY_MAP, m.mapName)
            .put(KEY_ZONES, zoneArr)
    }

    /**
     * Reads the JSON written by [serialize]; [fallbackMap] is used when the file names no map.
     * @param o stored JSON
     * @param fallbackMap map name used when the file names none
     * @return the zones
     */
    private fun parse(o: JSONObject, fallbackMap: String): MapZones {
        val mapName = o.optString(KEY_MAP).ifBlank { fallbackMap }
        val arr = o.optJSONArray(KEY_ZONES) ?: JSONArray()
        val zones = buildList {
            for (i in 0 until arr.length()) {
                val zo = arr.getJSONObject(i)
                val polyArr = zo.getJSONArray("polygon")
                val poly = buildList {
                    for (j in 0 until polyArr.length()) {
                        val p = polyArr.getJSONObject(j)
                        add(Point2D(p.getDouble("x"), p.getDouble("y")))
                    }
                }
                add(
                    Zone(
                        name = zo.getString("name"),
                        polygon = poly,
                        privacy = runCatching {
                            PrivacyLevel.valueOf(zo.optString("privacy", "OPEN"))
                        }.getOrDefault(PrivacyLevel.OPEN),
                        entryPoint = zo.optString("entryPoint").ifBlank { null }
                    )
                )
            }
        }
        return MapZones(mapName, zones)
    }

    /**
     * File-safe version of a map name: other characters become "_", at most 120 characters.
     * @param name map name
     * @return the safe name
     */
    private fun sanitize(name: String) =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "unnamed" }

    companion object {
        const val ZONES_DIR = "zones"
        const val SCHEMA_VERSION = 1
        private const val KEY_VERSION = "schemaVersion"
        private const val KEY_MAP = "mapName"
        private const val KEY_ZONES = "zones"
    }
}

/**
 * Thrown when a zone file exists but cannot be read.
 * @param mapName map name
 * @param cause original error
 */
class ZoneStoreCorrupt(mapName: String, cause: Throwable) :
    Exception("Zone definitions for map '$mapName' are unreadable", cause)

/**
 * Builds a zone from positions the robot was driven to.
 * @param name name of the zone
 */
class ZoneBuilder(val name: String) {
    private val points = mutableListOf<Point2D>()

    /**
     * Adds a corner at map position [p].
     * @param p map position
     * @return this builder
     */
    fun addPoint(p: Point2D) = apply { points.add(p) }

    /**
     * Adds the robot's current position as a corner.
     * @param pose robot pose
     * @return this builder
     */
    fun addCurrentPosition(pose: RobotPose) = apply { points.add(pose.point) }

    /**
     * Removes all corners collected so far.
     * @return this builder
     */
    fun clear() = apply { points.clear() }

    val vertexCount: Int get() = points.size

    /**
     * Whether there are enough corners (at least 3) for a polygon.
     * @return true if a polygon can be built
     */
    fun canBuild(): Boolean = points.size >= 3

    /**
     * Builds the zone from the collected corners.
     * @param privacy how the zone is treated by [PrivacyGuard].
     * @param entryPoint optional RobotOS place used to enter the zone.
     * @throws IllegalStateException if fewer than 3 corners were added.
     * @return the zone
     */
    fun build(privacy: PrivacyLevel = PrivacyLevel.OPEN, entryPoint: String? = null): Zone {
        check(canBuild()) { "Need at least 3 corners, have ${points.size}" }
        return Zone(name, points.toList(), privacy, entryPoint)
    }

    /** Axis-aligned rectangle from two opposite corners. */
    companion object {
        /**
         * An axis-aligned rectangle zone spanned by two opposite corners.
         * @param name zone name
         * @param corner first corner
         * @param opposite opposite corner
         * @param privacy privacy level
         * @param entryPoint RobotOS place to enter by, or null
         * @return the zone
         */
        fun rectangle(
            name: String,
            corner: Point2D,
            opposite: Point2D,
            privacy: PrivacyLevel = PrivacyLevel.OPEN,
            entryPoint: String? = null
        ): Zone {
            val minX = minOf(corner.x, opposite.x)
            val maxX = maxOf(corner.x, opposite.x)
            val minY = minOf(corner.y, opposite.y)
            val maxY = maxOf(corner.y, opposite.y)
            return Zone(
                name = name,
                polygon = listOf(
                    Point2D(minX, minY),
                    Point2D(maxX, minY),
                    Point2D(maxX, maxY),
                    Point2D(minX, maxY)
                ),
                privacy = privacy,
                entryPoint = entryPoint
            )
        }
    }
}
