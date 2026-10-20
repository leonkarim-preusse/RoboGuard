package com.example.robocontrol.vision

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import org.opencv.features2d.ORB as OpenCvOrb

/**
 * Settings for [ORB].
 * @property maxFeatures maximum number of keypoints per image
 * @property maxReferenceSide reference images are scaled so that their longer side is at most this many pixels
 * @property ratioTest Lowe's ratio: a match counts only if its distance is below this share of the second-best distance
 * @property minGoodMatches matches required after the ratio test before a homography is computed
 * @property minInliers homography inliers required for a detection
 * @property ransacReprojThreshold maximum reprojection error of an inlier, in pixels
 * @property minAreaPx minimum area of the projected outline, in pixels
 * @property fastThreshold FAST corner threshold; lower values also accept weaker corners
 * @property gridDistribution distribute keypoints over a [gridColumns] × [gridRows] grid instead of keeping the strongest
 * @property contrastBoost apply CLAHE contrast equalisation to frames before detection
 */
data class OrbConfig(
    val maxFeatures: Int = 1000,
    val maxReferenceSide: Int = 640,
    val ratioTest: Float = 0.75f,
    val minGoodMatches: Int = 15,
    val minInliers: Int = 12,
    /** Minimum number of keypoints a frame or crop must have before it is matched; 0 = no minimum. */
    val minFrameKeypoints: Int = 0,
    val ransacReprojThreshold: Double = 5.0,
    val minAreaPx: Double = 400.0,
    val fastThreshold: Int = 20,
    val gridDistribution: Boolean = false,
    val gridColumns: Int = 8,
    val gridRows: Int = 6,
    val contrastBoost: Boolean = false,
    /** Number of levels of the ORB image pyramid (scale factor 1.2 per level). */
    val pyramidLevels: Int = 8,
    /** OpenCV homography method: Calib3d.RANSAC (classic) or e.g. Calib3d.USAC_DEFAULT (RANSAC with local optimisation and early stopping, faster). */
    val homographyMethod: Int = Calib3d.RANSAC,
    val homographyMaxIters: Int = 2000,
    val homographyConfidence: Double = 0.995
)

/**
 * A learned reference object: its image and ORB features. The features are native memory and are released by [ORB].
 * @property className name of the reference
 * @property image the reference image, grayscale and scaled to [OrbConfig.maxReferenceSide]
 */
class ObjectReference internal constructor(
    val className: String,
    val image: GrayImage,
    /** Settings this reference was read with and is matched with (per-image section of settings.json, else the general ones). */
    val config: OrbConfig,
    internal val keypoints: Array<KeyPoint>,
    internal val descriptors: Mat
) {
    /** Number of ORB keypoints found. Few keypoints (< ~100) means a poorly textured reference. */
    val keypointCount: Int get() = keypoints.size

    /** Frees the native descriptor matrix. */
    internal fun release() = descriptors.release()
}

/**
 * Result of comparing one reference with one frame.
 * @property className name of the reference
 * @property detected true if the reference was found
 * @property goodMatches matches that passed the ratio test
 * @property inliers matches consistent with the homography
 * @property corners the reference's corners projected into the frame (top-left, top-right, bottom-right, bottom-left);
 * empty without a homography
 */
data class ObjectMatch(
    val className: String,
    val detected: Boolean,
    val goodMatches: Int,
    val inliers: Int,
    val corners: List<ImagePoint>,
    /**
     * Where [corners] come from: "outline" = reference corners projected through the homography; callers may replace the corners
     * and set their own source (the object test uses "marker" for the bounds of a colour marker); "" when not detected.
     */
    val outlineSource: String = "",
    /**
     * Why a result with enough inliers was NOT detected: its projected outline is implausible ("non-convex", "area"), e.g. under
     * wide-angle lens distortion. Else null.
     */
    val outlineRejected: String? = null,
    /** Frame positions of the matches that agree with the homography (for drawing); empty without a homography. */
    val inlierPoints: List<ImagePoint> = emptyList()
)

