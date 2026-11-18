package com.example.robocontrol.movement

import kotlin.math.sqrt

/**
 * Geometry. Coordinates are in the RobotOS map frame: metres, theta in radians.
 */

/**
 * A point in the map frame, in metres.
 * @property x x in metres
 * @property y y in metres
 */
data class Point2D(val x: Double, val y: Double) {
    /**
     * Straight-line distance to [other] in metres.
     * @param other other point
     * @return the distance in metres
     */
    fun distanceTo(other: Point2D): Double {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * Pose status reported by RobotOS. [FORBIDDEN] is an area defined in OrionStar's map tool, not a RoboGuard [Zone].
 * @property code status code used by the SDK
 */
enum class PoseStatus(val code: Int) {
    SAFE(0),
    NOT_SAFE(1),
    FORBIDDEN(2),
    OUTSIDE(3),
    UNKNOWN(-1);

    companion object {
        /**
         * Maps an SDK status code to the enum; unknown codes give [UNKNOWN].
         * @param code status code from the SDK
         * @return the matching status
         */
        fun fromCode(code: Int): PoseStatus =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * The robot's pose as reported by STATUS_POSE.
 * @property x x in metres
 * @property y y in metres
 * @property theta heading in radians
 * @property status pose status
 */
data class RobotPose(
    val x: Double,
    val y: Double,
    val theta: Double,
    val status: PoseStatus = PoseStatus.UNKNOWN
) {
    val point: Point2D get() = Point2D(x, y)
}

/** How the robot may treat an area: [OPEN] allows navigation, [PRIVATE] refuses it unless an override is active. */
enum class PrivacyLevel { OPEN, PRIVATE }

/**
 * A named area of the map.
 * @property name name of the area
 * @property polygon corners in map coordinates, in order; the polygon is closed and has at least 3 corners
 * @property privacy how the area is treated
 * @property entryPoint optional RobotOS place inside the area, used as navigation target
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
     * Point-in-polygon test by ray casting; works for concave polygons.
     * @param p point in map coordinates
     * @return true if the point is inside
     */
    fun contains(p: Point2D): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
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
     * Like [contains], with the polygon widened by [margin].
     * @param p point in map coordinates
     * @param margin widening in metres
     * @return true if the point is inside the widened polygon
     */
    fun containsWithMargin(p: Point2D, margin: Double): Boolean {
        if (contains(p)) return true
        if (margin <= 0.0) return false
        return distanceToEdge(p) <= margin
    }

    /**
     * Shortest distance from [p] to the polygon boundary, in metres.
     * @param p point
     * @return the distance in metres
     */
    fun distanceToEdge(p: Point2D): Double {
        var best = Double.MAX_VALUE
        var j = polygon.size - 1
        for (i in polygon.indices) {
            best = minOf(best, distancePointToSegment(p, polygon[j], polygon[i]))
            j = i
        }
        return best
    }

    /**
     * Arithmetic mean of the vertices; used as a fallback navigation target.
     * @return the centroid
     */
    fun centroid(): Point2D = Point2D(
        polygon.sumOf { it.x } / polygon.size,
        polygon.sumOf { it.y } / polygon.size
    )

    /**
     * Shortest distance from [p] to the line segment from [a] to [b].
     * @param p point
     * @param a segment start
     * @param b segment end
     * @return the distance in metres
     */
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
 * The zones of one RobotOS map.
 * @property mapName name of the map
 * @property zones zones on that map
 */
data class MapZones(
    val mapName: String,
    val zones: List<Zone> = emptyList()
) {
    /**
     * The first zone that contains [p], with [margin] metres added around each zone; null if none.
     * @param p point
     * @param margin margin in metres
     * @return the zone, or null
     */
    fun zoneAt(p: Point2D, margin: Double = 0.0): Zone? =
        zones.firstOrNull { it.containsWithMargin(p, margin) }

    /**
     * Every zone containing [p]; zones may overlap.
     * @param p point
     * @param margin margin in metres
     * @return the zones
     */
    fun zonesAt(p: Point2D, margin: Double = 0.0): List<Zone> =
        zones.filter { it.containsWithMargin(p, margin) }

    /**
     * The zone with this [name] (case-insensitive), or null.
     * @param name zone name
     * @return the zone, or null
     */
    fun byName(name: String): Zone? =
        zones.firstOrNull { it.name.equals(name, ignoreCase = true) }

    val privateZones: List<Zone> get() = zones.filter { it.isPrivate }

    /**
     * A copy with [zone] added; a zone with the same name is replaced.
     * @param zone zone to add
     * @return the new set
     */
    fun withZone(zone: Zone): MapZones =
        copy(zones = zones.filterNot { it.name.equals(zone.name, true) } + zone)

    /**
     * A copy without the zone called [name].
     * @param name zone name
     * @return the new set
     */
    fun withoutZone(name: String): MapZones =
        copy(zones = zones.filterNot { it.name.equals(name, true) })
}
