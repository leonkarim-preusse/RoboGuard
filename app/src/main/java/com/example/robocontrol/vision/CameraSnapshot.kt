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
 * Takes single grayscale frames from the camera through SurfaceShare ([SurfaceShareApi]): each [capture] opens the
 * stream, keeps the Y plane of one frame and closes the stream again. Requires a connected RobotApi. Captures are
 * serialised; call from a background dispatcher.
 * @param width requested frame width
 * @param height requested frame height
 * @param framesToSkip frames discarded before one is kept
 * @param timeoutMs maximum wait for a frame
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

    /** Maps a SurfaceShare error code to a [SnapshotFailure]. */
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