/**
 * Result of loading the reference images from the assets.
 * @property loaded names of the loaded references
 * @property rejected file name → reason for every image that was not loaded
 */
data class AssetLoadReport(
    val loaded: List<String>,
    val rejected: Map<String, String>
)

/**
 * ORB-based object detection: finds objects learned from reference images in camera frames. All images in
 * `assets/ORB_img` are loaded on construction, keyed by their file name without extension. Per frame, keypoints are
 * computed once; each reference is matched with a kNN Hamming search and Lowe's ratio test, and a homography with enough
 * inliers and a plausible outline counts as a detection. All public methods are synchronized; call them off the main
 * thread and call [release] when done.
 * @param context used to read the reference images from the assets
 * @param config default settings
 * @param configFor settings for one reference by name; null = [config]
 * @param skipReference true for references that must not be loaded
 * @throws IllegalStateException if OpenCV cannot be loaded
 */
class ORB(
    context: Context,
    config: OrbConfig = OrbConfig(),
    /**
     * Settings for one reference image (file name without extension), e.g. from `assets/settings/settings.json`.
     * null = use [config] for it. Returning null from [skipReference] is how an image is switched off.
     */
    private val configFor: (String) -> OrbConfig? = { null },
    /** true = do not load this asset image at all (settings.json `enabled: false`). */
    private val skipReference: (String) -> Boolean = { false }
) {

    /**
     * Settings in use. [OrbConfig.contrastBoost] may be set directly; detector settings (maxFeatures, fastThreshold, grid) must
     * go through [applySettings], which also re-extracts all references.
     */
    @Volatile
    var config: OrbConfig = config

    /** Keypoints found in the last evaluated frame (0 = no texture or too dark). For tuning and test screens. */
    @Volatile
    var lastFrameKeypoints: Int = 0
        private set

    /** Positions of those keypoints in frame coordinates (for drawing on test screens). */
    @Volatile
    var lastKeypointPositions: List<ImagePoint> = emptyList()
        private set

    /**
     * Collect keypoint positions ([lastKeypointPositions]) for drawing. Off saves one small object per keypoint and pass
     * (thousands per second), which the garbage collector otherwise has to clean up.
     */
    @Volatile
    var collectDrawData: Boolean = true

    /** Time spent per stage since the last [takeTimings], in nanoseconds. For finding out where detection time goes. */
    class Timings {
        var resizeNs = 0L; var extractNs = 0L; var knnNs = 0L; var ratioNs = 0L; var homographyNs = 0L
        fun add(o: Timings) { resizeNs += o.resizeNs; extractNs += o.extractNs; knnNs += o.knnNs; ratioNs += o.ratioNs; homographyNs += o.homographyNs }
    }

    private var timings = Timings()

    /** Returns the stage timings collected since the previous call and starts a new collection. */
    @Synchronized
    fun takeTimings(): Timings = timings.also { timings = Timings() }

    /** Lazy: must not be created before the OpenCV library is loaded in init. */
    private val clahe by lazy { Imgproc.createCLAHE(2.0, Size(8.0, 8.0)) }

    init {
        check(loadOpenCv()) { "OpenCV native library could not be loaded" }
    }

    private var orb: OpenCvOrb = createDetector(config.maxFeatures, config.fastThreshold, config.pyramidLevels)

    /** Over-detects for [OrbConfig.gridDistribution]: candidates from which the grid keeps the best per cell. */
    private var wideOrb: OpenCvOrb = createDetector(config.maxFeatures * 4, config.fastThreshold, config.pyramidLevels)

    /** A new OpenCV ORB detector with the given number of features, FAST threshold and pyramid levels. */
    private fun createDetector(features: Int, fastThreshold: Int, levels: Int): OpenCvOrb =
        OpenCvOrb.create(features, 1.2f, levels.coerceIn(1, 12), 31, 0, 2, 0 /* HARRIS_SCORE */, 31, fastThreshold)

    /** Detectors for references whose settings differ from [config]; keyed by the values that shape a detector. */
    private val extraDetectors = HashMap<Triple<Int, Int, Int>, OpenCvOrb>()

    /** The detector for [settings]; a cached extra one when they differ from the general config. */
    private fun detectorFor(settings: OrbConfig): OpenCvOrb {
        if (settings.maxFeatures == config.maxFeatures && settings.fastThreshold == config.fastThreshold &&
            settings.pyramidLevels == config.pyramidLevels
        ) return orb
        val key = Triple(settings.maxFeatures, settings.fastThreshold, settings.pyramidLevels)
        return extraDetectors.getOrPut(key) { createDetector(settings.maxFeatures, settings.fastThreshold, settings.pyramidLevels) }
    }

    /**
     * Applies new settings and re-extracts the features of every reference with them.
     * @param newConfig the new settings
     * @return name → keypoints of each reference afterwards; 0 = the reference was dropped
     */
    @Synchronized
    fun applySettings(newConfig: OrbConfig): Map<String, Int> {
        config = newConfig
        orb = createDetector(newConfig.maxFeatures, newConfig.fastThreshold, newConfig.pyramidLevels)
        wideOrb = createDetector(newConfig.maxFeatures * 4, newConfig.fastThreshold, newConfig.pyramidLevels)
        val images = references.mapValues { it.value.image }
        clearReferences()
        return images.mapValues { (name, image) ->
            val gray = image.toMat()
            try { addReferenceMat(name, gray)?.keypointCount ?: 0 } finally { gray.release() }
        }
    }

    /** Detects keypoints and computes descriptors with the current settings (grid distribution if enabled). */
    private fun extract(image: Mat, keypoints: MatOfKeyPoint, descriptors: Mat, settings: OrbConfig = config) {
        val detector = detectorFor(settings)
        if (!settings.gridDistribution) {
            detector.detectAndCompute(image, noMask, keypoints, descriptors)
            return
        }
        val candidates = MatOfKeyPoint()
        try {
            wideOrb.detect(image, candidates)
            val cols = settings.gridColumns
            val rows = settings.gridRows
            val perCell = (settings.maxFeatures + cols * rows - 1) / (cols * rows)
            val cellW = image.cols().toDouble() / cols
            val cellH = image.rows().toDouble() / rows
            val kept = candidates.toArray()
                .groupBy { kp -> minOf(rows - 1, (kp.pt.y / cellH).toInt()) * cols + minOf(cols - 1, (kp.pt.x / cellW).toInt()) }
                .values.flatMap { cell -> cell.sortedByDescending { it.response }.take(perCell) }
            keypoints.fromList(kept)
            detector.compute(image, keypoints, descriptors)
        } finally {
            candidates.release()
        }
    }

    /** Empty mask = "use the whole image"; reused to avoid allocating one per call. */
    private val noMask = Mat()

    /** The reference dictionary: class name -> reference. Insertion order is kept. */
    private val references = LinkedHashMap<String, ObjectReference>()

    /**
     * Result of loading the asset images during construction. Declared after all fields it needs,
     * because Kotlin initialises properties in order.
     */
    val assetLoadReport: AssetLoadReport = loadReferencesFromAssets(context.assets)

    // ---- reference dictionary ------------------------------------------------

    /**
     * Learns [className] from [image], replacing a reference of the same name.
     * @param className name of the reference
     * @param image ARGB_8888 or RGB_565 bitmap, ideally cropped to the object
     * @return the stored reference, or null if the image has too few features
     */
    @Synchronized
    fun addReference(className: String, image: Bitmap): ObjectReference? {
        val gray = image.toGrayMat()
        try {
            return addReferenceMat(className, gray)
        } finally {
            gray.release()
        }
    }

    /**
     * Learns [className] from a grayscale [image], replacing a reference of the same name.
     * @param className name of the reference
     * @param image grayscale image, ideally cropped to the object
     * @return the stored reference, or null if the image has too few features
     */
    @Synchronized
    fun addReference(className: String, image: GrayImage): ObjectReference? {
        val gray = image.toMat()
        try {
            return addReferenceMat(className, gray)
        } finally {
            gray.release()
        }
    }

    /** The reference stored under [className], or null. */
    @Synchronized
    operator fun get(className: String): ObjectReference? = references[className]

    /** Snapshot of the dictionary. Changing ORB afterwards does not change this map. */
    val referenceMap: Map<String, ObjectReference>
        @Synchronized get() = LinkedHashMap(references)

    /** All class names currently stored. */
    val classNames: Set<String>
        @Synchronized get() = references.keys.toSet()

    /** Removes and frees one reference. Returns false if there was none with that name. */
    @Synchronized
    fun removeReference(className: String): Boolean {
        val removed = references.remove(className) ?: return false
        removed.release()
        return true
    }

    /** Removes and frees all references. */
    @Synchronized
    fun clearReferences() {
        references.values.forEach { it.release() }
        references.clear()
    }

    // ---- detection -------------------------------------------------------------

    /**
     * Returns the references detected in [frame].
     * @param frame grayscale camera frame
     * @param classNames references to search for; null = all
     */
    fun detect(frame: GrayImage, classNames: Collection<String>? = null): List<ObjectMatch> =
        evaluate(frame, classNames).filter { it.detected }

    /**
     * Returns the references detected in a bitmap [frame] (ARGB_8888 or RGB_565), in its pixel coordinates.
     * @param frame camera frame
     * @param classNames references to search for; null = all
     */
    fun detect(frame: Bitmap, classNames: Collection<String>? = null): List<ObjectMatch> =
        evaluate(frame, classNames).filter { it.detected }

    /**
     * Compares [frame] with the references and returns one result per reference, detected or not.
     * @param frame grayscale camera frame
     * @param classNames references to compare; null = all, unknown names are ignored
     */
    @Synchronized
    fun evaluate(frame: GrayImage, classNames: Collection<String>? = null): List<ObjectMatch> {
        val gray = frame.toMat()
        try {
            return evaluateGray(gray, classNames)
        } finally {
            gray.release()
        }
    }

    /**
     * Searches overlapping, enlarged tiles of [frame] and returns the best result per reference, in frame coordinates.
     * @param frame grayscale camera frame
     * @param cols number of tile columns
     * @param rows number of tile rows
     * @param overlap overlap between neighbouring tiles, as a share of a tile
     * @param upscale enlargement factor of each tile
     * @param classNames references to compare; null = all
     */
    @Synchronized
    fun evaluateTiles(
        frame: GrayImage,
        cols: Int = 2,
        rows: Int = 2,
        overlap: Double = 0.25,
        upscale: Double = 2.0,
        classNames: Collection<String>? = null
    ): List<ObjectMatch> {
        val full = frame.toMat()
        val best = LinkedHashMap<String, ObjectMatch>()
        var keypoints = 0
        val positions = ArrayList<ImagePoint>()
        try {
            val tileW = (frame.width / (cols - overlap * (cols - 1))).toInt().coerceAtMost(frame.width)
            val tileH = (frame.height / (rows - overlap * (rows - 1))).toInt().coerceAtMost(frame.height)
            for (r in 0 until rows) for (c in 0 until cols) {
                val x0 = if (cols == 1) 0 else (c * (frame.width - tileW) / (cols - 1))
                val y0 = if (rows == 1) 0 else (r * (frame.height - tileH) / (rows - 1))
                for (mapped in evaluateCrop(full, ImageRegion(x0, y0, tileW, tileH), upscale, classNames)) {
                    val current = best[mapped.className]
                    if (current == null || better(mapped, current)) best[mapped.className] = mapped
                }
                keypoints += lastFrameKeypoints
                positions += lastKeypointPositions
            }
        } finally {
            full.release()
        }
        lastFrameKeypoints = keypoints
        lastKeypointPositions = positions
        return best.values.toList()
    }

    /**
     * Searches only [region] of [frame], scaled for matching; results are in frame coordinates.
     * @param frame grayscale camera frame
     * @param region area to search
     * @param targetSide target length of the region's longer side after scaling, in pixels
     * @param maxUpscale maximum enlargement factor
     * @param classNames references to compare; null = all
     */
    @Synchronized
    fun evaluateRegion(
        frame: GrayImage,
        region: ImageRegion,
        targetSide: Int = 800,
        maxUpscale: Double = 3.0,
        classNames: Collection<String>? = null,
        /** Exact scale factor for the crop (e.g. from the object's expected size); overrides [targetSide]/[maxUpscale]. */
        scale: Double? = null
    ): List<ObjectMatch> {
        val x0 = region.x.coerceIn(0, frame.width - 1)
        val y0 = region.y.coerceIn(0, frame.height - 1)
        val clipped = ImageRegion(x0, y0, (region.x + region.width).coerceAtMost(frame.width) - x0,
            (region.y + region.height).coerceAtMost(frame.height) - y0)
        if (clipped.width < 8 || clipped.height < 8) return emptyList()
        val upscale = scale ?: (targetSide.toDouble() / max(clipped.width, clipped.height)).coerceIn(1.0, maxUpscale)
        val full = frame.toMat()
        try {
            return evaluateCrop(full, clipped, upscale, classNames)
        } finally {
            full.release()
        }
    }

    /** Evaluates [crop] of [full], scaled by [upscale]; corners are mapped back to [full] coordinates. */
    private fun evaluateCrop(full: Mat, crop: ImageRegion, upscale: Double, classNames: Collection<String>?): List<ObjectMatch> {
        val part = full.submat(crop.y, crop.y + crop.height, crop.x, crop.x + crop.width)
        val scaled = Mat()
        try {
            val tResize = System.nanoTime()
            val input = if (upscale == 1.0) part else scaled.also {
                Imgproc.resize(part, it, Size(crop.width * upscale, crop.height * upscale), 0.0, 0.0,
                    if (upscale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
            }
            timings.resizeNs += System.nanoTime() - tResize
            fun back(p: ImagePoint) = ImagePoint(p.x / upscale + crop.x, p.y / upscale + crop.y)
            val results = evaluateGray(input, classNames).map { m ->
                m.copy(corners = m.corners.map(::back), inlierPoints = m.inlierPoints.map(::back))
            }
            lastKeypointPositions = lastKeypointPositions.map(::back)
            return results
        } finally {
            scaled.release()
            part.release()
        }
    }

    /** Which of two results is better: detected first, then more inliers, then more good matches. */
    private fun better(a: ObjectMatch, b: ObjectMatch): Boolean =
        if (a.detected != b.detected) a.detected else if (a.inliers != b.inliers) a.inliers > b.inliers else a.goodMatches > b.goodMatches

    /** Same as the [GrayImage] overload, for a bitmap frame (ARGB_8888 or RGB_565). */
    @Synchronized
    fun evaluate(frame: Bitmap, classNames: Collection<String>? = null): List<ObjectMatch> {
        val gray = frame.toGrayMat()
        try {
            return evaluateGray(gray, classNames)
        } finally {
            gray.release()
        }
    }

    /** Frees all references and internal buffers. Do not use this instance afterwards. */
    @Synchronized
    fun release() {
        extraDetectors.clear()
        clearReferences()
        noMask.release()
    }

    // ---- internals -------------------------------------------------------------

    /** Shared body of both evaluate overloads; [gray] is a single-channel frame owned by the caller. */
    private fun evaluateGray(gray: Mat, classNames: Collection<String>?): List<ObjectMatch> {
        val selected = if (classNames == null) references.values.toList()
        else classNames.mapNotNull { references[it] }
        if (selected.isEmpty()) return emptyList()

        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val boosted = if (config.contrastBoost) Mat().also { clahe.apply(gray, it) } else null
        try {
            val tExtract = System.nanoTime()
            extract(boosted ?: gray, keypoints, descriptors)
            timings.extractNs += System.nanoTime() - tExtract
            lastFrameKeypoints = keypoints.rows()
            val frameKeypoints = keypoints.toArray()
            lastKeypointPositions = if (collectDrawData) frameKeypoints.map { ImagePoint(it.pt.x, it.pt.y) } else emptyList()
            // A frame without texture (dark room, lens covered, a tiny crop) has no usable descriptors.
            if (descriptors.rows() < 2 || frameKeypoints.size < config.minFrameKeypoints) {
                return selected.map { notFound(it.className, 0) }
            }
            return selected.map { match(it, descriptors, frameKeypoints) }
        } finally {
            keypoints.release()
            descriptors.release()
            boosted?.release()
        }
    }

    /** Converts a bitmap (ARGB_8888 or RGB_565, not HARDWARE) into a new grayscale Mat. The caller must release() it. */
    private fun Bitmap.toGrayMat(): Mat {
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(this, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return gray
        } catch (e: Exception) {
            gray.release()
            throw e
        } finally {
            rgba.release()
        }
    }

    /**
     * Loads every image in `assets/ORB_img` as a reference.
     * @param assets the app's asset manager
     * @return which images were loaded and why others were not
     */
    private fun loadReferencesFromAssets(assets: AssetManager): AssetLoadReport {
        val loaded = mutableListOf<String>()
        val rejected = linkedMapOf<String, String>()

        // The assets root also contains folders added by the system or libraries (e.g. "images",
        // "webkit"), which is why only known image extensions are considered.
        val files = runCatching { assets.list(ASSET_DIR) }.getOrNull().orEmpty()
            .filter { it.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            .sorted()

        for (file in files) {
            val className = file.substringBeforeLast('.')
            if (skipReference(className)) {
                rejected[file] = "switched off in settings.json (enabled: false)"
                continue
            }
            if (className in references) {
                rejected[file] = "duplicate class name '$className', an earlier file already uses it"
                continue
            }
            val path = if (ASSET_DIR.isEmpty()) file else "$ASSET_DIR/$file"
            val bitmap = runCatching { decodeAsset(assets, path) }.getOrNull()
            if (bitmap == null) {
                rejected[file] = "could not be decoded as an image"
                continue
            }
            try {
                if (addReference(className, bitmap) == null) {
                    rejected[file] = "too few features (plain or low-texture image), cannot be detected"
                } else {
                    loaded += className
                }
            } finally {
                bitmap.recycle()
            }
        }

        Log.i(TAG, "loaded ${loaded.size} reference(s) from assets: $loaded")
        rejected.forEach { (file, reason) -> Log.w(TAG, "asset $file not used: $reason") }
        return AssetLoadReport(loaded, rejected)
    }

    /**
     * Decodes an image asset as ARGB_8888, subsampled for large images.
     * @param assets the app's asset manager
     * @param path asset path
     * @return the bitmap, or null if the file is not a decodable image
     */
    private fun decodeAsset(assets: AssetManager, path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= config.maxReferenceSide) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Extracts the features of one reference image and stores it under [className]; null if it has too few features. */
    private fun addReferenceMat(className: String, gray: Mat): ObjectReference? {
        require(className.isNotBlank()) { "className must not be blank" }
        // Per-image settings from settings.json, else the general ones this ORB was built with.
        val settings = configFor(className) ?: config
        val scaled = downscale(gray, settings.maxReferenceSide)
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        try {
            extract(scaled, keypoints, descriptors, settings)
            if (descriptors.rows() < settings.minGoodMatches) {
                descriptors.release()
                return null
            }
            val reference = ObjectReference(className, scaled.toGrayImage(), settings, keypoints.toArray(), descriptors)
            references.put(className, reference)?.release()
            return reference
        } finally {
            keypoints.release()
            if (scaled !== gray) scaled.release()
        }
    }

    /** Scales so the longer side is at most [OrbConfig.maxReferenceSide]. Returns [gray] itself if already small. */
    private fun downscale(gray: Mat, maxSide: Int = config.maxReferenceSide): Mat {
        val longer = max(gray.cols(), gray.rows())
        if (longer <= maxSide) return gray
        val scale = maxSide.toDouble() / longer
        val out = Mat()
        // INTER_AREA avoids aliasing when shrinking, which would create fake keypoints.
        Imgproc.resize(gray, out, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        return out
    }

    /** Steps 2 and 3 of the class description for one reference. */
    private fun match(reference: ObjectReference, frameDescriptors: Mat, frameKeypoints: Array<KeyPoint>): ObjectMatch {
        // Each reference is matched with its own settings (settings.json: per-image section, else the general ones).
        val settings = reference.config
        // Exact brute-force Hamming kNN (k = 2), like BFMatcher.knnMatch, but via batchDistance: the results come back as two plain
        // int matrices instead of one Java Mat object per reference feature, which made matching the slowest step on the robot
        // (250–290 ms of a 600 ms pass, 2026-09-17).
        val tKnn = System.nanoTime()
        val distances = Mat()
        val indices = Mat()
        val referencePoints = ArrayList<Point>()
        val framePoints = ArrayList<Point>()
        val tRatio: Long
        try {
            Core.batchDistance(reference.descriptors, frameDescriptors, distances, CvType.CV_32S, indices, Core.NORM_HAMMING, 2, noMask, 0, false)
            tRatio = System.nanoTime()
            timings.knnNs += tRatio - tKnn
            val rows = distances.rows()
            val dist = IntArray(rows * 2).also { if (rows > 0) distances.get(0, 0, it) }
            val idx = IntArray(rows * 2).also { if (rows > 0) indices.get(0, 0, it) }
            // One frame keypoint may be claimed by at most ONE reference feature: keep the closest.
            // Without this, a crop with very few keypoints matches hundreds of reference features onto the same handful of
            // points; the ratio test passes by chance and findHomography collapses them into a degenerate transform with a
            // huge inlier count. Measured on the robot 2026-09-21: 9 frame keypoints, "446 good matches, 336 inliers" on a
            // 32 px speck with no calendar in the room. After the rule, good matches can never exceed the frame's keypoints.
            val bestForFrame = HashMap<Int, Int>(frameKeypoints.size * 2)
            val bestDistance = HashMap<Int, Int>(frameKeypoints.size * 2)
            for (i in 0 until rows) {
                val bestIdx = idx[2 * i]
                if (bestIdx < 0 || idx[2 * i + 1] < 0) continue
                if (dist[2 * i] < settings.ratioTest * dist[2 * i + 1]) {
                    val d = dist[2 * i]
                    val previous = bestDistance[bestIdx]
                    if (previous == null || d < previous) {
                        bestDistance[bestIdx] = d
                        bestForFrame[bestIdx] = i
                    }
                }
            }
            for ((frameIdx, referenceIdx) in bestForFrame) {
                referencePoints += reference.keypoints[referenceIdx].pt
                framePoints += frameKeypoints[frameIdx].pt
            }
        } finally {
            distances.release()
            indices.release()
        }

        timings.ratioNs += System.nanoTime() - tRatio
        val good = referencePoints.size
        if (good < settings.minGoodMatches) return notFound(reference.className, good)

        val src = MatOfPoint2f().apply { fromList(referencePoints) }
        val dst = MatOfPoint2f().apply { fromList(framePoints) }
        val inlierMask = Mat()
        val tHomography = System.nanoTime()
        val homography = Calib3d.findHomography(src, dst, settings.homographyMethod, settings.ransacReprojThreshold, inlierMask,
            settings.homographyMaxIters, settings.homographyConfidence)
        timings.homographyNs += System.nanoTime() - tHomography
        try {
            if (homography.empty()) return notFound(reference.className, good)
            val inliers = Core.countNonZero(inlierMask)
            val corners = projectCorners(homography, reference.image.width, reference.image.height)
            val mask = ByteArray(inlierMask.rows()).also { inlierMask.get(0, 0, it) }
            val inlierPoints = framePoints.filterIndexed { i, _ -> i < mask.size && mask[i].toInt() != 0 }.map { ImagePoint(it.x, it.y) }
            if (inliers < settings.minInliers) return ObjectMatch(reference.className, false, good, inliers, corners, inlierPoints = inlierPoints)
            val problem = outlineProblem(corners, settings.minAreaPx)
            if (problem == null) return ObjectMatch(reference.className, true, good, inliers, corners, "outline", inlierPoints = inlierPoints)
            // Not detected. (Accepting the inliers' bounding box here was tried on the robot, 2026-09-17: many false detections
            // on the whiteboard without the calendar. A caller with a second identifier, e.g. a colour marker, may decide.)
            return ObjectMatch(reference.className, false, good, inliers, corners, "", problem, inlierPoints)
        } finally {
            src.release()
            dst.release()
            inlierMask.release()
            homography.release()
        }
    }

    /** Maps the reference image's corners through the homography into frame coordinates. */
    private fun projectCorners(homography: Mat, width: Int, height: Int): List<ImagePoint> {
        val w = width.toDouble()
        val h = height.toDouble()
        val src = MatOfPoint2f(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))
        val dst = MatOfPoint2f()
        try {
            Core.perspectiveTransform(src, dst, homography)
            return dst.toArray().map { ImagePoint(it.x, it.y) }
        } finally {
            src.release()
            dst.release()
        }
    }

    /**
     * Checks whether projected corners form a plausible outline.
     * @param corners the four projected corners
     * @param minAreaPx minimum area in pixels
     * @return null if plausible, otherwise "non-convex" or "area"
     */
    private fun outlineProblem(corners: List<ImagePoint>, minAreaPx: Double = config.minAreaPx): String? {
        if (corners.size != 4) return "non-convex"
        var sign = 0
        var doubleArea = 0.0
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val c = corners[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0 || (sign != 0 && s != sign)) return "non-convex"
            sign = s
            doubleArea += a.x * b.y - b.x * a.y // shoelace formula
        }
        return if (abs(doubleArea) / 2 >= minAreaPx) null else "area"
    }

    /** A "not detected" result for [className]. */
    private fun notFound(className: String, goodMatches: Int) =
        ObjectMatch(className, detected = false, goodMatches = goodMatches, inliers = 0, corners = emptyList())

    companion object {
        private const val TAG = "ORB"

        /**
         * Folder inside the APK's assets that holds the reference images: `robocontrol/assets/ORB_img` (app/build.gradle.kts maps
         * `robocontrol/assets` to the assets root). Kept separate so other assets (e.g. the Silero model) never mix with them.
         */
        private const val ASSET_DIR = "ORB_img"

        /** Extensions treated as reference images. HEIC/HEIF decode only where the Android version supports it. */
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif")

        @Volatile
        private var openCvLoaded = false

        /**
         * Loads OpenCV's native library once per process. Called by the constructor; exposed so
         * an app can check availability early, e.g. at startup.
         */
        @Synchronized
        fun loadOpenCv(): Boolean {
            if (!openCvLoaded) {
                openCvLoaded = OpenCVLoader.initLocal()
                // Before the first parallel OpenCV call: otherwise OpenCV starts a pool of 7 helper threads that spin after
                // every short operation (measured 0.8-0.95 of a core on the robot, more than the detection itself).
                if (openCvLoaded) runCatching { org.opencv.core.Core.setNumThreads(DetectionSettings.run.openCvThreads.coerceAtLeast(1)) }
            }
            return openCvLoaded
        }
    }
}
