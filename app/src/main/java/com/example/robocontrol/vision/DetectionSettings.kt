package com.example.robocontrol.vision

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Settings of one reference image (or the general ones).
 * @property enabled whether the reference is searched
 * @property calendarPopup whether the calendar popup opens
 * @property usePinkMarker whether ORB runs only inside pink frames
 * @property maxFeatures maximum keypoints
 * @property fastThreshold FAST threshold
 * @property pyramidLevels pyramid levels
 * @property gridDistribution whether keypoints are spread over a grid
 * @property gridColumns grid columns
 * @property gridRows grid rows
 * @property contrastBoost whether CLAHE is applied
 * @property maxReferenceSide longest side of a reference
 * @property ratioTest Lowe ratio
 * @property minGoodMatches good matches needed
 * @property minFrameKeypoints keypoints a frame needs
 * @property minInliers inliers needed
 * @property confirmInlierDrop lower inlier count for confirmation
 * @property ransacReprojThreshold RANSAC threshold in pixels
 * @property minAreaPx minimum outline area
 * @property homographyMethod homography method
 * @property homographyMaxIters maximum iterations
 * @property homographyConfidence homography confidence
 * @property scaleRegionToReference whether pink areas are scaled to the reference
 * @property minRegionScale minimum scale
 * @property maxRegionScale maximum scale
 * @property minRegionLongSidePx smallest pink area searched
 * @property orbMarginPx margin around the pink area
 */
data class ObjectSettings(
    val enabled: Boolean = true,
    val calendarPopup: Boolean = false,
    val usePinkMarker: Boolean = true,
    val maxFeatures: Int = 1500,
    val fastThreshold: Int = 10,
    val pyramidLevels: Int = 4,
    val gridDistribution: Boolean = false,
    val gridColumns: Int = 8,
    val gridRows: Int = 6,
    val contrastBoost: Boolean = false,
    val maxReferenceSide: Int = 640,
    val ratioTest: Double = 0.75,
    val minGoodMatches: Int = 15,
    val minFrameKeypoints: Int = 60,
    val minInliers: Int = 20,
    val confirmInlierDrop: Int = 8,
    val ransacReprojThreshold: Double = 5.0,
    val minAreaPx: Double = 400.0,
    val homographyMethod: String = "USAC_DEFAULT",
    val homographyMaxIters: Int = 1000,
    val homographyConfidence: Double = 0.995,
    val scaleRegionToReference: Boolean = true,
    val minRegionScale: Double = 0.5,
    val maxRegionScale: Double = 8.0,
    val minRegionLongSidePx: Int = 100,
    val orbMarginPx: Int = 10
) {
    val confirmInliers: Int get() = (minInliers - confirmInlierDrop).coerceAtLeast(ORB_MIN_INLIERS)

    /**
     * The ORB settings of this object as an [OrbConfig].
     * @return the ORB settings
     */
    fun toOrbConfig(): OrbConfig = OrbConfig(
        maxFeatures = maxFeatures,
        maxReferenceSide = maxReferenceSide,
        ratioTest = ratioTest.toFloat(),
        minGoodMatches = minGoodMatches,
        minFrameKeypoints = minFrameKeypoints,
        minInliers = minInliers,
        ransacReprojThreshold = ransacReprojThreshold,
        minAreaPx = minAreaPx,
        fastThreshold = fastThreshold,
        gridDistribution = gridDistribution,
        gridColumns = gridColumns,
        gridRows = gridRows,
        contrastBoost = contrastBoost,
        pyramidLevels = pyramidLevels,
        homographyMethod = homographyMethodId(homographyMethod),
        homographyMaxIters = homographyMaxIters,
        homographyConfidence = homographyConfidence
    )

    companion object {
        const val ORB_MIN_INLIERS = 4

        /**
         * OpenCV's constant for a homography method named in settings.json (RANSAC, RHO, USAC_…); unknown names give
         * RANSAC.
         * @param name method name
         * @return the OpenCV constant
         */
        fun homographyMethodId(name: String): Int = when (name.uppercase()) {
            "RANSAC" -> org.opencv.calib3d.Calib3d.RANSAC
            "RHO" -> org.opencv.calib3d.Calib3d.RHO
            "USAC_ACCURATE" -> org.opencv.calib3d.Calib3d.USAC_ACCURATE
            "USAC_MAGSAC" -> org.opencv.calib3d.Calib3d.USAC_MAGSAC
            "USAC_FAST" -> org.opencv.calib3d.Calib3d.USAC_FAST
            else -> org.opencv.calib3d.Calib3d.USAC_DEFAULT
        }
    }
}

