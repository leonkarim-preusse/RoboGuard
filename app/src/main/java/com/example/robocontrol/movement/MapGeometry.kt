package com.example.robocontrol.movement

import kotlin.math.sqrt

/**
 * Pure geometry and map-domain types. Deliberately free of any Android or
 * OrionStar SDK dependency so this layer compiles and unit-tests on the JVM
 * without the robot and without robotservice.jar.
 *
 * All coordinates are in the RobotOS map frame: metres, x/y, theta in radians.
 */

/** A point in the map frame, in metres. */
data class Point2D(val x: Double, val y: Double) {
    fun distanceTo(other: Point2D): Double {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * Safety/zone status reported by the RobotOS Pose listener.
 *
 * These codes come from the SDK, not from us:
 *   SAFE = 0, NOT_SAFE = 1, OBSTACLE = 2 (forbidden area), OUTSIDE = 3
 *
 * Note [FORBIDDEN] refers to an area authored in OrionStar's own map tool.
 * It is NOT the same thing as one of our [Zone]s marked [PrivacyLevel.PRIVATE] —
 * ours are enforced in the app, theirs by the robot's navigation stack.
 * Both are honoured; see PrivacyGuard.
 */
enum class PoseStatus(val code: Int) {
    SAFE(0),
    NOT_SAFE(1),
    FORBIDDEN(2),
    OUTSIDE(3),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): PoseStatus =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** The robot's pose as reported by STATUS_POSE. */
data class RobotPose(
    val x: Double,
    val y: Double,
    val theta: Double,
    val status: PoseStatus = PoseStatus.UNKNOWN
) {
    val point: Point2D get() = Point2D(x, y)
}

/**
 * How the robot may treat an area.
 *
 * [OPEN]    — ordinary space, navigation allowed.
 * [PRIVATE] — opt-out-by-default: navigation into this area is refused, and an
 *             in-progress navigation is aborted if the robot enters it. Only an
 *             explicit, time-boxed override (see PrivacyGuard.grantOverride)
 *             lifts this.
 */
enum class PrivacyLevel { OPEN, PRIVATE }

/**
 * A named region of the map — "bathroom", "bedroom", "living room".
 *
 * The OrionStar SDK has no concept of an area: `setLocation` names a single
 * point, and there is no API for virtual walls or forbidden regions. So the
 * area model lives here, in the app, and is enforced by PrivacyGuard against
 * the live pose stream.
 *
 * @param polygon vertices in map coordinates, in order. Treated as closed
 *        (the last vertex joins the first). Minimum 3 vertices.
 * @param entryPoint optional name of an OrionStar location (created with
 *        `setLocation`) that sits inside this zone, used as the navigation
 *        target when asked to go to the zone by name.
 */
data class Zone(
    val name: String,
    val polygon: List<Point2D>,
    val privacy: PrivacyLevel = PrivacyLevel.OPEN,
    val entryPoint: String? = null
) {
    init {
        require(name.isNotBlank()) { "Zone name must not be blank" }
        require(polygon.size >= 3) {
            "Zone '$name' needs at least 3 vertices, got ${polygon.size}"
        }
    }

    val isPrivate: Boolean get() = privacy == PrivacyLevel.PRIVATE

    /**
     * Point-in-polygon by ray casting (crossing number).
     *
     * Handles concave polygons, which matters: an L-shaped room is common and
     * a bounding-box test would wrongly capture the corridor outside it.
     *
     * Boundary behaviour: a point exactly on an edge is not guaranteed to be
     * reported consistently — this is inherent to ray casting with floating
     * point. Callers that care should use [containsWithMargin].
     */
    fun contains(p: Point2D): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            // Does the edge (b -> a) straddle the horizontal ray at p.y?
            if ((a.y > p.y) != (b.y > p.y)) {
                val t = (p.y - a.y) / (b.y - a.y)
                val xCross = a.x + t * (b.x - a.x)
                if (p.x < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * [contains], widened by [margin] metres.
     *
     * Use this for privacy enforcement rather than [contains]: the robot has a
     * 0.41 m footprint and localization drift, so treating the polygon as a
     * hard line means the robot is already partly inside the bathroom by the
     * time the test trips. A margin of ~0.5 m stops it at the doorway instead.
     */
    fun containsWithMargin(p: Point2D, margin: Double): Boolean {
        if (contains(p)) return true
        if (margin <= 0.0) return false
        return distanceToEdge(p) <= margin
    }

    /** Shortest distance from [p] to the polygon boundary, in metres. */
    fun distanceToEdge(p: Point2D): Double {
        var best = Double.MAX_VALUE
        var j = polygon.size - 1
        for (i in polygon.indices) {
            best = minOf(best, distancePointToSegment(p, polygon[j], polygon[i]))
            j = i
        }
        return best
    }

    /** Arithmetic mean of the vertices — a usable fallback navigation target. */
    fun centroid(): Point2D = Point2D(
        polygon.sumOf { it.x } / polygon.size,
        polygon.sumOf { it.y } / polygon.size
    )

    private fun distancePointToSegment(p: Point2D, a: Point2D, b: Point2D): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) return p.distanceTo(a)
        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
        t = t.coerceIn(0.0, 1.0)
        return p.distanceTo(Point2D(a.x + t * dx, a.y + t * dy))
    }
}

/**
 * The set of zones belonging to one RobotOS map.
 *
 * Zones are bound to a map name because OrionStar locations are: the docs are
 * explicit that a location saved with `setLocation` only resolves while the
 * same map is active. Our polygons share that constraint — coordinates from
 * one map are meaningless on another.
 */
data class MapZones(
    val mapName: String,
    val zones: List<Zone> = emptyList()
) {
    fun zoneAt(p: Point2D, margin: Double = 0.0): Zone? =
        zones.firstOrNull { it.containsWithMargin(p, margin) }

    /** Every zone containing [p] — zones may legitimately overlap (a nook inside a room). */
    fun zonesAt(p: Point2D, margin: Double = 0.0): List<Zone> =
        zones.filter { it.containsWithMargin(p, margin) }

    fun byName(name: String): Zone? =
        zones.firstOrNull { it.name.equals(name, ignoreCase = true) }

    val privateZones: List<Zone> get() = zones.filter { it.isPrivate }

    fun withZone(zone: Zone): MapZones =
        copy(zones = zones.filterNot { it.name.equals(zone.name, true) } + zone)

    fun withoutZone(name: String): MapZones =
        copy(zones = zones.filterNot { it.name.equals(name, true) })
}
