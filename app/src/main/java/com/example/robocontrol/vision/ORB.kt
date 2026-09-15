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
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import org.opencv.features2d.ORB as OpenCvOrb

/**
 * Tuning parameters for [ORB]. The defaults follow the OpenCV feature-homography tutorial and
 * are a starting point; tune [minInliers] on the robot with real objects and lighting.
 *
 * @property maxFeatures keypoints ORB extracts per image. More = more robust, slower.
 * @property maxReferenceSide reference images are scaled down so their longer side is at most this.
 *           Matches the 640×480 camera frames, so a phone photo is compared at a similar scale.
 * @property ratioTest Lowe's ratio test: a match counts only if its distance is below this share of
 *           the second-best match's distance. Lower = stricter, fewer false matches.
 * @property minGoodMatches matches that must survive the ratio test before a homography is tried.
 * @property minInliers matches that must agree with the homography for the object to count as detected.
 * @property ransacReprojThreshold pixels a match may deviate from the homography and still be an inlier.
 * @property minAreaPx the projected outline must cover at least this many pixels; smaller outlines
 *           are almost always degenerate homographies, not tiny objects.
 */
data class OrbConfig(
    val maxFeatures: Int = 1000,
    val maxReferenceSide: Int = 640,
    val ratioTest: Float = 0.75f,
    val minGoodMatches: Int = 15,
    val minInliers: Int = 12,
    val ransacReprojThreshold: Double = 5.0,
    val minAreaPx: Double = 400.0
)

/**
 * One reference object: the image it was learned from and its ORB features.
 *
 * Features live in native OpenCV memory. They are released by [ORB] when the reference is
 * removed or replaced, or when [ORB.release] is called, so never keep using a reference after that.
 *
 * @property className the dictionary key, e.g. "calendar"
 * @property image the reference as stored: grayscale and scaled down to [OrbConfig.maxReferenceSide]
 */
class ObjectReference internal constructor(
    val className: String,
    val image: GrayImage,
    internal val keypoints: Array<KeyPoint>,
    internal val descriptors: Mat
) {
    /** Number of ORB keypoints found. Few keypoints (< ~100) means a poorly textured reference. */
    val keypointCount: Int get() = keypoints.size

    internal fun release() = descriptors.release()
}

/**
 * Result of comparing one reference against one frame.
 *
 * @property detected true if enough matches agree on a plausible outline
 * @property goodMatches matches that passed the ratio test
 * @property inliers matches consistent with the homography (0 if none was computed)
 * @property corners the reference's four corners projected into the frame, in order top-left,
 *           top-right, bottom-right, bottom-left of the reference image. Empty if no homography
 *           was found. Meaningful when [detected]; for non-flat objects only approximate.
 */
data class ObjectMatch(
    val className: String,
    val detected: Boolean,
    val goodMatches: Int,
    val inliers: Int,
    val corners: List<ImagePoint>
)

/**
 * What happened to the reference images in the assets folder when an [ORB] was created.
 *
 * @property loaded class names now in the dictionary, in file-name order
 * @property rejected file name -> reason, for every image that is NOT in the dictionary
 */
data class AssetLoadReport(
    val loaded: List<String>,
    val rejected: Map<String, String>
)

