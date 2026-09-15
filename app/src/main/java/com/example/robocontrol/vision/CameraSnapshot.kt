package com.example.robocontrol.vision

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of one [CameraSnapshot.capture]. */
sealed interface SnapshotResult {
    /** How long the whole capture took, including starting and stopping the stream. */
    val elapsedMs: Long

    data class Success(val image: GrayImage, override val elapsedMs: Long) : SnapshotResult
    data class Failure(val reason: SnapshotFailure, override val elapsedMs: Long) : SnapshotResult
}

/** Why no frame was captured. */
sealed interface SnapshotFailure {
    /** requestImageFrame returned -1: RobotApi is not connected, so SurfaceShareApi has no service yet. */
    data object NotConnected : SnapshotFailure

    /** Another app or component holds the shared stream (SurfaceShareError.ERROR_SURFACE_SHARE_USED, -14). */
    data object CameraBusy : SnapshotFailure

    /** The stream was taken over while capturing (SurfaceShareError.ERROR_SURFACE_SHARE_PREEMPTED, -16). */
    data object Preempted : SnapshotFailure

    /** The stream started, but no usable frame arrived within the timeout. */
    data object Timeout : SnapshotFailure

    /** Any other SDK error or negative return code. */
    data class Sdk(val code: Int, val message: String?) : SnapshotFailure
}

/**
 * Takes single grayscale frames from the robot camera through OrionStar's camera data stream
 * sharing ([SurfaceShareApi]), which gives an app frames without taking the camera away from
 * RobotOS's own vision service.
 *
 * Every [capture] opens the stream, waits for one frame, copies its brightness (Y) plane, and
 * closes the stream again, so nothing keeps running between captures. That fits periodic sampling:
 * ```
 * val camera = CameraSnapshot()
 * scope.launch(Dispatchers.Default) {
 *     while (isActive) {
 *         val result = camera.capture()
 *         if (result is SnapshotResult.Success) orb.detect(result.image)
 *         delay(500)
 *     }
 * }
 * ```
 * The OrionStar docs warn the shared stream has high memory and CPU cost and must be closed when not
 * in use; opening it per capture honours that. The price is start-up time per capture, so check
 * [SnapshotResult.elapsedMs] on the robot: if it approaches the sampling interval, keep the stream open
 * instead.
 *
 * Requirements:
 *  - `RobotApi.getInstance().connectServer(...)` must have connected first. SurfaceShareApi is a
 *    sub-API of RobotApi and only gets its service connection then (verified in the jar); before
 *    that, capture fails with [SnapshotFailure.NotConnected].
 *  - The app must be in the foreground (RobotOS serves the SDK to the foreground app only).
 *
 * Image format follows the OrionStar sample: 640×480, YUV_420_888. Only the Y plane is kept, which is
 * exactly the grayscale image [ORB] needs, so no colour conversion is done. Per the OrionStar FAQ the
 * sensor is mounted rotated by 90°; frames are returned as delivered, not rotated.
 *
 * UNVERIFIED on hardware: stream start-up time, whether the first frames are dark (hence
 * [framesToSkip]), the success return value of requestImageFrame (assumed: non-negative), and whether
 * the vision service delivers exactly [width]×[height].
 *
 * Captures are serialized: concurrent callers wait for each other. Call from a background dispatcher;
 * cleanup briefly blocks while the frame thread shuts down.
 *
 * @param width requested frame width, as in the OrionStar sample
 * @param height requested frame height
 * @param framesToSkip frames discarded before one is kept, to let exposure settle after the stream starts
 * @param timeoutMs maximum wait for a usable frame after requesting the stream
 */
