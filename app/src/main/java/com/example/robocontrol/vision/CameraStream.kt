package com.example.robocontrol.vision

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.ainirobot.coreservice.client.surfaceshare.SurfaceShareApi
import com.ainirobot.coreservice.client.surfaceshare.SurfaceShareBean
import com.ainirobot.coreservice.client.surfaceshare.SurfaceShareError
import com.ainirobot.coreservice.client.surfaceshare.SurfaceShareListener
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicReference

/**
 * One camera frame in memory: brightness for detection, colour (from the camera's YUV data) for the pink marker and display, and
 * brightness statistics. Never stored.
 *
 * Everything except [gray] is computed on first use with native OpenCV code: converting every frame in Kotlin cost 80–100 ms per
 * frame on the robot (measured 2026-09-17), which was the largest part of a detection pass.
 *
 * @property timestampMs elapsedRealtime when the frame arrived
 */
class CameraFrame internal constructor(
    val gray: GrayImage,
    private val uPlane: ByteArray,
    private val vPlane: ByteArray,
    private val uRowStride: Int,
    private val uPixelStride: Int,
    private val vRowStride: Int,
    private val vPixelStride: Int,
    val timestampMs: Long
) {
    /** mean, standard deviation, share ≥ 250 of Y. */
    private val stats: DoubleArray by lazy {
        ORB.loadOpenCv()
        val y = gray.toMat(); val mean = MatOfDouble(); val std = MatOfDouble(); val bright = Mat()
        try {
            Core.meanStdDev(y, mean, std)
            Imgproc.threshold(y, bright, 249.0, 255.0, Imgproc.THRESH_BINARY)
            doubleArrayOf(mean.get(0, 0)[0], std.get(0, 0)[0], Core.countNonZero(bright).toDouble() / (gray.width * gray.height))
        } finally {
            y.release(); mean.release(); std.release(); bright.release()
        }
    }

    /** Average Y (0–255). */
    val meanBrightness: Double get() = stats[0]
    /** Standard deviation of Y (0–~128); low = flat, washed-out or dark image. */
    val contrast: Double get() = stats[1]
    /** Share of pixels at Y ≥ 250 (0–1): overexposed, detail lost there. */
    val clippedShare: Double get() = stats[2]

    /** Chroma planes at their native half resolution, without row/pixel padding: Cb (U) and Cr (V). */
    private val chroma: Pair<ByteArray, ByteArray> by lazy {
        val cw = gray.width / 2; val ch = gray.height / 2
        fun tight(plane: ByteArray, rowStride: Int, pixelStride: Int): ByteArray {
            val out = ByteArray(cw * ch)
            for (row in 0 until ch) {
                val src = row * rowStride
                if (pixelStride == 1) {
                    System.arraycopy(plane, src, out, row * cw, minOf(cw, plane.size - src))
                } else {
                    val dst = row * cw
                    for (col in 0 until cw) out[dst + col] = plane[src + col * pixelStride]
                }
            }
            return out
        }
        tight(uPlane, uRowStride, uPixelStride) to tight(vPlane, vRowStride, vPixelStride)
    }

    /**
     * New RGB Mat (CV_8UC3, caller releases) from the YUV data, full-range BT.601 (JFIF; OpenCV's YCrCb conversion uses the same
     * full-range formulas). [half] = at half size (640×360), where the colour data is native and only brightness is averaged.
     */
    fun rgbMat(half: Boolean): Mat {
        ORB.loadOpenCv()
        val cw = gray.width / 2; val ch = gray.height / 2
        val (cb, cr) = chroma
        val y = gray.toMat()
        val cbMat = Mat(ch, cw, CvType.CV_8UC1).also { it.put(0, 0, cb) }
        val crMat = Mat(ch, cw, CvType.CV_8UC1).also { it.put(0, 0, cr) }
        val a = Mat(); val b = Mat(); val c = Mat(); val ycrcb = Mat()
        try {
            if (half) {
                Imgproc.resize(y, a, Size(cw.toDouble(), ch.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                Core.merge(listOf(a, crMat, cbMat), ycrcb)
            } else {
                val size = Size(gray.width.toDouble(), gray.height.toDouble())
                Imgproc.resize(crMat, b, size, 0.0, 0.0, Imgproc.INTER_NEAREST)
                Imgproc.resize(cbMat, c, size, 0.0, 0.0, Imgproc.INTER_NEAREST)
                Core.merge(listOf(y, b, c), ycrcb)
            }
            val rgb = Mat()
            Imgproc.cvtColor(ycrcb, rgb, Imgproc.COLOR_YCrCb2RGB)
            return rgb
        } finally {
            y.release(); cbMat.release(); crMat.release(); a.release(); b.release(); c.release(); ycrcb.release()
        }
    }

    /** Full-size colour bitmap for display, built on first use. Thread-safe. */
    val preview: Bitmap by lazy {
        val rgb = rgbMat(half = false)
        val rgba = Mat()
        try {
            Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
            Bitmap.createBitmap(gray.width, gray.height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }
        } finally {
            rgb.release(); rgba.release()
        }
    }
}

/**
 * A continuous camera stream through RobotOS's SurfaceShareApi, so the robot's own vision keeps the camera
 * (see [CameraSnapshot], which opens and closes the stream for one frame). Keeps only the latest frame.
 *
 * Needs RobotApi connected first (else [start] reports code −1). Stop it when the screen is not visible: the
 * OrionStar docs warn about memory and CPU cost.
 */
class CameraStream(
    /**
     * Frame size requested from SurfaceShare. RobotOS's own camera session runs at 1280×720 (YUV, full-range "JFIF"; read with
     * `dumpsys media.camera` on the robot, 2026-09-17), so that is the highest real resolution; other sizes are scaled from it
     * (640×480 also squeezes 16:9 into 4:3).
     */
    val width: Int = 1280,
    val height: Int = 720,
    /**
     * Frames per second passed on at most; RobotOS delivers ~30. Extra frames are closed without copying. Owner (2026-09-18): 20, to leave
     * CPU for detection. The stream itself cannot be slowed (SurfaceShare has no frame-rate setting), only our handling of it.
     */
    val maxFps: Int = 20
) {
    /** The newest frame, or null before the first one. */
    val latest = AtomicReference<CameraFrame?>(null)

    @Volatile
    var framesReceived = 0L
        private set

    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var bean: SurfaceShareBean? = null
    private var lastError = 0
    /** Earliest time the next frame is accepted (frame-rate limit). */
    private var nextDueAt = 0L

    // Reused per frame on the camera thread (plane copies and output pixels), so no per-frame allocation except the outputs.
    private val yBytes = ByteArray(width * height)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)

    /**
     * Starts the stream. [onError] gets SurfaceShare errors (e.g. −14 camera used by someone else, −15 camera blocked by
     * the device policy, −16 preempted).
     * @return the requestImageFrame code (≥ 0 = requested)
     */
    @Synchronized
    fun start(onError: (code: Int, message: String?) -> Unit): Int {
        if (reader != null) return 0
        // Only copies frames: background priority, so it does not compete with the detection threads for fast cores.
        val t = HandlerThread("CameraStream", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val r = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ rd ->
            val image = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (maxFps > 0) {
                    // Schedule-based limit: frames arrive every ~33 ms, so "at least 50 ms apart" would give only 15 fps.
                    // Advancing the due time by exactly 1000/maxFps keeps the average at maxFps.
                    if (now < nextDueAt) return@setOnImageAvailableListener
                    val interval = 1000L / maxFps
                    nextDueAt = maxOf(nextDueAt + interval, now - interval)
                }
                latest.set(image.toFrame())
                framesReceived++
            } finally {
                image.close()
            }
        }, Handler(t.looper))
        val b = SurfaceShareBean().apply { name = STREAM_NAME }
        val code = SurfaceShareApi.getInstance().requestImageFrame(r.surface, b, object : SurfaceShareListener() {
            override fun onError(error: Int, message: String?) {
                lastError = error
                onError(error, message)
            }

            override fun onStatusUpdate(status: Int, message: String?) {}
        })
        thread = t
        reader = r
        bean = b
        if (code < 0) stop()
        return code
    }

    @Synchronized
    fun stop() {
        val r = reader ?: return
        bean?.let { b ->
            if (lastError != SurfaceShareError.ERROR_SURFACE_SHARE_USED) runCatching { SurfaceShareApi.getInstance().abandonImageFrame(b) }
        }
        r.setOnImageAvailableListener(null, null)
        thread?.quitSafely()
        runCatching { thread?.join(100) }
        r.close()
        r.surface.release()
        reader = null
        thread = null
        bean = null
        lastError = 0
        latest.set(null)
        yBytes.fill(0)
        uBytes.fill(0)
        vBytes.fill(0)
    }

    /**
     * Copies the three planes in bulk (one buffer read per row instead of per pixel), then builds the gray image, the colour
     * preview (YUV_420_888 → ARGB, BT.601) and the brightness statistics in one pass over plain arrays.
     */
    private fun Image.toFrame(): CameraFrame {
        val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
        val yStride = yPlane.rowStride
        val yBuf = yPlane.buffer
        for (row in 0 until height) {
            yBuf.position(row * yStride)
            yBuf.get(yBytes, row * width, width)
        }
        val uBuf = uPlane.buffer.apply { position(0) }
        val vBuf = vPlane.buffer.apply { position(0) }
        if (uBytes.size != uBuf.remaining()) uBytes = ByteArray(uBuf.remaining())
        if (vBytes.size != vBuf.remaining()) vBytes = ByteArray(vBuf.remaining())
        uBuf.get(uBytes)
        vBuf.get(vBytes)

        // Own copies: the reused buffers change with the next frame, and conversions run later (only if the frame is used).
        return CameraFrame(
            GrayImage(width, height, yBytes.copyOf()),
            uBytes.copyOf(), vBytes.copyOf(),
            uPlane.rowStride, uPlane.pixelStride, vPlane.rowStride, vPlane.pixelStride,
            SystemClock.elapsedRealtime()
        )
    }

    private companion object {
        const val STREAM_NAME = "RoboGuardObjectTest"
    }
}