/**
 * ORB-based object re-detection: learn an object from one reference image, then find that same
 * object in new camera frames.
 *
 * References are kept in a dictionary keyed by class name. On creation, every image in the app's
 * assets (the folder `robocontrol/assets`, see app/build.gradle.kts) is loaded automatically, with
 * its file name without extension as the key, e.g. `calendar.jpg` -> "calendar":
 * ```
 * val orb = ORB(applicationContext)        // on a background thread: loads and analyses all assets
 * orb.assetLoadReport.rejected             // images that could not be used, with the reason
 * orb["calendar"]                          // the stored reference, or null
 * orb.addReference("poster", posterBitmap) // more references can still be added at runtime
 *
 * for (match in orb.detect(frame)) {       // frame: a GrayImage or Bitmap supplied by the caller
 *     // match.className, match.corners -> region to blur
 * }
 * ```
 *
 * ORB never accesses the camera. Getting frames is the caller's job, e.g. a background thread in the
 * activity that grabs a frame every 0.5–1 s (for instance with [CameraSnapshot]) and passes it to
 * [detect]. That keeps camera lifecycle, timing and permissions out of the detector.
 *
 * How detection works (per frame):
 *  1. ORB keypoints and binary descriptors are computed for the frame, once.
 *  2. For each reference, every reference descriptor gets its two nearest frame descriptors
 *     (Hamming distance); Lowe's ratio test keeps only distinctive matches.
 *  3. With enough good matches, a homography is fitted with RANSAC. The matches that agree with it
 *     are the inliers; enough inliers and a convex, non-tiny outline mean "detected".
 *
 * Because step 1 runs once per frame, adding references only adds cheap matching.
 *
 * Works well for textured, mostly flat objects (calendars, posters, book covers, labels, screens).
 * Works poorly for plain or shiny objects, strong viewpoint changes, motion blur, or objects much
 * smaller in the frame than in the reference. ORB is rotation-invariant, so the camera's 90°
 * sensor orientation does not affect matching; corners are in the frame's own coordinates.
 *
 * Thread-safe: all public methods are synchronized. Detection is CPU-heavy, so call from a background
 * dispatcher, never the main thread.
 *
 * Call [release] when done; OpenCV objects hold native memory the garbage collector does not see.
 *
 * @param context used only to read the reference images from the app's assets
 * @param config matching thresholds, also applied to the asset images
 * @throws IllegalStateException from the constructor if the OpenCV native library cannot be loaded
 */
class ORB(context: Context, private val config: OrbConfig = OrbConfig()) {

    init {
        check(loadOpenCv()) { "OpenCV native library could not be loaded" }
    }

    private val orb: OpenCvOrb = OpenCvOrb.create(config.maxFeatures)
    private val matcher: DescriptorMatcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

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
     * Learns [className] from a bitmap, e.g. a photo loaded from a file or resource.
     * Replaces an existing reference with the same name.
     *
     * The bitmap must be ARGB_8888 or RGB_565 (not HARDWARE); it is converted to grayscale and
     * scaled down to [OrbConfig.maxReferenceSide]. Crop it to the object first: background in the
     * reference produces keypoints that will not be found again.
     *
     * @return the stored reference, or null if the image has too few features to ever be detected
     *         (fewer than [OrbConfig.minGoodMatches] keypoints, typically a plain surface)
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
     * Learns [className] from a grayscale image, e.g. a frame from [CameraSnapshot] in which the
     * object fills most of the view. Replaces an existing reference with the same name.
     *
     * @return the stored reference, or null if the image has too few features (see the Bitmap overload)
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
     * Returns the objects found in [frame]: only matches with [ObjectMatch.detected] = true.
     *
     * @param classNames restrict the search to these references; default: all
     */
    fun detect(frame: GrayImage, classNames: Collection<String>? = null): List<ObjectMatch> =
        evaluate(frame, classNames).filter { it.detected }

    /**
     * Same as the [GrayImage] overload, for a frame the caller already has as a bitmap
     * (ARGB_8888 or RGB_565). It is used at its own size, so corners are in its pixel coordinates;
     * scale very large frames down first, since detection time grows with the pixel count.
     */
    fun detect(frame: Bitmap, classNames: Collection<String>? = null): List<ObjectMatch> =
        evaluate(frame, classNames).filter { it.detected }