class CameraSnapshot(
    private val width: Int = 640,
    private val height: Int = 480,
    private val framesToSkip: Int = 2,
    private val timeoutMs: Long = 3_000
) {

    private val mutex = Mutex()

    /** Opens the shared stream, returns one grayscale frame, and closes the stream again. */
    suspend fun capture(): SnapshotResult = mutex.withLock {
        val t0 = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - t0

        // Frames are delivered on their own thread, as in the OrionStar sample.
        val frameThread = HandlerThread("CameraSnapshot").apply { start() }
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, MAX_BUFFERED_IMAGES)
        val surface = reader.surface
        val bean = SurfaceShareBean().apply { name = STREAM_NAME }

        // Completed by whichever comes first: a usable frame or an SDK error.
        val outcome = CompletableDeferred<SnapshotResult>()
        var lastError = 0
        var streamRequested = false

        var framesSeen = 0
        reader.setOnImageAvailableListener({ r ->
            // acquireLatestImage drops stale frames; the reader may already be closing, hence runCatching.
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                framesSeen++
                if (framesSeen > framesToSkip && !outcome.isCompleted) {
                    outcome.complete(SnapshotResult.Success(image.yPlaneToGray(), elapsed()))
                }
            } finally {
                // OrionStar docs: an Image that is not closed quickly leads to OOM.
                image.close()
            }
        }, Handler(frameThread.looper))

        try {
            val code = SurfaceShareApi.getInstance().requestImageFrame(surface, bean, object : SurfaceShareListener() {
                override fun onError(error: Int, message: String?) {
                    lastError = error
                    outcome.complete(SnapshotResult.Failure(mapError(error, message), elapsed()))
                }

                // STATUS_SET_STREAM_SUCCESS (10) arrives here; the first frame is what actually matters.
                override fun onStatusUpdate(status: Int, message: String?) {}
            })
            if (code == NOT_CONNECTED_CODE) return SnapshotResult.Failure(SnapshotFailure.NotConnected, elapsed())
            if (code < 0) return SnapshotResult.Failure(SnapshotFailure.Sdk(code, "requestImageFrame returned $code"), elapsed())
            streamRequested = true

            return withTimeoutOrNull(timeoutMs) { outcome.await() }
                ?: SnapshotResult.Failure(SnapshotFailure.Timeout, elapsed())
        } finally {
            // Same order as the OrionStar sample: abandon the stream, stop the thread, close the reader.
            // If the share was already held by someone else (-14), it is not ours to abandon.
            if (streamRequested && lastError != SurfaceShareError.ERROR_SURFACE_SHARE_USED) {
                runCatching { SurfaceShareApi.getInstance().abandonImageFrame(bean) }
            }
            reader.setOnImageAvailableListener(null, null)
            frameThread.quitSafely()
            runCatching { frameThread.join(THREAD_JOIN_MS) }
            reader.close()
            surface.release()
        }
    }

    private fun mapError(error: Int, message: String?): SnapshotFailure = when (error) {
        SurfaceShareError.ERROR_SURFACE_SHARE_USED -> SnapshotFailure.CameraBusy
        SurfaceShareError.ERROR_SURFACE_SHARE_PREEMPTED -> SnapshotFailure.Preempted
        else -> SnapshotFailure.Sdk(error, message)
    }

    /**
     * Copies the Y (brightness) plane into a [GrayImage]. The Y plane always has pixelStride 1, but
     * rowStride can be larger than the width (row padding), so rows are copied one by one.
     */
    private fun Image.yPlaneToGray(): GrayImage {
        val plane = planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val out = ByteArray(width * height)
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(out, row * width, width)
        }
        return GrayImage(width, height, out)
    }

    private companion object {
        /** Name shown to RobotOS for this consumer of the shared stream. */
        const val STREAM_NAME = "RoboGuardSnapshot"

        /** Enough to use acquireLatestImage while frames keep arriving; the OrionStar sample uses 4. */
        const val MAX_BUFFERED_IMAGES = 2

        /** requestImageFrame's return value when the service connection is missing (read from the jar). */
        const val NOT_CONNECTED_CODE = -1

        const val THREAD_JOIN_MS = 100L
    }
}
