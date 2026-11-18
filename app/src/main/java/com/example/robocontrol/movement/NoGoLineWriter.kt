package com.example.robocontrol.movement

import android.content.Context
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.CommandListener
import org.json.JSONObject
import java.io.File

/**
 * Writes no-go lines into a RobotOS map like the map tool does, and can restore the original. Blocking.
 * @param context used for the backup folder
 * @param log receives one line per step, including the SDK's answers
 * @param reqIdSource source of request ids
 */
class NoGoLineWriter(
    context: Context,
    private val log: (String) -> Unit,
    private val reqIdSource: () -> Int
) {
    private val backupRoot = File(context.filesDir, "robocontrol/map_backups")

    /**
     * Whether the original map.pgm of [mapName] has already been backed up.
     * @param mapName map name
     * @return true if a backup exists
     */
    fun hasBackup(mapName: String): Boolean = backupPgm(mapName).exists()

    /**
     * Draws a no-go line from [from] to [to] into [mapName]'s map and tells RobotOS about it.
     * @param mapName map name
     * @param from line start
     * @param to line end
     * @param thicknessPx line width in pixels
     * @return a short description of what was written
     */
    fun write(mapName: String, from: Point2D, to: Point2D, thicknessPx: Int = DEFAULT_THICKNESS_PX): String {
        val target = PgmMap.file(mapName)
        val current = PgmMap.parse(target.readBytes())
        ensureBackup(mapName, target)

        val updated = current.withLine(from, to, thicknessPx)
        val added = updated.noGoPixels().size - current.noGoPixels().size
        writeReplacing(target, updated.toBytes())
        log("map.pgm written: line (%.2f, %.2f) → (%.2f, %.2f), %.2f m, %d px wide, %d new no-go pixels"
            .format(from.x, from.y, to.x, to.y, current.lengthOf(from, to), thicknessPx, added))

        notifyRobotOs(mapName, forbidLineFlag = 1)
        return "no-go line written (%d new pixels)".format(added)
    }

    /**
     * Puts the original map.pgm and no-go flag back, as they were before RoboGuard's first change.
     * @param mapName map name
     * @return a message for the tester
     */
    fun restore(mapName: String): String {
        val backup = backupPgm(mapName)
        check(backup.exists()) { "no backup for $mapName" }
        val original = backup.readBytes()
        PgmMap.parse(original)
        writeReplacing(PgmMap.file(mapName), original)
        val flag = runCatching { backupFlag(mapName).readText().trim().toInt() }.getOrDefault(1)
        log("map.pgm restored from backup (${original.size} bytes), original no-go flag $flag")
        notifyRobotOs(mapName, forbidLineFlag = flag)
        return "original map restored"
    }

    /**
     * Backup folder of one map inside the app's files.
     * @param mapName map name
     * @return the folder
     */
    private fun backupDir(mapName: String) = File(backupRoot, mapName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    /**
     * The backed up original map.pgm.
     * @param mapName map name
     * @return the file
     */
    private fun backupPgm(mapName: String) = File(backupDir(mapName), "map.pgm")
    /**
     * The backed up original forbid-line flag.
     * @param mapName map name
     * @return the file
     */
    private fun backupFlag(mapName: String) = File(backupDir(mapName), "forbidLine.txt")

    /**
     * Copies the original once, before RoboGuard changes anything.
     * @param mapName map name
     * @param source original file
     */
    private fun ensureBackup(mapName: String, source: File) {
        if (backupPgm(mapName).exists()) return
        backupDir(mapName).mkdirs()
        source.copyTo(backupPgm(mapName))
        backupFlag(mapName).writeText(readForbidLineFlag(mapName).toString())
        log("backup of the original map.pgm saved in app storage (${backupPgm(mapName).length()} bytes)")
    }

    /**
     * `forbidLine` from mapinfo.json, whose "mapInfo" field is itself a JSON string.
     * @param mapName map name
     * @return the flag
     */
    private fun readForbidLineFlag(mapName: String): Int = runCatching {
        val outer = JSONObject(File(RobotMapFile.mapDirectory(mapName), "mapinfo.json").readText())
        JSONObject(outer.getString("mapInfo")).optInt("forbidLine", 0)
    }.getOrDefault(0)

    /**
     * Replaces [target] through a temp file and a rename; falls back to a plain write if the rename fails.
     * @param target file to replace
     * @param bytes new content
     */
    private fun writeReplacing(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".roboguard.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
    }

    /**
     * Tells RobotOS about the no-go lines and a new update time.
     * @param mapName map name
     * @param forbidLineFlag flag to set
     */
    private fun notifyRobotOs(mapName: String, forbidLineFlag: Int) {
        val api = RobotApi.getInstance()
        val flagCode = api.setMapForbidLineFlag(reqIdSource(), mapName, forbidLineFlag, answer("setMapForbidLineFlag($forbidLineFlag)"))
        // The map tool passes seconds (TimeUtil.getCurTimeSecond).
        val now = System.currentTimeMillis() / 1000
        val timeCode = api.setMapUpdateTime(reqIdSource(), mapName, now, answer("setMapUpdateTime($now)"))
        log("RobotOS notified: setMapForbidLineFlag → $flagCode, setMapUpdateTime → $timeCode (negative = rejected)")
    }

    /**
     * Creates a listener that logs the SDK's answer.
     * @param call name of the SDK call
     * @return the listener
     */
    private fun answer(call: String) = object : CommandListener() {
        /**
         * Logs the result.
         * @param result result code
         * @param message message
         * @param extraData extra data
         */
        override fun onResult(result: Int, message: String?, extraData: String?) = log("$call answered: result=$result message=$message")
        /**
         * Logs the error.
         * @param errorCode error code
         * @param errorString error text
         * @param extraData extra data
         */
        override fun onError(errorCode: Int, errorString: String?, extraData: String?) = log("$call error: $errorCode $errorString")
    }

    companion object {
        const val DEFAULT_THICKNESS_PX = 2
    }
}
