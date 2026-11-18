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
 * @property minFrameKeypoints keypoints a frame needs before matching
 * @property ransacReprojThreshold maximum reprojection error of an inlier, in pixels
 * @property minAreaPx minimum area of the projected outline, in pixels
 * @property fastThreshold FAST corner threshold; lower values also accept weaker corners
 * @property gridDistribution distribute keypoints over a [gridColumns] × [gridRows] grid instead of keeping the
 *   strongest
 * @property gridColumns grid columns
 * @property gridRows grid rows
 * @property contrastBoost apply CLAHE contrast equalisation to frames before detection
 * @property pyramidLevels pyramid levels
 * @property homographyMethod OpenCV homography method
 * @property homographyMaxIters maximum iterations
 * @property homographyConfidence homography confidence
 */
data class OrbConfig(
    val maxFeatures: Int = 1000,
    val maxReferenceSide: Int = 640,
    val ratioTest: Float = 0.75f,
    val minGoodMatches: Int = 15,
    val minInliers: Int = 12,
    val minFrameKeypoints: Int = 0,
    val ransacReprojThreshold: Double = 5.0,
    val minAreaPx: Double = 400.0,
    val fastThreshold: Int = 20,
    val gridDistribution: Boolean = false,
    val gridColumns: Int = 8,
    val gridRows: Int = 6,
    val contrastBoost: Boolean = false,
    val pyramidLevels: Int = 8,
    val homographyMethod: Int = Calib3d.RANSAC,
    val homographyMaxIters: Int = 2000,
    val homographyConfidence: Double = 0.995
)

/**
 * A learned reference object: its image and ORB features.
 * @property className name of the reference
 * @property image the reference image, grayscale and scaled to [OrbConfig.maxReferenceSide]
 * @property config settings it was read with
 * @property keypoints reference keypoints
 * @property descriptors reference descriptors
 */