/**
 * Settings of the pink-marker search; they work on the whole camera picture, so they cannot be set per image.
 * @property marginPx grouping distance
 * @property minSaturation minimum saturation
 * @property whiteBalance whether white balance is applied
 * @property requireFrame whether pink must form a frame
 * @property minSides sides that must contain pink
 * @property minSideCoverage coverage a side needs
 * @property requireWhiteInside whether the inside must be white
 * @property minWhiteShare white share needed
 * @property analysisScale scale of the colour analysis
 * @property holdMs hold time after an accepted frame
 * @property minRegionPixels minimum pink pixels of a group
 * @property minSpeckPixels smaller specks are dropped
 */
data class PinkSettings(
    val marginPx: Int = 40,
    val minSaturation: Int = 50,
    val whiteBalance: Boolean = true,
    val requireFrame: Boolean = true,
    val minSides: Int = 3,
    val minSideCoverage: Double = 0.3,
    val requireWhiteInside: Boolean = true,
    val minWhiteShare: Double = 0.5,
    val analysisScale: Double = 0.5,
    val holdMs: Long = 1500,
    val minRegionPixels: Int = 150,
    val minSpeckPixels: Int = 12
) {
    /**
     * Copies the pink settings onto [marker].
     * @param marker colour search to configure
     */
    fun applyTo(marker: ColorMarker) {
        marker.marginPx = marginPx
        marker.color = MarkerColor.PINK.copy(saturationMin = minSaturation)
        marker.whiteBalance = whiteBalance
        marker.requireFrame = requireFrame
        marker.minSides = minSides
        marker.minSideCoverage = minSideCoverage
        marker.requireWhiteInside = requireWhiteInside
        marker.minWhiteShare = minWhiteShare
        marker.analysisScale = analysisScale
        marker.holdMs = holdMs
        marker.minRegionPixels = minRegionPixels
        marker.minSpeckPixels = minSpeckPixels
    }
}

/**
 * Settings that concern the detection as a whole, not a single reference image.
 * @property consistencyNeeded detections needed
 * @property consistencyWindow passes considered
 * @property cooldownSeconds pause after an announcement
 * @property workers detection threads
 * @property maxPassesPerSecond pass rate limit
 * @property maxRegions pink areas searched
 * @property openCvThreads threads per OpenCV call
 * @property wholeFrameIntervalMs interval of whole-picture searches
 * @property rearmAfterGoneSeconds time out of view before a new announcement
 * @property popupCooldownSeconds quiet time after the popup
 */
data class RunSettings(
    val consistencyNeeded: Int = 2,
    val consistencyWindow: Int = 2,
    val cooldownSeconds: Int = 3,
    val workers: Int = 2,
    val maxPassesPerSecond: Int = 12,
    val maxRegions: Int = 3,
    val openCvThreads: Int = 1,
    val wholeFrameIntervalMs: Int = 500,
    val rearmAfterGoneSeconds: Int = 10,
    val popupCooldownSeconds: Int = 120
)

/** Reads `assets/settings/settings.json`; built-in defaults are used if it is missing or invalid. */
object DetectionSettings {
    private const val TAG = "DetectionSettings"
    private const val ASSET_FILE = "settings/settings.json"

    @Volatile
    var general: ObjectSettings = ObjectSettings()
        private set

    @Volatile
    var pink: PinkSettings = PinkSettings()
        private set

    @Volatile
    var run: RunSettings = RunSettings()
        private set

    @Volatile
    private var images: Map<String, ObjectSettings> = emptyMap()

    @Volatile
    private var appContext: Context? = null

