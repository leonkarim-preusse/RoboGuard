package com.example.robocontrol.vision

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Settings of one reference image (or the general ones): everything ORB and the pink-marker gate need.
 * Read from `assets/settings/settings.json`; see [DetectionSettings].
 */
data class ObjectSettings(
    /** false = this reference is not searched for at all. */
    val enabled: Boolean = true,
    /** true = ORB runs only inside pink frames; false = the whole camera picture is searched for this reference. */
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
    /** Inliers a confirming check needs (never below [ORB_MIN_INLIERS]). */
    val confirmInliers: Int get() = (minInliers - confirmInlierDrop).coerceAtLeast(ORB_MIN_INLIERS)

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
        /** A homography needs four points, so fewer inliers can never be a detection. */
        const val ORB_MIN_INLIERS = 4

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

/** Settings of the pink-marker search; they work on the whole camera picture, so they cannot be set per image. */
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

/** Settings that concern the detection as a whole, not a single reference image. */
data class RunSettings(
    val consistencyNeeded: Int = 2,
    val consistencyWindow: Int = 2,
    val cooldownSeconds: Int = 3,
    val workers: Int = 2,
    val maxPassesPerSecond: Int = 12,
    val maxRegions: Int = 3
)

/**
 * Reads `assets/settings/settings.json`: the general object-detection settings plus per-image overrides.
 *
 * ```json
 * "orb": {
 *   "general": { "maxFeatures": { "value": 1500, "note": "…" }, … },
 *   "images":  { "cal_prop_thick_outline.jpg": { "minInliers": { "value": 30, "note": "…" } } }
 * }
 * ```
 * Every value may be written as `{ "value": …, "note": "…" }` (the note explains it and is ignored) or as a plain value.
 * A per-image section overrides only the keys it lists; [forImage] merges it onto [general]. Keys that describe the whole
 * camera picture (the `pink` block, workers, passes per second, …) are read from `general` only; naming them under an image
 * is ignored and logged, as are unknown keys.
 *
 * Missing file or broken JSON: the built-in defaults are used (the same values as in the shipped file) and an error is logged,
 * so a typo never stops the detection.
 */
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

    /** Per image (file name as written in the file, plus the name without extension), only the overridden values merged in. */
    @Volatile
    private var images: Map<String, ObjectSettings> = emptyMap()

    @Volatile
    private var appContext: Context? = null

    /** Loads the file (idempotent). Called by CalendarMonitor before the references are read. */
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
     * Settings for one reference image. [name] is its file name or the name without extension (the class name ORB uses).
     * Falls back to [general] when the file names no section for it.
     */
    fun forImage(name: String): ObjectSettings = images[name] ?: images[name.substringBeforeLast('.')] ?: general

    /** All reference names the file mentions with `enabled: false`, so the loader can skip them. */
    fun isEnabled(name: String): Boolean = forImage(name).enabled

    private fun readObject(json: JSONObject, base: ObjectSettings, where: String): ObjectSettings {
        var result = base
        for (key in json.keys()) {
            if (key.startsWith("_") || key == "pink") continue
            val value = unwrap(json, key)
            result = when (key) {
                "enabled" -> result.copy(enabled = value.asBoolean(result.enabled))
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
                // Whole-picture settings: valid under "general", meaningless under an image.
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
                else -> result
            }
        }
        return result
    }

    private val WHOLE_PICTURE_KEYS = setOf(
        "consistencyNeeded", "consistencyWindow", "cooldownSeconds", "workers", "maxPassesPerSecond", "maxRegions"
    )

    /** A value written either as `{ "value": …, "note": "…" }` or plainly. */
    private fun unwrap(json: JSONObject, key: String): Any? {
        val raw = json.opt(key)
        return if (raw is JSONObject) raw.opt("value") else raw
    }

    private fun Any?.asInt(fallback: Int) = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull() ?: fallback
        else -> fallback
    }

    private fun Any?.asDouble(fallback: Double) = when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull() ?: fallback
        else -> fallback
    }

    private fun Any?.asBoolean(fallback: Boolean) = when (this) {
        is Boolean -> this
        is String -> toBooleanStrictOrNull() ?: fallback
        else -> fallback
    }

    private fun Any?.asString(fallback: String) = (this as? String) ?: fallback
}
