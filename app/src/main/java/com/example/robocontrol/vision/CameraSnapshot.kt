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
    val elapsedMs: Long

    /**
     * A frame was captured.
     * @property image grayscale frame
     * @property elapsedMs time taken
     */
    data class Success(val image: GrayImage, override val elapsedMs: Long) : SnapshotResult
    /**
     * No frame was captured.
     * @property reason reason
     * @property elapsedMs time taken
     */
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

    /**
     * Any other SDK error or negative return code.
     * @property code error code
     * @property message error text
     */
    data class Sdk(val code: Int, val message: String?) : SnapshotFailure
}

/**
 * Takes single grayscale frames through SurfaceShare. Call from a background dispatcher.
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

    /**
     * Opens the shared stream, returns one grayscale frame, and closes the stream again.
     * @return the frame or the reason it failed
     */
    suspend fun capture(): SnapshotResult = mutex.withLock {
        val t0 = SystemClock.elapsedRealtime()
        /**
         * Milliseconds since the capture started.
         * @return the time
         */
        fun elapsed() = SystemClock.elapsedRealtime() - t0

        val frameThread = HandlerThread("CameraSnapshot").apply { start() }
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, MAX_BUFFERED_IMAGES)
        val surface = reader.surface
        val bean = SurfaceShareBean().apply { name = STREAM_NAME }

        val outcome = CompletableDeferred<SnapshotResult>()
        var lastError = 0
        var streamRequested = false

        var framesSeen = 0
        reader.setOnImageAvailableListener({ r ->
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
                /**
                 * Completes the capture with a failure.
                 * @param error error code
                 * @param message error text
                 */
                override fun onError(error: Int, message: String?) {
                    lastError = error
                    outcome.complete(SnapshotResult.Failure(mapError(error, message), elapsed()))
                }

                /**
                 * Logs status updates.
                 * @param status status code
                 * @param message status text
                 */
                override fun onStatusUpdate(status: Int, message: String?) {}
            })
            if (code == NOT_CONNECTED_CODE) return SnapshotResult.Failure(SnapshotFailure.NotConnected, elapsed())
            if (code < 0) return SnapshotResult.Failure(SnapshotFailure.Sdk(code, "requestImageFrame returned $code"), elapsed())
            streamRequested = true

            return withTimeoutOrNull(timeoutMs) { outcome.await() }
                ?: SnapshotResult.Failure(SnapshotFailure.Timeout, elapsed())
        } finally {
            // Same order as the OrionStar sample; a share held by someone else (-14) is not abandoned.
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

    /**
     * Maps a SurfaceShare error code to a [SnapshotFailure].
     * @param error error code
     * @param message error text
     * @return the failure
     */
    private fun mapError(error: Int, message: String?): SnapshotFailure = when (error) {
        SurfaceShareError.ERROR_SURFACE_SHARE_USED -> SnapshotFailure.CameraBusy
        SurfaceShareError.ERROR_SURFACE_SHARE_PREEMPTED -> SnapshotFailure.Preempted
        else -> SnapshotFailure.Sdk(error, message)
    }

    /**
     * Copies the Y plane into a [GrayImage].
     * @return the image
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
        const val STREAM_NAME = "RoboGuardSnapshot"

        const val MAX_BUFFERED_IMAGES = 2

        const val NOT_CONNECTED_CODE = -1

        const val THREAD_JOIN_MS = 100L
    }
}
