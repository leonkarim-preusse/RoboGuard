package com.example.robocontrol.movement

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Chooses the direction the robot turns to on the spot after a stop at a private area. */
object EscapeHeading {
    const val DEFAULT_REQUIRED_FREE_M = 1.0

    /**
     * Result of [choose].
     * @property heading target heading in radians
     * @property freeDistance distance the line of sight stays outside private areas
     * @property turn signed rotation from the current heading in radians; positive = counter-clockwise
     * @property meetsRequirement true if [freeDistance] reached the required distance
     */
    data class Choice(val heading: Double, val freeDistance: Double, val turn: Double, val meetsRequirement: Boolean)

    /**
     * Chooses the clear direction that ends furthest from all private areas, else the one with the longest free
     * distance.
     * @param pose current pose
     * @param zones private zones
     * @param requiredFree required free distance in metres
     * @param lookRange maximum distance checked in metres
     * @param stepDeg angle between tested directions
     * @param sampleM step along the line of sight in metres
     * @return the chosen direction, or null without zones
     */
    fun choose(
        pose: RobotPose,
        zones: List<Zone>,
        requiredFree: Double = DEFAULT_REQUIRED_FREE_M,
        lookRange: Double = 3.0,
        stepDeg: Int = 5,
        sampleM: Double = 0.05
    ): Choice? {
        if (zones.isEmpty()) return null
        val origin = pose.point
        val candidates = (0 until 360 step stepDeg).map { deg ->
            val heading = Math.toRadians(deg.toDouble())
            val free = freeDistance(origin, heading, zones, lookRange, sampleM)
            val end = along(origin, heading, requiredFree)
            val endClearance = zones.minOf { clearance(it, end) }
            Triple(heading, free, endClearance)
        }
        val clear = candidates.filter { it.second >= requiredFree }
        val best = if (clear.isNotEmpty()) {
            clear.maxBy { it.third }
        } else {
            candidates.maxWith(compareBy<Triple<Double, Double, Double>> { it.second }.thenBy { it.third })
        }
        return Choice(best.first, best.second, normalize(best.first - pose.theta), clear.isNotEmpty())
    }

    /**
     * Distance along the ray until it first enters a zone polygon; [lookRange] if it never does within range.
     * @param origin start point
     * @param heading direction in radians
     * @param zones private areas
     * @param lookRange maximum distance
     * @param sampleM step size
     * @return the free distance
     */
    private fun freeDistance(origin: Point2D, heading: Double, zones: List<Zone>, lookRange: Double, sampleM: Double): Double {
        val blocking = zones.filterNot { it.contains(origin) }
        var s = sampleM
        while (s <= lookRange) {
            val p = along(origin, heading, s)
            if (blocking.any { it.contains(p) }) return s - sampleM
            s += sampleM
        }
        return lookRange
    }

    /**
     * The point [d] metres from [p] in direction [heading].
     * @param p start point
     * @param heading direction in radians
     * @param d distance in metres
     * @return the point
     */
    private fun along(p: Point2D, heading: Double, d: Double) = Point2D(p.x + cos(heading) * d, p.y + sin(heading) * d)

    /**
     * Positive outside the polygon, negative inside.
     * @param zone area
     * @param p point
     * @return the signed clearance
     */
    private fun clearance(zone: Zone, p: Point2D) = if (zone.contains(p)) -zone.distanceToEdge(p) else zone.distanceToEdge(p)

    /**
     * Wraps an angle to (-π, π].
     * @param a angle in radians
     * @return the wrapped angle
     */
    fun normalize(a: Double): Double {
        var r = a % (2 * PI)
        if (r <= -PI) r += 2 * PI
        if (r > PI) r -= 2 * PI
        return r
    }

    /**
     * Radians to degrees for the log; values close to zero are printed as 0.
     * @param a angle in radians
     * @return the angle in degrees
     */
    fun degrees(a: Double) = Math.toDegrees(a).let { if (abs(it) < 0.05) 0.0 else it }
}
