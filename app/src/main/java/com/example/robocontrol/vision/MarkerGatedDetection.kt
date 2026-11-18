package com.example.robocontrol.vision

import kotlin.math.max

/** Named access to the object detection settings in `assets/settings/settings.json` ([DetectionSettings]). */
object CalendarDetectionSettings {
    val orb: OrbConfig get() = DetectionSettings.general.toOrbConfig()

    val MIN_GOOD_MATCHES: Int get() = DetectionSettings.general.minGoodMatches
    val MIN_INLIERS: Int get() = DetectionSettings.general.minInliers
    val CONFIRM_INLIER_DROP: Int get() = DetectionSettings.general.confirmInlierDrop
    val MIN_REGION_SCALE: Double get() = DetectionSettings.general.minRegionScale
    val MAX_REGION_SCALE: Double get() = DetectionSettings.general.maxRegionScale
    val ORB_MARGIN_PX: Int get() = DetectionSettings.general.orbMarginPx

    val MIN_REGION_LONG_SIDE_PX: Int get() = DetectionSettings.general.minRegionLongSidePx
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
    val WHOLE_FRAME_INTERVAL_MS: Long get() = DetectionSettings.run.wholeFrameIntervalMs.coerceAtLeast(50).toLong()
    val REARM_AFTER_GONE_MS: Long get() = DetectionSettings.run.rearmAfterGoneSeconds.coerceAtLeast(0) * 1000L
    val POPUP_COOLDOWN_MS: Long get() = DetectionSettings.run.popupCooldownSeconds.coerceAtLeast(0) * 1000L

    const val MIN_INLIERS_LOWEST = ObjectSettings.ORB_MIN_INLIERS
    const val MIN_INLIERS_HIGHEST = 200

    const val ORB_MIN_SCALED_BORDER_PX = 32

    const val WORKER_PRIORITY = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY

    /**
     * Applies the pink-search settings from the file to [marker].
     * @param marker colour search to configure
     */
    fun applyTo(marker: ColorMarker) = DetectionSettings.pink.applyTo(marker)
}

/**
 * ORB result for one pink area.
 * @property frameSide longer side of the pink bounds in frame pixels
 * @property scale factor the area was scaled by before ORB
 * @property keypoints keypoints found in the area
 * @property goodMatches good matches of the best reference in the area
 * @property inliers inliers of the best reference in the area
 */
data class RegionCheck(val frameSide: Int, val scale: Double, val keypoints: Int, val goodMatches: Int, val inliers: Int)

/**
 * Result of one [markerGatedPass].
 * @property results one entry per reference, the best over all pink areas
 * @property marker result of the pink search; null if it failed
 * @property regions pink areas that were searched
 * @property keypoints number of keypoints found in all searched areas
 * @property keypointPositions frame positions of these keypoints
 * @property previewMs time to build the colour image
 * @property orbTimings ORB stage times
 * @property skipped true if ORB was skipped because another worker ran it
 * @property checks one entry per searched pink area
 */
class MarkerPass(
    val results: List<ObjectMatch>,
    val marker: MarkerResult?,
    val regions: List<MarkerRegion>,
    val keypoints: Int,
    val keypointPositions: List<ImagePoint>,
    val previewMs: Long = 0,
    val orbTimings: ORB.Timings = ORB.Timings(),
    val skipped: Boolean = false,
    val checks: List<RegionCheck> = emptyList()
)

/**
 * Runs one detection pass: finds pink areas in [frame] and runs ORB only inside them.
 * @param orb detector
 * @param marker pink marker search
 * @param frame camera frame
 * @param thresholds good matches and inliers required, per reference name
 * @param maxRegions maximum number of pink areas searched
 * @param withOverlay also build the pink overlay for the debug view
 * @param classNames references to search, or null for all
 * @param orbGate allows one ORB run at a time, or null
 * @return the pass result
 */
fun markerGatedPass(
    orb: ORB,
    marker: ColorMarker,
    frame: CameraFrame,
    thresholds: (String) -> Pair<Int, Int>,
    maxRegions: Int = CalendarDetectionSettings.MAX_REGIONS,
    withOverlay: Boolean = false,
    classNames: Collection<String>? = null,
    orbGate: java.util.concurrent.Semaphore? = null
): MarkerPass {
    orb.takeTimings()
    val previewMs = 0L
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
    val referenceSide = orb.referenceMap.values.maxOfOrNull { max(it.image.width, it.image.height) } ?: 640
    for (region in regions) {
        if (max(region.bounds.width, region.bounds.height) < CalendarDetectionSettings.MIN_REGION_LONG_SIDE_PX) continue
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

/**
 * Judges an ORB result found inside a pink area.
 * @param m ORB result
 * @param region pink area
 * @param minGood good matches needed
 * @param minInliers inliers needed
 * @return the judged result
 */
private fun judgeInMarker(m: ObjectMatch, region: MarkerRegion, minGood: Int, minInliers: Int): ObjectMatch {
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

/**
 * Detected first, then more inliers, then more good matches.
 * @param a first result
 * @param b second result
 * @return true if [a] is better
 */
fun isBetter(a: ObjectMatch, b: ObjectMatch): Boolean =
    if (a.detected != b.detected) a.detected else if (a.inliers != b.inliers) a.inliers > b.inliers else a.goodMatches > b.goodMatches
