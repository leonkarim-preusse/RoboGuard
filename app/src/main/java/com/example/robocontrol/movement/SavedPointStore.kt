package com.example.robocontrol.movement

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * A named position saved on one map.
 * @property name name of the location
 * @property position map coordinates in metres
 * @property theta heading of the robot when the position was saved, in radians
 */
data class SavedPoint(val name: String, val position: Point2D, val theta: Double)

/**
 * Thrown when stored locations exist but cannot be read.
 * @param mapName map name
 * @param cause original error
 */
class PointStoreCorrupt(mapName: String, cause: Throwable) :
    Exception("Saved locations for map '$mapName' are unreadable", cause)

/**
 * Stores [SavedPoint]s per map, encrypted with an own Keystore key.
 * @param context used for the storage folder
 * @param cipher encryption of the files
 */
class SavedPointStore(context: Context, private val cipher: ZoneCipher = KeystoreZoneCipher(KEY_ALIAS)) {
    private val directory = File(context.filesDir, "robocontrol/points")

    /**
     * All points stored for [mapName]; empty if none were ever saved.
     * @param mapName map name
     * @throws PointStoreCorrupt if a stored file exists but cannot be read (damaged, or the Keystore key is gone)
     * @return the points
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
        save(mapName, points)
        legacy.delete()
        return points
    }

    /**
     * Replaces the stored points for [mapName] with [points].
     * @param mapName map name
     * @param points points to store
     */
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

    /**
     * Deletes all stored points for [mapName], including an unreadable file. Returns true if nothing is left.
     * @param mapName map name
     * @return true if nothing is left
     */
    @Synchronized
    fun delete(mapName: String): Boolean {
        val file = fileFor(mapName)
        val legacy = legacyFileFor(mapName)
        return (!file.exists() || file.delete()) && (!legacy.exists() || legacy.delete())
    }

    /**
     * Reads the JSON list of saved locations.
     * @param json stored JSON
     * @return the points
     */
    private fun parse(json: String): List<SavedPoint> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SavedPoint(o.getString("name"), Point2D(o.getDouble("x"), o.getDouble("y")), o.optDouble("theta", 0.0))
        }
    }

    /**
     * Associated data that binds a file to its name, i.e. to its map.
     * @param file file
     * @return the associated data
     */
    private fun associatedData(file: File) = "roboguard-points:${file.name}".toByteArray(Charsets.UTF_8)

    /**
     * File-safe version of a map name.
     * @param mapName map name
     * @return the safe name
     */
    private fun safeName(mapName: String) = mapName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * The encrypted file that holds the locations of [mapName].
     * @param mapName map name
     * @return the file
     */
    private fun fileFor(mapName: String): File = File(directory, safeName(mapName) + ".points")

    /**
     * The old plaintext file of [mapName], read once and then replaced by the encrypted one.
     * @param mapName map name
     * @return the file
     */
    private fun legacyFileFor(mapName: String): File = File(directory, safeName(mapName) + ".json")

    companion object {
        const val KEY_ALIAS = "robocontrol_saved_points"
    }
}
