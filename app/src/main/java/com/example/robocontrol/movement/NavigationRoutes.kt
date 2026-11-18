package com.example.robocontrol.movement

import android.graphics.Bitmap
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/*
 * Navigation routes for the phone app, served from the shared [NavigationHub], so the phone and the robot's screen show
 * the same state. RobotServerService mounts them with its `secureGet` / `securePost` helpers, so every call is
 * authenticated like /save (client id and HMAC signature over the request body, empty for GET). This file only holds the
 * handlers.
 *
 * Routes:
 *  - `GET  /nav/state`    the whole screen state as JSON (stateJson)
 *  - `GET  /nav/map.json` size, resolution and world coordinates of the rendered map
 *  - `GET  /nav/map.png`  that map as a PNG
 *  - `POST /nav/command`  one command as JSON {"action": …} (handleCommand); answers with the new state
 *
 * Every command is noted in NavigationHub.noteRemoteCommand, so the robot's screen shows that it is steered remotely.
 */
/**
 * The whole state of the navigation screen as JSON text.
 * @return the JSON text
 */
fun navigationStateJson(): String = stateJson().toString()

/**
 * Size, resolution and world coordinates of the rendered map.
 * @return the JSON text
 */
fun navigationMapJson(): String = mapJson().toString()

/**
 * The rendered map as PNG bytes, or null while no map is loaded.
 * @return the PNG, or null
 */
