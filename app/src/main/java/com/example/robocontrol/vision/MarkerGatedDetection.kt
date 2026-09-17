package com.example.robocontrol.vision

import kotlin.math.max

/**
 * The settings the owner chose in the object test (run probe-20260917-153521, 2026-09-17): used by [CalendarMonitor] and as
 * the object test's start values.
 */
object CalendarDetectionSettings {
    /**
     * 4 pyramid levels instead of 8: the pink frame tells the object's size, so the area is scaled to the reference's size first
     * ([markerGatedPass]); USAC homography instead of classic RANSAC (both 2026-09-17, for speed).
     */
    val orb = OrbConfig(maxFeatures = 3000, fastThreshold = 10, gridDistribution = false, minGoodMatches = 15, minInliers = 15,
        pyramidLevels = 4, homographyMethod = org.opencv.calib3d.Calib3d.USAC_DEFAULT, homographyMaxIters = 1000)

    /** Allowed range for the size-based scale of a pink area (tiny or huge frames are clamped). */
    const val MIN_REGION_SCALE = 0.5
    const val MAX_REGION_SCALE = 4.0
    const val MIN_GOOD_MATCHES = 15
    /** Default inlier requirement; the monitor's current value is [CalendarMonitor.minInliers] (adjustable in the camera view). */
    const val MIN_INLIERS = 30
    const val MIN_INLIERS_LOWEST = 4
    /**
     * Confirming passes need this many inliers fewer than the first one. Owner first chose X − 10; changed to 30 / 25 after weak
     * announcements with confirming passes of 12–18 inliers (logcat 2026-09-17 22:56), while real ones had 28–48.
     */
    const val CONFIRM_INLIER_DROP = 5
    const val MIN_INLIERS_HIGHEST = 200
    const val MARKER_MARGIN_PX = 40
    val MARKER_COLOR = MarkerColor.PINK.copy(saturationMin = 50)
    const val WHITE_BALANCE = true
    const val REQUIRE_FRAME = true
    /** The inside of the pink frame must be mostly white paper. */
    const val REQUIRE_WHITE_INSIDE = true
    const val MIN_WHITE_SHARE = 0.5

    /** Parallel detection workers in [CalendarMonitor] (each its own ORB); the robot has 8 cores, navigation needs some. */
    const val WORKERS = 2

    /**
     * Upper limit for pass starts (all workers together). Without it the idle colour-only passes ran for every camera frame (30/s)
     * and, together with RobotOS (chassis, audio, camera services), kept the CPU so busy and hot (82–95 °C) that ORB passes with a
     * pink area became slower (600 → 900 ms, 2026-09-17).
     */
    const val MAX_PASSES_PER_SECOND = 12

    /** Largest pink areas searched per pass (each costs one ORB pass). */
    const val MAX_REGIONS = 3

    /**
     * Present = detected in at least this many of the last [CONSISTENCY_WINDOW] passes. In [CalendarMonitor] the first of those
     * passes needs the full inlier count, the others only [CONFIRM_INLIER_DROP] fewer.
     */
    const val CONSISTENCY_NEEDED = 2
    const val CONSISTENCY_WINDOW = 2

    fun applyTo(marker: ColorMarker) {
        marker.marginPx = MARKER_MARGIN_PX
        marker.color = MARKER_COLOR
        marker.whiteBalance = WHITE_BALANCE
        marker.requireFrame = REQUIRE_FRAME
        marker.requireWhiteInside = REQUIRE_WHITE_INSIDE
        marker.minWhiteShare = MIN_WHITE_SHARE
    }
}

/**
 * Result of one [markerGatedPass].
 * @property results one entry per reference (best over all pink areas)
 * @property marker the pink search result (null if it failed)
 * @property regions the pink areas that were searched
 * @property keypoints keypoints found in all searched areas
 * @property keypointPositions their frame positions
 */
class MarkerPass(
    val results: List<ObjectMatch>,
    val marker: MarkerResult?,
    val regions: List<MarkerRegion>,
    val keypoints: Int,
    val keypointPositions: List<ImagePoint>,
    /** Time to build the colour image from the camera's YUV data (0 if it already existed). */
    val previewMs: Long = 0,
    /** ORB stage times of this pass. */
    val orbTimings: ORB.Timings = ORB.Timings(),
    /** True if a pink area was found but ORB was skipped because another worker was running ORB ([markerGatedPass] orbGate). */
    val skipped: Boolean = false
)

