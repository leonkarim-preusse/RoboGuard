package com.example.robocontrol.movement

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * A named position recorded on one map, e.g. "Kitchen door".
 *
 * @property position map coordinates in metres, same frame as RobotOS poses and places
 * @property theta the robot's heading when the position was recorded, in radians
 */
data class SavedPoint(val name: String, val position: Point2D, val theta: Double)

/** Thrown when stored locations exist but cannot be read. Never treat this as "no locations": a save would erase them. */
class PointStoreCorrupt(mapName: String, cause: Throwable) :
    Exception("Saved locations for map '$mapName' are unreadable", cause)

/**
 * Stores [SavedPoint]s per map in RoboGuard's private storage, encrypted: `files/robocontrol/points/<map name>.points`.
 *
 * Same protection as the private areas ([ZoneRegistry] + [KeystoreZoneCipher]), with its own Keystore key alias
 * [KEY_ALIAS]: AES-256-GCM, file name authenticated, written to a temp file that is flushed to disk (fsync) and renamed.
 *
 * Deliberately NOT stored as RobotOS places (`RobotApi.setLocation`): places created that way cannot be deleted
 * through the SDK (`removeLocation` is a no-op in robotservice_12.3.jar), so every test location would stay in the
 * robot's shared map for good. Points are bound to a map name, because coordinates are only meaningful on the map
 * they were recorded on.
 *
 * Migration: older versions wrote plain `<map name>.json`. On load, such a file is read, saved encrypted and then
 * deleted.
 */
class SavedPointStore(context: Context, private val cipher: ZoneCipher = KeystoreZoneCipher(KEY_ALIAS)) {

    private val directory = File(context.filesDir, "robocontrol/points")

    /**
     * All points stored for [mapName]; empty if none were ever saved.
     * @throws PointStoreCorrupt if a stored file exists but cannot be read (damaged, or the Keystore key is gone)
     */
    @Synchronized
    fun load(mapName: String): List<SavedPoint> {
        val file = fileFor(mapName)
        if (file.exists()) {
            return try {
                parse(cipher.decrypt(file.readBytes(), associatedData(file)).toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw PointStoreCorrupt(mapName, e)
            }
        }
        val legacy = legacyFileFor(mapName)
        if (!legacy.exists()) return emptyList()
        val points = try {
            parse(legacy.readText())
        } catch (e: Exception) {
            throw PointStoreCorrupt(mapName, e)
        }
        // One-time migration: only delete the plain file once the encrypted copy is safely written.
        save(mapName, points)
        legacy.delete()
        return points
    }

    /** Replaces the stored points for [mapName] with [points]. */
    @Synchronized
    fun save(mapName: String, points: List<SavedPoint>) {
        directory.mkdirs()
        val array = JSONArray()
        points.forEach {
            array.put(JSONObject().put("name", it.name).put("x", it.position.x).put("y", it.position.y).put("theta", it.theta))
        }
        val target = fileFor(mapName)
        val temp = File(directory, target.name + ".tmp")
        val bytes = cipher.encrypt(array.toString(2).toByteArray(Charsets.UTF_8), associatedData(target))
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        check(temp.renameTo(target)) { "could not replace ${target.name}" }
    }

    /** Deletes all stored points for [mapName], including an unreadable file. Returns true if nothing is left. */
    @Synchronized
    fun delete(mapName: String): Boolean {
        val file = fileFor(mapName)
        val legacy = legacyFileFor(mapName)
        return (!file.exists() || file.delete()) && (!legacy.exists() || legacy.delete())
    }

    private fun parse(json: String): List<SavedPoint> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SavedPoint(o.getString("name"), Point2D(o.getDouble("x"), o.getDouble("y")), o.optDouble("theta", 0.0))
        }
    }

    private fun associatedData(file: File) = "roboguard-points:${file.name}".toByteArray(Charsets.UTF_8)

    /** Map names contain spaces and other characters; keep the file name to a safe character set. */
    private fun safeName(mapName: String) = mapName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun fileFor(mapName: String): File = File(directory, safeName(mapName) + ".points")

    private fun legacyFileFor(mapName: String): File = File(directory, safeName(mapName) + ".json")

    companion object {
        const val KEY_ALIAS = "robocontrol_saved_points"
    }
}