fun navigationMapPng(): ByteArray? {
    val rendered = NavigationHub.current?.rendered?.value ?: return null
    return ByteArrayOutputStream().use { out ->
        rendered.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}

/**
 * Runs one command from the phone app.
 * @param body JSON body of the request
 * @param client name of the phone that sent it
 * @return HTTP status and the new state as JSON
 */
suspend fun navigationCommand(body: String, client: String): Pair<HttpStatusCode, String> {
    val request = runCatching { JSONObject(body) }.getOrNull()
        ?: return HttpStatusCode.BadRequest to errorJson("body is not JSON")
    NavigationHub.noteRemoteCommand(client)
    val result = handleCommand(request, client)
    val answer = stateJson().put("ok", result.ok).put("message", result.message ?: JSONObject.NULL)
    return (if (result.ok) HttpStatusCode.OK else HttpStatusCode.BadRequest) to answer.toString()
}

/**
 * The JSON answer for a failed request.
 * @param message error text
 * @return the JSON text
 */
private fun errorJson(message: String) = JSONObject().put("ok", false).put("error", message).toString()

/**
 * Outcome of one phone command: [ok] and the robot's own wording when it refused.
 * @property ok whether the command succeeded
 * @property message refusal text, or null
 */
private class CommandResult(val ok: Boolean, val message: String? = null)

/**
 * Runs one command from the phone app.
 * @param request the parsed command
 * @param client name of the phone that sent it
 * @return whether the command was accepted, with the robot's message
 */
private suspend fun handleCommand(request: JSONObject, client: String): CommandResult {
    val navigation = NavigationHub.current
        ?: return CommandResult(false, "the navigation is not running on the robot")
    val action = request.optString("action")
    val name = request.optString("name").takeIf { it.isNotBlank() }
    // Coordinates must be finite and inside the map; org.json parses a quoted "NaN" as a number.
    val position = if (request.has("x") && request.has("y")) {
        Point2D(request.optDouble("x"), request.optDouble("y")).takeIf { insideMap(navigation, it) }
            ?: return CommandResult(false, "x/y must be numbers inside the map")
    } else null

    return when (action) {
        "select" -> {
            val target = name?.let { wanted ->
                (navigation.places.value + navigation.customPoints.value).firstOrNull { it.name == wanted }
            } ?: position?.let { MapPoint("phone", it, saved = false) }
            if (target == null) CommandResult(false, "select needs a known name or x/y")
            else { navigation.select(target); CommandResult(true) }
        }
        "point" -> position?.let { navigation.handleMapTap(it); CommandResult(true) }
            ?: CommandResult(false, "point needs x/y")
        "drive" -> {
            val selected = navigation.selected.value
            val differs = selected != null && ((name != null && name != selected.name) ||
                (position != null && position.distanceTo(selected.position) > 0.05))
            if (differs) CommandResult(false, "the selected target changed to ${selected!!.name}; check it and press Drive again")
            else navigation.driveToSelected()?.let { CommandResult(false, it) } ?: CommandResult(true)
        }
        "stop" -> { navigation.stop("stopped from the phone"); CommandResult(true) }
        "speed" -> {
            val preset = SpeedPreset.entries.firstOrNull { it.name.equals(request.optString("preset"), ignoreCase = true) }
            if (preset == null) CommandResult(false, "unknown speed preset")
            else { navigation.setSpeed(preset); CommandResult(true) }
        }
        "clearPoints" -> { navigation.clearCustomPoints(); CommandResult(true) }
        "saveLocation" -> {
            if (name == null) CommandResult(false, "saveLocation needs a name")
            else navigation.saveCurrentPosition(name).let { CommandResult(it == null, it) }
        }
        "deleteLocation" -> { navigation.deleteSelectedLocation(); CommandResult(true) }
        "areaCircle" -> {
            if (name == null) CommandResult(false, "areaCircle needs a name")
            else navigation.makeSelectedAreaPrivate(name).let { CommandResult(it == null, it) }
        }
        "drawStart" -> { navigation.startDrawingArea(); CommandResult(true) }
        "drawUndo" -> { navigation.undoDrawingVertex(); CommandResult(true) }
        "drawCancel" -> { navigation.cancelDrawingArea(); CommandResult(true) }
        "drawFinish" -> {
            if (name == null) CommandResult(false, "drawFinish needs a name")
            else navigation.finishDrawingArea(name).let { CommandResult(it == null, it) }
        }
        "deleteArea" -> {
            if (name == null) CommandResult(false, "deleteArea needs a name")
            else {
                navigation.removePrivateArea(name)
                NavigationHub.log.i("phone", "private area \"$name\" deleted from the phone")
                CommandResult(true)
            }
        }
        "revokeArea" -> {
            if (name == null) CommandResult(false, "revokeArea needs a name")
            else { navigation.revokeCrossingPermission(name); CommandResult(true) }
        }
        "answerPrivacy" -> {
            val id = request.optLong("id", -1)
            val minutes = if (request.has("minutes")) request.optInt("minutes", -1) else null
            when {
                id < 0 -> CommandResult(false, "answerPrivacy needs the question id")
                minutes != null && minutes !in 1..MAX_ALLOWED_MINUTES -> CommandResult(false, "minutes must be 1 to $MAX_ALLOWED_MINUTES")
                else -> { PrivacyOverridePrompt.answer(id, minutes, answeredBy = "phone \"$client\""); CommandResult(true) }
            }
        }
        "reload" -> { navigation.reload(); CommandResult(true) }
        "resetLocations" -> { navigation.resetSavedLocations(); CommandResult(true) }
        "resetAreas" -> { navigation.clearPrivateAreas(); CommandResult(true) }
        else -> CommandResult(false, "unknown action \"$action\"")
    }
}

/**
 * Everything the phone screen needs, in one object. All coordinates are robot coordinates in metres.
 * @return the state
 */
private fun stateJson(): JSONObject {
    NavigationHub.expireRemoteControl()
    val navigation = NavigationHub.current
        ?: return JSONObject().put("ok", false).put("running", false)
            .put("error", "the navigation is not running on the robot")
    val pose = navigation.pose.value
    val overrides = navigation.activeOverrides.value
    return JSONObject().apply {
        put("ok", true)
        put("running", true)
        put("map", navigation.mapName.value ?: JSONObject.NULL)
        put("localized", navigation.localized.value ?: JSONObject.NULL)
        put("sdkControl", navigation.sdkActive.value)
        put("navState", navigation.navState.value)
        put("moving", navigation.driving.value || navigation.turning.value)
        put("speed", navigation.speed.value.name)
        put("speedLabel", navigation.speed.value.label)
        put("measuredSpeed", navigation.measuredSpeed.value ?: JSONObject.NULL)
        put("remoteControl", NavigationHub.remoteControl.value ?: JSONObject.NULL)
        put("now", System.currentTimeMillis())
        put("pose", pose?.let {
            JSONObject().put("x", it.x).put("y", it.y).put("theta", it.theta).put("status", it.status.name)
        } ?: JSONObject.NULL)
        put("selected", navigation.selected.value?.let { pointJson(it) } ?: JSONObject.NULL)
        put("places", JSONArray().apply { navigation.places.value.forEach { put(pointJson(it)) } })
        put("points", JSONArray().apply { navigation.customPoints.value.forEach { put(pointJson(it)) } })
        put("areas", JSONArray().apply {
            navigation.privateZones.value.forEach { zone ->
                put(JSONObject().apply {
                    put("name", zone.name)
                    put("allowedUntil", overrides[zone.name] ?: JSONObject.NULL)
                    put("corners", JSONArray().apply {
                        zone.polygon.forEach { put(JSONObject().put("x", it.x).put("y", it.y)) }
                    })
                })
            }
        })
        put("privacyMargin", navigation.privacyMargin)
        put("drawing", navigation.drawingVertices.value?.let { corners ->
            JSONArray().apply { corners.forEach { put(JSONObject().put("x", it.x).put("y", it.y)) } }
        } ?: JSONObject.NULL)
        put("areasLoaded", navigation.zonesLoaded.value)
        put("areaStoreError", navigation.zoneStoreError.value ?: JSONObject.NULL)
        put("areaSaveWarning", navigation.zoneSaveWarning.value ?: JSONObject.NULL)
        put("locationStoreError", navigation.pointStoreError.value ?: JSONObject.NULL)
        put("question", PrivacyOverridePrompt.pending.value?.let {
            JSONObject().put("id", it.id).put("area", it.zoneName)
        } ?: JSONObject.NULL)
        put("log", JSONArray().apply { NavigationHub.log.lines.value.takeLast(LOG_LINES).forEach { put(it) } })
        put("audit", JSONArray().apply { navigation.audit.value.forEach { put(it) } })
    }
}

private const val LOG_LINES = 40

private const val MAX_ALLOWED_MINUTES = 60

/**
 * Finite and, once a map is shown, inside its rendered section.
 * @param navigation navigation model
 * @param p point
 * @return true if the point is usable
 */
private fun insideMap(navigation: MapNavigation, p: Point2D): Boolean {
    if (!p.x.isFinite() || !p.y.isFinite()) return false
    val rendered = navigation.rendered.value ?: return true
    val map = rendered.map
    val b = rendered.bounds
    return p.x >= map.originX + b.minCol * map.resolution && p.x <= map.originX + (b.maxCol + 1) * map.resolution &&
        p.y >= map.originY + b.minRow * map.resolution && p.y <= map.originY + (b.maxRow + 1) * map.resolution
}

/**
 * One place or location as JSON for the phone (name, x, y, saved).
 * @param point place or location
 * @return the JSON object
 */
private fun pointJson(point: MapPoint) = JSONObject()
    .put("name", point.name)
    .put("x", point.position.x)
    .put("y", point.position.y)
    .put("saved", point.saved)
    .put("persistent", point.persistent)

/**
 * Size of the map picture and the world coordinates of its corners.
 * @return the JSON object
 */
private fun mapJson(): JSONObject {
    val navigation = NavigationHub.current
    val rendered = navigation?.rendered?.value
        ?: return JSONObject().put("ok", false).put("error", "no map loaded")
    val map = rendered.map
    val bounds = rendered.bounds
    return JSONObject().apply {
        put("ok", true)
        put("name", navigation.mapName.value ?: JSONObject.NULL)
        put("widthPx", rendered.bitmap.width)
        put("heightPx", rendered.bitmap.height)
        put("resolution", map.resolution)
        put("minX", map.originX + bounds.minCol * map.resolution)
        put("maxX", map.originX + (bounds.maxCol + 1) * map.resolution)
        put("minY", map.originY + bounds.minRow * map.resolution)
        put("maxY", map.originY + (bounds.maxRow + 1) * map.resolution)
    }
}