class ObjectReference internal constructor(
    val className: String,
    val image: GrayImage,
    val config: OrbConfig,
    internal val keypoints: Array<KeyPoint>,
    internal val descriptors: Mat
) {
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
 *   empty without a homography
 * @property outlineSource where the corners come from
 * @property outlineRejected why a result with enough inliers was not detected, or null
 * @property inlierPoints frame positions of the inliers
 */
data class ObjectMatch(
    val className: String,
    val detected: Boolean,
    val goodMatches: Int,
    val inliers: Int,
    val corners: List<ImagePoint>,
    val outlineSource: String = "",
    val outlineRejected: String? = null,
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
 * ORB-based object detection: finds objects from reference images in camera frames. Call off the main thread and
 * [release] when done.
 * @param context used to read the reference images from the assets
 * @param config default settings
 * @param configFor settings for one reference by name; null = [config]
 * @param skipReference true for references that must not be loaded
 * @throws IllegalStateException if OpenCV cannot be loaded
 */
class ORB(
    context: Context,
    config: OrbConfig = OrbConfig(),
    private val configFor: (String) -> OrbConfig? = { null },
    private val skipReference: (String) -> Boolean = { false }
) {
    @Volatile
    var config: OrbConfig = config

    @Volatile
    var lastFrameKeypoints: Int = 0
        private set

    @Volatile
    var lastKeypointPositions: List<ImagePoint> = emptyList()
        private set

    @Volatile
    var collectDrawData: Boolean = true

    /** Time spent per stage since the last [takeTimings], in nanoseconds. For finding out where detection time goes. */
    class Timings {
        var resizeNs = 0L; var extractNs = 0L; var knnNs = 0L; var ratioNs = 0L; var homographyNs = 0L
        /**
         * Adds the stage times of [o].
         * @param o times to add
         */
        fun add(o: Timings) { resizeNs += o.resizeNs; extractNs += o.extractNs; knnNs += o.knnNs; ratioNs += o.ratioNs; homographyNs += o.homographyNs }
    }

    private var timings = Timings()

    /**
     * Returns the stage timings collected since the previous call and starts a new collection.
     * @return the collected timings
     */
    @Synchronized
    fun takeTimings(): Timings = timings.also { timings = Timings() }

    private val clahe by lazy { Imgproc.createCLAHE(2.0, Size(8.0, 8.0)) }

    init {
        check(loadOpenCv()) { "OpenCV native library could not be loaded" }
    }

    private var orb: OpenCvOrb = createDetector(config.maxFeatures, config.fastThreshold, config.pyramidLevels)

    private var wideOrb: OpenCvOrb = createDetector(config.maxFeatures * 4, config.fastThreshold, config.pyramidLevels)

    /**
     * A new OpenCV ORB detector with the given number of features, FAST threshold and pyramid levels.
     * @param features maximum keypoints
     * @param fastThreshold FAST threshold
     * @param levels pyramid levels
     * @return the detector
     */
    private fun createDetector(features: Int, fastThreshold: Int, levels: Int): OpenCvOrb =
        OpenCvOrb.create(features, 1.2f, levels.coerceIn(1, 12), 31, 0, 2, 0 /* HARRIS_SCORE */, 31, fastThreshold)

    private val extraDetectors = HashMap<Triple<Int, Int, Int>, OpenCvOrb>()

    /**
     * The detector for [settings]; a cached extra one when they differ from the general config.
     * @param settings ORB settings
     * @return the detector
     */
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

    /**
     * Detects keypoints and computes descriptors with the current settings (grid distribution if enabled).
     * @param image grayscale image
     * @param keypoints receives the keypoints
     * @param descriptors receives the descriptors
     * @param settings ORB settings
     */
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

    private val noMask = Mat()

    private val references = LinkedHashMap<String, ObjectReference>()

    val assetLoadReport: AssetLoadReport = loadReferencesFromAssets(context.assets)

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

    /**
     * The reference stored under [className], or null.
     * @param className reference name
     * @return the reference, or null
     */
    @Synchronized
    operator fun get(className: String): ObjectReference? = references[className]

    val referenceMap: Map<String, ObjectReference>
        @Synchronized get() = LinkedHashMap(references)

    val classNames: Set<String>
        @Synchronized get() = references.keys.toSet()

    /**
     * Removes and frees one reference. Returns false if there was none with that name.
     * @param className reference name
     * @return true if a reference was removed
     */
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

    /**
     * Returns the references detected in [frame].
     * @param frame grayscale camera frame
     * @param classNames references to search for; null = all
     * @return the detected references
     */
    fun detect(frame: GrayImage, classNames: Collection<String>? = null): List<ObjectMatch> =
        evaluate(frame, classNames).filter { it.detected }

    /**
     * Returns the references detected in a bitmap [frame].
     * @param frame camera frame
     * @param classNames references to search for; null = all
     * @return the detected references
     */
    fun detect(frame: Bitmap, classNames: Collection<String>? = null): List<ObjectMatch> =
        evaluate(frame, classNames).filter { it.detected }

    /**
     * Compares [frame] with the references and returns one result per reference, detected or not.
     * @param frame grayscale camera frame
     * @param classNames references to compare; null = all, unknown names are ignored
     * @return one result per reference
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
     * Searches overlapping, enlarged tiles of [frame].
     * @param frame grayscale camera frame
     * @param cols number of tile columns
     * @param rows number of tile rows
     * @param overlap overlap between neighbouring tiles, as a share of a tile
     * @param upscale enlargement factor of each tile
     * @param classNames references to compare; null = all
     * @return the best result per reference
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
     * @param scale exact scale of the crop, or null to use [targetSide]
     * @return one result per reference
     */
    @Synchronized
    fun evaluateRegion(
        frame: GrayImage,
        region: ImageRegion,
        targetSide: Int = 800,
        maxUpscale: Double = 3.0,
        classNames: Collection<String>? = null,
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

    /**
     * Evaluates [crop] of [full], scaled by [upscale]; corners are mapped back to [full] coordinates.
     * @param full whole frame
     * @param crop region to search
     * @param upscale scale factor
     * @param classNames references to search, or null for all
     * @return one result per reference
     */
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
            /**
             * Maps a crop point back to frame coordinates.
             * @param p point in the crop
             * @return the frame point
             */
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

    /**
     * Which of two results is better: detected first, then more inliers, then more good matches.
     * @param a first result
     * @param b second result
     * @return true if [a] is better
     */
    private fun better(a: ObjectMatch, b: ObjectMatch): Boolean =
        if (a.detected != b.detected) a.detected else if (a.inliers != b.inliers) a.inliers > b.inliers else a.goodMatches > b.goodMatches

    /**
     * Same as the [GrayImage] overload, for a bitmap frame (ARGB_8888 or RGB_565).
     * @param frame bitmap frame
     * @param classNames references to search, or null for all
     * @return one result per reference
     */
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

    /**
     * Shared body of both evaluate overloads; [gray] is a single-channel frame owned by the caller.
     * @param gray grayscale frame
     * @param classNames references to search, or null for all
     * @return one result per reference
     */
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

    /**
     * Converts a bitmap (ARGB_8888 or RGB_565, not HARDWARE) into a new grayscale Mat. The caller must release() it.
     * @return the grayscale Mat
     */
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

    /**
     * Extracts the features of one reference image and stores it under [className]; null if it has too few features.
     * @param className reference name
     * @param gray grayscale image
     * @return the reference, or null
     */
    private fun addReferenceMat(className: String, gray: Mat): ObjectReference? {
        require(className.isNotBlank()) { "className must not be blank" }
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

    /**
     * Scales so the longer side is at most [OrbConfig.maxReferenceSide]. Returns [gray] itself if already small.
     * @param gray grayscale image
     * @param maxSide longest allowed side
     * @return the scaled image
     */
    private fun downscale(gray: Mat, maxSide: Int = config.maxReferenceSide): Mat {
        val longer = max(gray.cols(), gray.rows())
        if (longer <= maxSide) return gray
        val scale = maxSide.toDouble() / longer
        val out = Mat()
        // INTER_AREA avoids aliasing when shrinking, which would create fake keypoints.
        Imgproc.resize(gray, out, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        return out
    }

    /**
     * Matches one reference against the frame features.
     * @param reference reference
     * @param frameDescriptors frame descriptors
     * @param frameKeypoints frame keypoints
     * @return the result
     */
    private fun match(reference: ObjectReference, frameDescriptors: Mat, frameKeypoints: Array<KeyPoint>): ObjectMatch {
        val settings = reference.config
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
            // Each frame keypoint may be claimed by only one reference feature, which prevents degenerate homographies.
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
            return ObjectMatch(reference.className, false, good, inliers, corners, "", problem, inlierPoints)
        } finally {
            src.release()
            dst.release()
            inlierMask.release()
            homography.release()
        }
    }

    /**
     * Maps the reference image's corners through the homography into frame coordinates.
     * @param homography homography matrix
     * @param width reference width
     * @param height reference height
     * @return the projected corners
     */
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

    /**
     * A "not detected" result for [className].
     * @param className reference name
     * @param goodMatches good matches found
     * @return the result
     */
    private fun notFound(className: String, goodMatches: Int) =
        ObjectMatch(className, detected = false, goodMatches = goodMatches, inliers = 0, corners = emptyList())

    companion object {
        private const val TAG = "ORB"

        private const val ASSET_DIR = "ORB_img"

        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif")

        @Volatile
        private var openCvLoaded = false

        /**
         * Loads OpenCV's native library once per process. Called by the constructor; exposed so
         * an app can check availability early, e.g. at startup.
         * @return true if OpenCV is available
         */
        @Synchronized
        fun loadOpenCv(): Boolean {
            if (!openCvLoaded) {
                openCvLoaded = OpenCVLoader.initLocal()
                // Before the first parallel OpenCV call, otherwise OpenCV starts a busy-waiting helper pool.
                if (openCvLoaded) runCatching { org.opencv.core.Core.setNumThreads(DetectionSettings.run.openCvThreads.coerceAtLeast(1)) }
            }
            return openCvLoaded
        }
    }
}