/**
 * Pink-marker pass: find pink areas in [frame], run ORB only inside each, keep the best result per reference.
 * Accepted: a plausible outline whose centre lies in the pink area, or (lens distortion folds outlines) at least [minGood] good
 * matches and [minInliers] inliers inside the pink area; the box is then the pink pixels' bounds ("marker").
 */
fun markerGatedPass(
    orb: ORB,
    marker: ColorMarker,
    frame: CameraFrame,
    minGood: Int,
    minInliers: Int,
    maxRegions: Int = CalendarDetectionSettings.MAX_REGIONS,
    withOverlay: Boolean = false,
    /**
     * If given, ORB runs only while holding a permit; without one the pass returns [MarkerPass.skipped]. Lets several workers check
     * colour in parallel while only one runs the expensive ORB step (parallel ORB passes slowed each other down on the robot).
     */
    orbGate: java.util.concurrent.Semaphore? = null
): MarkerPass {
    orb.takeTimings() // start a fresh collection for this pass
    val previewMs = 0L // colour now comes straight from the YUV data inside ColorMarker.find (counted as colour time)
    val found = runCatching { marker.find(frame, withOverlay) }.getOrNull()
    val regions = found?.regions.orEmpty().take(maxRegions)
    if (regions.isNotEmpty() && orbGate != null && !orbGate.tryAcquire()) {
        return MarkerPass(orb.classNames.map { ObjectMatch(it, false, 0, 0, emptyList()) }, found, regions, 0, emptyList(), previewMs, skipped = true)
    }
    try {
    val best = LinkedHashMap<String, ObjectMatch>()
    orb.classNames.forEach { best[it] = ObjectMatch(it, false, 0, 0, emptyList()) }
    var keypoints = 0
    val points = ArrayList<ImagePoint>()
    // The pink line runs along the reference image's edges, so its long side ≈ the reference's long side (640 px after loading).
    val referenceSide = orb.referenceMap.values.maxOfOrNull { max(it.image.width, it.image.height) } ?: 640
    for (region in regions) {
        val scale = (referenceSide.toDouble() / max(region.bounds.width, region.bounds.height).coerceAtLeast(1))
            .coerceIn(CalendarDetectionSettings.MIN_REGION_SCALE, CalendarDetectionSettings.MAX_REGION_SCALE)
        val results = runCatching { orb.evaluateRegion(frame.gray, region.search, scale = scale) }.getOrElse { emptyList() }
        keypoints += orb.lastFrameKeypoints
        points += orb.lastKeypointPositions
        for (m in results) {
            val judged = judgeInMarker(m, region, minGood, minInliers)
            val current = best[m.className]
            if (current == null || isBetter(judged, current)) best[m.className] = judged
        }
    }
    return MarkerPass(best.values.toList(), found, regions, keypoints, points, previewMs, orb.takeTimings())
    } finally {
        if (regions.isNotEmpty() && orbGate != null) orbGate.release()
    }
}

private fun judgeInMarker(m: ObjectMatch, region: MarkerRegion, minGood: Int, minInliers: Int): ObjectMatch {
    // The caller's thresholds apply to both paths (ORB's own config may differ, e.g. after adjusting the inliers at runtime).
    if (m.detected && m.corners.size == 4 && m.goodMatches >= minGood && m.inliers >= minInliers) {
        val centre = ImagePoint(m.corners.sumOf { it.x } / 4, m.corners.sumOf { it.y } / 4)
        if (region.search.contains(centre)) return m
    }
    if (m.goodMatches < minGood || m.inliers < minInliers) return m.copy(detected = false)
    val b = region.bounds
    val box = listOf(
        ImagePoint(b.x.toDouble(), b.y.toDouble()), ImagePoint((b.x + b.width).toDouble(), b.y.toDouble()),
        ImagePoint((b.x + b.width).toDouble(), (b.y + b.height).toDouble()), ImagePoint(b.x.toDouble(), (b.y + b.height).toDouble())
    )
    return m.copy(detected = true, corners = box, outlineSource = "marker")
}

/** Detected first, then more inliers, then more good matches. */
fun isBetter(a: ObjectMatch, b: ObjectMatch): Boolean =
    if (a.detected != b.detected) a.detected else if (a.inliers != b.inliers) a.inliers > b.inliers else a.goodMatches > b.goodMatches
