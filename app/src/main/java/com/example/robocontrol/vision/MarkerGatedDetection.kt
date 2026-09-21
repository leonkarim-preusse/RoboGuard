package com.example.robocontrol.vision

import kotlin.math.max

/**
 * The settings the owner chose in the object test (run probe-20260917-153521, 2026-09-17): used by [CalendarMonitor] and as
 * the object test's start values.
 */
/**
 * The object-detection settings, read from `assets/settings/settings.json` ([DetectionSettings]). This object only gives the
 * values names the code can use; everything that can be tuned lives in that file, including per-image overrides.
 *
 * `MARKER_*`, `WORKER_PRIORITY` and `ORB_MIN_SCALED_BORDER_PX` are fixed in code: they are not tuning knobs but properties of
 * the robot (thread priority) or of ORB itself (its 31 px patch border).
 */
object CalendarDetectionSettings {
    /** General ORB settings (per-image overrides: `DetectionSettings.forImage(name)`). */
    val orb: OrbConfig get() = DetectionSettings.general.toOrbConfig()

    val MIN_GOOD_MATCHES: Int get() = DetectionSettings.general.minGoodMatches
    val MIN_INLIERS: Int get() = DetectionSettings.general.minInliers
    val CONFIRM_INLIER_DROP: Int get() = DetectionSettings.general.confirmInlierDrop
    val MIN_REGION_SCALE: Double get() = DetectionSettings.general.minRegionScale
    val MAX_REGION_SCALE: Double get() = DetectionSettings.general.maxRegionScale
    val ORB_MARGIN_PX: Int get() = DetectionSettings.general.orbMarginPx
    val SCALE_REGION_TO_REFERENCE: Boolean get() = DetectionSettings.general.scaleRegionToReference
    val USE_PINK_MARKER: Boolean get() = DetectionSettings.general.usePinkMarker
    val MARKER_MARGIN_PX: Int get() = DetectionSettings.pink.marginPx
    val MIN_WHITE_SHARE: Double get() = DetectionSettings.pink.minWhiteShare
    val WORKERS: Int get() = DetectionSettings.run.workers
    val MAX_PASSES_PER_SECOND: Int get() = DetectionSettings.run.maxPassesPerSecond
    val MAX_REGIONS: Int get() = DetectionSettings.run.maxRegions
    val CONSISTENCY_NEEDED: Int get() = DetectionSettings.run.consistencyNeeded
    val CONSISTENCY_WINDOW: Int get() = DetectionSettings.run.consistencyWindow
    val COOLDOWN_MS: Long get() = DetectionSettings.run.cooldownSeconds * 1000L

    /** Lowest/highest inlier requirement the camera view may set. */
    const val MIN_INLIERS_LOWEST = ObjectSettings.ORB_MIN_INLIERS
    const val MIN_INLIERS_HIGHEST = 200

    /**
     * ORB finds no keypoints within its 31 px patch border, so the crop keeps at least this much around the pink frame
     * after scaling (property of ORB, not a setting).
     */
    const val ORB_MIN_SCALED_BORDER_PX = 32

    /**
     * Android thread priority of the detection workers (nice value; lower = more CPU, more likely a fast core). −8 =
     * THREAD_PRIORITY_URGENT_DISPLAY, the level Android gives the screen's render thread.
     */
    const val WORKER_PRIORITY = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY

    /** Applies the pink-search settings from the file to [marker]. */
    fun applyTo(marker: ColorMarker) = DetectionSettings.pink.applyTo(marker)
}

/**
 * ORB on one pink area: for relating detection quality to distance.
 * @property frameSide long side of the pink line's bounds in frame pixels (larger = closer)
 * @property scale factor the area was scaled by before ORB
 * @property keypoints keypoints ORB found in the area
 * @property goodMatches / [inliers] of the best reference in this area
 */
data class RegionCheck(val frameSide: Int, val scale: Double, val keypoints: Int, val goodMatches: Int, val inliers: Int)

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
    val skipped: Boolean = false,
    /** One entry per pink area ORB ran on. */
    val checks: List<RegionCheck> = emptyList()
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
    /** Thresholds per reference name (good matches, inliers); from settings.json, so an image can demand more or fewer. */
    thresholds: (String) -> Pair<Int, Int>,
    maxRegions: Int = CalendarDetectionSettings.MAX_REGIONS,
    withOverlay: Boolean = false,
    /** Only these references are searched for (null = all); the others are handled elsewhere, e.g. without the pink gate. */
    classNames: Collection<String>? = null,
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
        return MarkerPass((classNames ?: orb.classNames).map { ObjectMatch(it, false, 0, 0, emptyList()) }, found, regions, 0, emptyList(), previewMs, skipped = true)
    }
    try {
    val wanted = classNames ?: orb.classNames
    val best = LinkedHashMap<String, ObjectMatch>()
    wanted.forEach { best[it] = ObjectMatch(it, false, 0, 0, emptyList()) }
    var keypoints = 0
    val points = ArrayList<ImagePoint>()
    val checks = ArrayList<RegionCheck>()
    // The pink line runs along the reference image's edges, so its long side ≈ the reference's long side (640 px after loading).
    val referenceSide = orb.referenceMap.values.maxOfOrNull { max(it.image.width, it.image.height) } ?: 640
    for (region in regions) {
        val scale = if (!CalendarDetectionSettings.SCALE_REGION_TO_REFERENCE) 1.0
        else (referenceSide.toDouble() / max(region.bounds.width, region.bounds.height).coerceAtLeast(1))
            .coerceIn(CalendarDetectionSettings.MIN_REGION_SCALE, CalendarDetectionSettings.MAX_REGION_SCALE)
        val margin = max(CalendarDetectionSettings.ORB_MARGIN_PX, kotlin.math.ceil(CalendarDetectionSettings.ORB_MIN_SCALED_BORDER_PX / scale).toInt())
        val b = region.bounds
        val crop = ImageRegion(b.x - margin, b.y - margin, b.width + 2 * margin, b.height + 2 * margin)
        val results = runCatching { orb.evaluateRegion(frame.gray, crop, scale = scale, classNames = classNames) }.getOrElse { emptyList() }
        keypoints += orb.lastFrameKeypoints
        points += orb.lastKeypointPositions
        val top = results.maxByOrNull { it.inliers }
        checks += RegionCheck(max(b.width, b.height), scale, orb.lastFrameKeypoints, top?.goodMatches ?: 0, top?.inliers ?: 0)
        for (m in results) {
            val (minGood, minInliers) = thresholds(m.className)
            val judged = judgeInMarker(m, region, minGood, minInliers)
            val current = best[m.className]
            if (current == null || isBetter(judged, current)) best[m.className] = judged
        }
    }
    return MarkerPass(best.values.toList(), found, regions, keypoints, points, previewMs, orb.takeTimings(), checks = checks)
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
