package com.example.robocontrol.movement

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A named position recorded on one map, e.g. "Kitchen door".
 *
 * @property position map coordinates in metres, same frame as RobotOS poses and places
 * @property theta the robot's heading when the position was recorded, in radians
 */
data class SavedPoint(val name: String, val position: Point2D, val theta: Double)

/**
 * Stores [SavedPoint]s per map in RoboGuard's private storage: `files/robocontrol/points/<map name>.json`.
 *
 * Deliberately NOT stored as RobotOS places (`RobotApi.setLocation`): places created that way cannot be deleted
 * through the SDK (`removeLocation` is a no-op in robotservice_12.3.jar), so every test location would stay in the
 * robot's shared map for good. Points are bound to a map name, because coordinates are only meaningful on the map
 * they were recorded on.
 */
class SavedPointStore(context: Context) {

    private val directory = File(context.filesDir, "robocontrol/points")

    /** All points stored for [mapName]; empty if there are none or the file cannot be read. */
    fun load(mapName: String): List<SavedPoint> {
        val file = fileFor(mapName)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SavedPoint(o.getString("name"), Point2D(o.getDouble("x"), o.getDouble("y")), o.optDouble("theta", 0.0))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Replaces the stored points for [mapName] with [points]. Written to a temporary file first and then renamed,
     * so a crash while writing cannot leave a half-written file behind.
     */
    fun save(mapName: String, points: List<SavedPoint>) {
        directory.mkdirs()
        val array = JSONArray()
        points.forEach {
            array.put(JSONObject().put("name", it.name).put("x", it.position.x).put("y", it.position.y).put("theta", it.theta))
        }
        val target = fileFor(mapName)
        val temp = File(directory, target.name + ".tmp")
        temp.writeText(array.toString(2))
        check(temp.renameTo(target)) { "could not replace ${target.name}" }
    }

    /** Map names contain spaces and other characters; keep the file name to a safe character set. */
    private fun fileFor(mapName: String): File =
        File(directory, mapName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
}
