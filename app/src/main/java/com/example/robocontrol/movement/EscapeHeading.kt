package com.example.robocontrol.movement

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Picks the direction the robot should turn to (on the spot) after it was stopped at a private area.
 *
 * Owner's rule: face a direction whose line of sight stays at least [DEFAULT_REQUIRED_FREE_M] clear of every private
 * area; if no direction manages that, face the one that stays clear the longest. Turning on the spot instead of
 * driving backwards avoids moving blind, because the SDK has no obstacle avoidance for backward motion.
 *
 * Pure geometry, no SDK: testable with [FakeRobotBridge].
 */
object EscapeHeading {

    const val DEFAULT_REQUIRED_FREE_M = 1.0

    /**
     * @property heading target heading in radians (map frame, same convention as [RobotPose.theta])
     * @property freeDistance how far the line of sight runs before entering a private area (capped at the look range)
     * @property turn signed rotation from the current heading, radians in (-π, π]; positive = counter-clockwise
     * @property meetsRequirement true if [freeDistance] reached the required free distance
     */
    data class Choice(val heading: Double, val freeDistance: Double, val turn: Double, val meetsRequirement: Boolean)

    /**
     * Candidates every [stepDeg] degrees. Among the directions that are clear for [requiredFree], the one that ends
     * furthest from all private areas wins (so the robot points *away*, not along the edge). If none is clear enough,
     * the one with the longest free distance wins. Returns null if there are no private zones.
     *
     * The line of sight is checked against the zone polygons without the safety margin: after a stop the robot is
     * standing inside the margin, so every direction would count as blocked otherwise.
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

    /** Distance along the ray until it first enters a zone polygon; [lookRange] if it never does within range. */
    private fun freeDistance(origin: Point2D, heading: Double, zones: List<Zone>, lookRange: Double, sampleM: Double): Double {
        // Zones the robot is already inside do not block (it is leaving them); their clearance still ranks the choice.
        val blocking = zones.filterNot { it.contains(origin) }
        var s = sampleM
        while (s <= lookRange) {
            val p = along(origin, heading, s)
            if (blocking.any { it.contains(p) }) return s - sampleM
            s += sampleM
        }
        return lookRange
    }

    private fun along(p: Point2D, heading: Double, d: Double) = Point2D(p.x + cos(heading) * d, p.y + sin(heading) * d)

    /** Positive outside the polygon, negative inside. */
    private fun clearance(zone: Zone, p: Point2D) = if (zone.contains(p)) -zone.distanceToEdge(p) else zone.distanceToEdge(p)

    /** Wraps an angle to (-π, π]. */
    fun normalize(a: Double): Double {
        var r = a % (2 * PI)
        if (r <= -PI) r += 2 * PI
        if (r > PI) r -= 2 * PI
        return r
    }

    fun degrees(a: Double) = Math.toDegrees(a).let { if (abs(it) < 0.05) 0.0 else it }
}