    /**
     * Compares [frame] against the references and returns one [ObjectMatch] per reference,
     * detected or not. Use this to tune [OrbConfig]: log goodMatches and inliers for frames that
     * do and do not contain the object, then set the thresholds between them.
     *
     * @param classNames restrict the comparison to these references; unknown names are ignored
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
        try {
            orb.detectAndCompute(gray, noMask, keypoints, descriptors)
            // A frame without texture (dark room, lens covered) has no usable descriptors.
            if (descriptors.rows() < 2) return selected.map { notFound(it.className, 0) }
            val frameKeypoints = keypoints.toArray()
            return selected.map { match(it, descriptors, frameKeypoints) }
        } finally {
            keypoints.release()
            descriptors.release()
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
     * Adds every image in the assets root to the dictionary. Files that are not images (by extension)
     * are ignored silently; images that cannot be used are listed in the report with a reason.
     * If two files share a name (calendar.png and calendar.jpg), the first in alphabetical order wins.
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
     * Decodes an asset into the format OpenCV's bitmapToMat needs (ARGB_8888), whatever the file's
     * own format is (palette PNG, grayscale JPEG, ...). Large photos are subsampled by a power of two
     * while decoding, never below [OrbConfig.maxReferenceSide], so a 12 MP photo never has to fit into
     * memory at full size. The exact downscale to maxReferenceSide happens later in [downscale].
     *
     * @return null if the file is not a decodable image
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

    private fun addReferenceMat(className: String, gray: Mat): ObjectReference? {
        require(className.isNotBlank()) { "className must not be blank" }
        val scaled = downscale(gray)
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        try {
            orb.detectAndCompute(scaled, noMask, keypoints, descriptors)
            if (descriptors.rows() < config.minGoodMatches) {
                descriptors.release()
                return null
            }
            val reference = ObjectReference(className, scaled.toGrayImage(), keypoints.toArray(), descriptors)
            references.put(className, reference)?.release()
            return reference
        } finally {
            keypoints.release()
            if (scaled !== gray) scaled.release()
        }
    }

    /** Scales so the longer side is at most [OrbConfig.maxReferenceSide]. Returns [gray] itself if already small. */
    private fun downscale(gray: Mat): Mat {
        val longer = max(gray.cols(), gray.rows())
        if (longer <= config.maxReferenceSide) return gray
        val scale = config.maxReferenceSide.toDouble() / longer
        val out = Mat()
        // INTER_AREA avoids aliasing when shrinking, which would create fake keypoints.
        Imgproc.resize(gray, out, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        return out
    }

    /** Steps 2 and 3 of the class description for one reference. */
    private fun match(reference: ObjectReference, frameDescriptors: Mat, frameKeypoints: Array<KeyPoint>): ObjectMatch {
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(reference.descriptors, frameDescriptors, knn, 2)

        val referencePoints = ArrayList<Point>()
        val framePoints = ArrayList<Point>()
        for (pair in knn) {
            val candidates = pair.toArray()
            pair.release()
            if (candidates.size < 2) continue
            val best = candidates[0]
            val secondBest = candidates[1]
            if (best.distance < config.ratioTest * secondBest.distance) {
                referencePoints += reference.keypoints[best.queryIdx].pt
                framePoints += frameKeypoints[best.trainIdx].pt
            }
        }

        val good = referencePoints.size
        if (good < config.minGoodMatches) return notFound(reference.className, good)

        val src = MatOfPoint2f().apply { fromList(referencePoints) }
        val dst = MatOfPoint2f().apply { fromList(framePoints) }
        val inlierMask = Mat()
        val homography = Calib3d.findHomography(src, dst, Calib3d.RANSAC, config.ransacReprojThreshold, inlierMask)
        try {
            if (homography.empty()) return notFound(reference.className, good)
            val inliers = Core.countNonZero(inlierMask)
            val corners = projectCorners(homography, reference.image.width, reference.image.height)
            val detected = inliers >= config.minInliers && isPlausibleOutline(corners)
            return ObjectMatch(reference.className, detected, good, inliers, corners)
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
     * RANSAC can return a homography that fits a handful of coincidental matches but folds the
     * outline into a bow-tie or a sliver. A real, flat object seen by a camera projects to a
     * convex quadrilateral of reasonable size.
     */
    private fun isPlausibleOutline(corners: List<ImagePoint>): Boolean {
        if (corners.size != 4) return false
        var sign = 0
        var doubleArea = 0.0
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val c = corners[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0 || (sign != 0 && s != sign)) return false
            sign = s
            doubleArea += a.x * b.y - b.x * a.y // shoelace formula
        }
        return abs(doubleArea) / 2 >= config.minAreaPx
    }

    private fun notFound(className: String, goodMatches: Int) =
        ObjectMatch(className, detected = false, goodMatches = goodMatches, inliers = 0, corners = emptyList())

    companion object {
        private const val TAG = "ORB"

        /**
         * Folder inside the APK's assets that holds the reference images. Empty = the root, which is
         * where app/build.gradle.kts maps `robocontrol/assets`.
         */
        private const val ASSET_DIR = ""

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
            if (!openCvLoaded) openCvLoaded = OpenCVLoader.initLocal()
            return openCvLoaded
        }
    }
}
