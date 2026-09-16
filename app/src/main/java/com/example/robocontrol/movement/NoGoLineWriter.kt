package com.example.robocontrol.movement

import android.content.Context
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.CommandListener
import org.json.JSONObject
import java.io.File

/**
 * Writes no-go lines into a RobotOS map the same way RobotOS's own map tool does, so the robot's route planner avoids
 * them. Sequence copied from the map tool's `MapReqProcessor.handleSaveMap` (see CLAUDE.md):
 *  1. write `navi_data/map.pgm` with the line drawn in (pixel value 0)
 *  2. `RobotApi.setMapForbidLineFlag(reqId, mapName, 1, listener)`
 *  3. `RobotApi.setMapUpdateTime(reqId, mapName, <now in seconds>, listener)`
 *
 * Safety:
 *  - Before the first change to a map, the original `map.pgm` and its original no-go flag are copied into RoboGuard's
 *    private storage (`files/robocontrol/map_backups/<map>/`). Later writes never overwrite that copy, so [restore]
 *    always returns to the state before RoboGuard touched the map.
 *  - The new file is parsed from the current file (a malformed file is rejected, never rewritten) and written to a
 *    temporary file first, then renamed over the original.
 *
 * Needs WRITE_EXTERNAL_STORAGE and SDK control (app started from the robot's home launcher).
 * UNVERIFIED on the robot: whether navigation uses the new line immediately or only after a map reload / relocalization.
 *
 * All methods block (file I/O, binder calls); call them off the main thread.
 *
 * @param log receives one line per step, including the SDK's asynchronous answers
 */
class NoGoLineWriter(
    context: Context,
    private val log: (String) -> Unit,
    private val reqIdSource: () -> Int
) {
    private val backupRoot = File(context.filesDir, "robocontrol/map_backups")

    fun hasBackup(mapName: String): Boolean = backupPgm(mapName).exists()

    /**
     * Draws a no-go line from [from] to [to] into [mapName]'s map and tells RobotOS about it.
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

    /** Puts the original map.pgm and no-go flag back, as they were before RoboGuard's first change. */
    fun restore(mapName: String): String {
        val backup = backupPgm(mapName)
        check(backup.exists()) { "no backup for $mapName" }
        val original = backup.readBytes()
        PgmMap.parse(original) // refuse to restore a corrupt backup
        writeReplacing(PgmMap.file(mapName), original)
        val flag = runCatching { backupFlag(mapName).readText().trim().toInt() }.getOrDefault(1)
        log("map.pgm restored from backup (${original.size} bytes), original no-go flag $flag")
        notifyRobotOs(mapName, forbidLineFlag = flag)
        return "original map restored"
    }

    // ---- internals ----------------------------------------------------------------

    private fun backupDir(mapName: String) = File(backupRoot, mapName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    private fun backupPgm(mapName: String) = File(backupDir(mapName), "map.pgm")
    private fun backupFlag(mapName: String) = File(backupDir(mapName), "forbidLine.txt")

    /** Copies the original only once: the first backup is the state before RoboGuard changed anything. */
    private fun ensureBackup(mapName: String, source: File) {
        if (backupPgm(mapName).exists()) return
        backupDir(mapName).mkdirs()
        source.copyTo(backupPgm(mapName))
        backupFlag(mapName).writeText(readForbidLineFlag(mapName).toString())
        log("backup of the original map.pgm saved in app storage (${backupPgm(mapName).length()} bytes)")
    }

    /** `forbidLine` from mapinfo.json, whose "mapInfo" field is itself a JSON string. */
    private fun readForbidLineFlag(mapName: String): Int = runCatching {
        val outer = JSONObject(File(RobotMapFile.mapDirectory(mapName), "mapinfo.json").readText())
        JSONObject(outer.getString("mapInfo")).optInt("forbidLine", 0)
    }.getOrDefault(0)

    private fun writeReplacing(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".roboguard.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
    }

    private fun notifyRobotOs(mapName: String, forbidLineFlag: Int) {
        val api = RobotApi.getInstance()
        val flagCode = api.setMapForbidLineFlag(reqIdSource(), mapName, forbidLineFlag, answer("setMapForbidLineFlag($forbidLineFlag)"))
        // The map tool passes seconds (TimeUtil.getCurTimeSecond).
        val now = System.currentTimeMillis() / 1000
        val timeCode = api.setMapUpdateTime(reqIdSource(), mapName, now, answer("setMapUpdateTime($now)"))
        log("RobotOS notified: setMapForbidLineFlag → $flagCode, setMapUpdateTime → $timeCode (negative = rejected)")
    }

    /** Logs the SDK's asynchronous answer. Only the 3-argument callbacks: the SDK calls both variants (CLAUDE.md). */
    private fun answer(call: String) = object : CommandListener() {
        override fun onResult(result: Int, message: String?, extraData: String?) = log("$call answered: result=$result message=$message")
        override fun onError(errorCode: Int, errorString: String?, extraData: String?) = log("$call error: $errorCode $errorString")
    }

    companion object {
        /** 2 px = 10 cm at 0.05 m/px; thick enough that a grid planner cannot slip through diagonally. */
        const val DEFAULT_THICKNESS_PX = 2
    }
}