    /**
     * Loads the file. Called by CalendarMonitor before the references are read.
     * @param context Android context
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        reload()
    }

    /** Re-reads the file (after an app update or for tests). */
    @Synchronized
    fun reload() {
        val context = appContext ?: return
        val json = runCatching { JSONObject(context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }) }
            .onFailure { Log.e(TAG, "could not read $ASSET_FILE, using the built-in defaults: $it") }
            .getOrNull() ?: return
        val orb = json.optJSONObject("orb") ?: run {
            Log.e(TAG, "$ASSET_FILE has no \"orb\" section, using the built-in defaults")
            return
        }
        val generalJson = orb.optJSONObject("general") ?: JSONObject()
        general = readObject(generalJson, ObjectSettings(), "general")
        pink = readPink(generalJson.optJSONObject("pink"))
        run = readRun(generalJson)
        val perImage = LinkedHashMap<String, ObjectSettings>()
        orb.optJSONObject("images")?.let { list ->
            for (name in list.keys()) {
                if (name.startsWith("_")) continue
                val section = list.optJSONObject(name) ?: continue
                val merged = readObject(section, general, "image '$name'")
                perImage[name] = merged
                perImage[name.substringBeforeLast('.')] = merged
            }
        }
        images = perImage
        Log.i(TAG, "loaded: general $general, pink $pink, run $run, per-image overrides for ${images.keys.filter { it.contains('.') }}")
    }

    /**
     * Settings for one reference image.
     * @param name file name or class name
     * @return the settings
     */
    fun forImage(name: String): ObjectSettings = images[name] ?: images[name.substringBeforeLast('.')] ?: general

    /**
     * Whether a reference is enabled in the file.
     * @param name reference name
     * @return true if enabled
     */
    fun isEnabled(name: String): Boolean = forImage(name).enabled

    /**
     * Reads the values of one object block; unknown keys and whole-picture keys are logged and ignored.
     * @param json object block
     * @param base defaults
     * @param where name for log lines
     * @return the settings
     */
    private fun readObject(json: JSONObject, base: ObjectSettings, where: String): ObjectSettings {
        var result = base
        for (key in json.keys()) {
            if (key.startsWith("_") || key == "pink") continue
            val value = unwrap(json, key)
            result = when (key) {
                "enabled" -> result.copy(enabled = value.asBoolean(result.enabled))
                "calendarPopup" -> result.copy(calendarPopup = value.asBoolean(result.calendarPopup))
                "usePinkMarker" -> result.copy(usePinkMarker = value.asBoolean(result.usePinkMarker))
                "maxFeatures" -> result.copy(maxFeatures = value.asInt(result.maxFeatures))
                "fastThreshold" -> result.copy(fastThreshold = value.asInt(result.fastThreshold))
                "pyramidLevels" -> result.copy(pyramidLevels = value.asInt(result.pyramidLevels))
                "gridDistribution" -> result.copy(gridDistribution = value.asBoolean(result.gridDistribution))
                "gridColumns" -> result.copy(gridColumns = value.asInt(result.gridColumns))
                "gridRows" -> result.copy(gridRows = value.asInt(result.gridRows))
                "contrastBoost" -> result.copy(contrastBoost = value.asBoolean(result.contrastBoost))
                "maxReferenceSide" -> result.copy(maxReferenceSide = value.asInt(result.maxReferenceSide))
                "ratioTest" -> result.copy(ratioTest = value.asDouble(result.ratioTest))
                "minGoodMatches" -> result.copy(minGoodMatches = value.asInt(result.minGoodMatches))
                "minFrameKeypoints" -> result.copy(minFrameKeypoints = value.asInt(result.minFrameKeypoints))
                "minInliers" -> result.copy(minInliers = value.asInt(result.minInliers))
                "confirmInlierDrop" -> result.copy(confirmInlierDrop = value.asInt(result.confirmInlierDrop))
                "ransacReprojThreshold" -> result.copy(ransacReprojThreshold = value.asDouble(result.ransacReprojThreshold))
                "minAreaPx" -> result.copy(minAreaPx = value.asDouble(result.minAreaPx))
                "homographyMethod" -> result.copy(homographyMethod = value.asString(result.homographyMethod))
                "homographyMaxIters" -> result.copy(homographyMaxIters = value.asInt(result.homographyMaxIters))
                "homographyConfidence" -> result.copy(homographyConfidence = value.asDouble(result.homographyConfidence))
                "scaleRegionToReference" -> result.copy(scaleRegionToReference = value.asBoolean(result.scaleRegionToReference))
                "minRegionScale" -> result.copy(minRegionScale = value.asDouble(result.minRegionScale))
                "maxRegionScale" -> result.copy(maxRegionScale = value.asDouble(result.maxRegionScale))
                "minRegionLongSidePx" -> result.copy(minRegionLongSidePx = value.asInt(result.minRegionLongSidePx))
                "orbMarginPx" -> result.copy(orbMarginPx = value.asInt(result.orbMarginPx))
                in WHOLE_PICTURE_KEYS -> {
                    if (where != "general") Log.w(TAG, "$where: '$key' works on the whole camera picture and is ignored there")
                    result
                }
                else -> {
                    Log.w(TAG, "$where: unknown setting '$key' ignored")
                    result
                }
            }
        }
        return result
    }

    /**
     * Reads the "pink" block; missing values keep their defaults.
     * @param json pink block, or null
     * @return the settings
     */
    private fun readPink(json: JSONObject?): PinkSettings {
        var result = PinkSettings()
        if (json == null) return result
        for (key in json.keys()) {
            if (key.startsWith("_")) continue
            val value = unwrap(json, key)
            result = when (key) {
                "marginPx" -> result.copy(marginPx = value.asInt(result.marginPx))
                "minSaturation" -> result.copy(minSaturation = value.asInt(result.minSaturation))
                "whiteBalance" -> result.copy(whiteBalance = value.asBoolean(result.whiteBalance))
                "requireFrame" -> result.copy(requireFrame = value.asBoolean(result.requireFrame))
                "minSides" -> result.copy(minSides = value.asInt(result.minSides))
                "minSideCoverage" -> result.copy(minSideCoverage = value.asDouble(result.minSideCoverage))
                "requireWhiteInside" -> result.copy(requireWhiteInside = value.asBoolean(result.requireWhiteInside))
                "minWhiteShare" -> result.copy(minWhiteShare = value.asDouble(result.minWhiteShare))
                "analysisScale" -> result.copy(analysisScale = value.asDouble(result.analysisScale))
                "holdMs" -> result.copy(holdMs = value.asInt(result.holdMs.toInt()).toLong())
                "minRegionPixels" -> result.copy(minRegionPixels = value.asInt(result.minRegionPixels))
                "minSpeckPixels" -> result.copy(minSpeckPixels = value.asInt(result.minSpeckPixels))
                else -> { Log.w(TAG, "pink: unknown setting '$key' ignored"); result }
            }
        }
        return result
    }

    /**
     * Reads the whole-picture settings (workers, rate, consistency, cooldowns, ...) from the general block.
     * @param json general block
     * @return the settings
     */
    private fun readRun(json: JSONObject): RunSettings {
        var result = RunSettings()
        for (key in WHOLE_PICTURE_KEYS) {
            if (!json.has(key)) continue
            val value = unwrap(json, key)
            result = when (key) {
                "consistencyNeeded" -> result.copy(consistencyNeeded = value.asInt(result.consistencyNeeded))
                "consistencyWindow" -> result.copy(consistencyWindow = value.asInt(result.consistencyWindow))
                "cooldownSeconds" -> result.copy(cooldownSeconds = value.asInt(result.cooldownSeconds))
                "workers" -> result.copy(workers = value.asInt(result.workers))
                "maxPassesPerSecond" -> result.copy(maxPassesPerSecond = value.asInt(result.maxPassesPerSecond))
                "maxRegions" -> result.copy(maxRegions = value.asInt(result.maxRegions))
                "openCvThreads" -> result.copy(openCvThreads = value.asInt(result.openCvThreads))
                "wholeFrameIntervalMs" -> result.copy(wholeFrameIntervalMs = value.asInt(result.wholeFrameIntervalMs))
                "rearmAfterGoneSeconds" -> result.copy(rearmAfterGoneSeconds = value.asInt(result.rearmAfterGoneSeconds))
                "popupCooldownSeconds" -> result.copy(popupCooldownSeconds = value.asInt(result.popupCooldownSeconds))
                else -> result
            }
        }
        return result
    }

    private val WHOLE_PICTURE_KEYS = setOf(
        "consistencyNeeded", "consistencyWindow", "cooldownSeconds", "workers", "maxPassesPerSecond", "maxRegions",
        "openCvThreads", "wholeFrameIntervalMs", "rearmAfterGoneSeconds", "popupCooldownSeconds"
    )

    /**
     * A value written either as `{ "value": …, "note": "…" }` or plainly.
     * @param json block
     * @param key key
     * @return the value
     */
    private fun unwrap(json: JSONObject, key: String): Any? {
        val raw = json.opt(key)
        return if (raw is JSONObject) raw.opt("value") else raw
    }

    /**
     * A number from the JSON as Int, or [fallback].
     * @param fallback default
     * @return the number
     */
    private fun Any?.asInt(fallback: Int) = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull() ?: fallback
        else -> fallback
    }

    /**
     * A number from the JSON as Double, or [fallback].
     * @param fallback default
     * @return the number
     */
    private fun Any?.asDouble(fallback: Double) = when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull() ?: fallback
        else -> fallback
    }

    /**
     * A boolean from the JSON, or [fallback].
     * @param fallback default
     * @return the boolean
     */
    private fun Any?.asBoolean(fallback: Boolean) = when (this) {
        is Boolean -> this
        is String -> toBooleanStrictOrNull() ?: fallback
        else -> fallback
    }

    /**
     * A string from the JSON, or [fallback].
     * @param fallback default
     * @return the string
     */
    private fun Any?.asString(fallback: String) = (this as? String) ?: fallback
}
