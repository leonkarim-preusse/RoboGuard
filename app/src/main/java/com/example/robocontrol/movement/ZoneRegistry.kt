package com.example.robocontrol.movement

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Persistence for [MapZones].
 *
 * Stored as JSON in app-private storage (`filesDir`), one file per RobotOS map.
 * App-private on purpose: zone definitions say which rooms in someone's home are
 * bathroom and bedroom, which is exactly the kind of data the thesis argues
 * should not leave the device. Combined with `allowBackup=false` in the
 * manifest, these files never reach cloud backup.
 *
 * Uses org.json (part of Android) rather than adding a serialization dependency.
 *
 * With a [cipher] (RoboGuard's "Navigation and Map" screen uses [KeystoreZoneCipher]) the JSON is encrypted and files
 * end in `.zones` instead of `.json`. Note: RoboGuard's manifest currently has `allowBackup="true"`, so the encryption,
 * not the storage location, is what keeps a backup copy unreadable.
 *
 * Writes go to a temp file that is flushed to disk (fsync) and then renamed over the old file, so a power cut leaves
 * either the old or the new version, never half a file.
 *
 * @param baseDir normally `context.filesDir`. Injected so tests can use a temp dir.
 * @param cipher encrypts file contents; null = plain JSON (as before)
 */
class ZoneRegistry(private val baseDir: File, private val cipher: ZoneCipher? = null) {

    private val dir: File by lazy {
        File(baseDir, ZONES_DIR).apply { if (!exists()) mkdirs() }
    }

    private val extension get() = if (cipher != null) ".zones" else ".json"

    private fun fileFor(mapName: String) = File(dir, "${sanitize(mapName)}$extension")

    /** Authenticated with the encrypted content: binds a file to its own name, i.e. to its map. */
    private fun associatedData(file: File) = "roboguard-zones:${file.name}".toByteArray(Charsets.UTF_8)

    private fun readText(f: File): String =
        cipher?.decrypt(f.readBytes(), associatedData(f))?.toString(Charsets.UTF_8) ?: f.readText()

    /** Returns the stored zones for [mapName], or an empty set if none exist yet. */
    fun load(mapName: String): MapZones {
        val f = fileFor(mapName)
        if (!f.exists()) return MapZones(mapName)
        return try {
            parse(JSONObject(readText(f)), mapName)
        } catch (e: Exception) {
            // A corrupt file must not brick navigation. Fail closed to "no zones
            // known" and let the caller decide: PrivacyGuard treats an empty
            // registry as "nothing is marked private", so the caller SHOULD
            // surface this rather than silently driving everywhere.
            throw ZoneStoreCorrupt(mapName, e)
        }
    }

    /** Like [load] but swallows corruption, returning null so the caller can react. */
    fun loadOrNull(mapName: String): MapZones? = try {
        load(mapName)
    } catch (_: ZoneStoreCorrupt) {
        null
    }

    fun save(zones: MapZones) {
        val f = fileFor(zones.mapName)
        val tmp = File(f.parentFile, f.name + ".tmp")
        val json = serialize(zones).toString(2).toByteArray(Charsets.UTF_8)
        val bytes = cipher?.encrypt(json, associatedData(f)) ?: json
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        // Atomic replace so a crash mid-write cannot leave a half-file that
        // would later read as "no private zones".
        if (!tmp.renameTo(f)) {
            f.writeBytes(tmp.readBytes())
            tmp.delete()
        }
    }

    fun listMaps(): List<String> =
        dir.listFiles { _, n -> n.endsWith(extension) }
            ?.mapNotNull { runCatching { JSONObject(readText(it)).optString(KEY_MAP) }.getOrNull() }
            ?.filter { it.isNotBlank() }
            ?.sorted()
            ?: emptyList()

    fun delete(mapName: String): Boolean = fileFor(mapName).delete()

    /** True if a stored file exists for [mapName] (readable or not). */
    fun exists(mapName: String): Boolean = fileFor(mapName).exists()

    // ---- serialization -------------------------------------------------

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

/** Thrown when a zone file exists but cannot be read. Never ignore silently. */
class ZoneStoreCorrupt(mapName: String, cause: Throwable) :
    Exception("Zone definitions for map '$mapName' are unreadable", cause)

/**
 * Builds a zone from points the robot was physically driven to.
 *
 * This is the practical way to define a room without a map editor: drive the
 * robot to each corner, call [addCurrentPosition], then [build]. It is also how
 * the thesis demo can mark "the bathroom" without any OrionStar tooling.
 */
class ZoneBuilder(val name: String) {
    private val points = mutableListOf<Point2D>()

    fun addPoint(p: Point2D) = apply { points.add(p) }

    fun addCurrentPosition(pose: RobotPose) = apply { points.add(pose.point) }

    fun clear() = apply { points.clear() }

    val vertexCount: Int get() = points.size

    fun canBuild(): Boolean = points.size >= 3

    fun build(privacy: PrivacyLevel = PrivacyLevel.OPEN, entryPoint: String? = null): Zone {
        check(canBuild()) { "Need at least 3 corners, have ${points.size}" }
        return Zone(name, points.toList(), privacy, entryPoint)
    }

    /** Axis-aligned rectangle from two opposite corners — quickest useful case. */
    companion object {
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
