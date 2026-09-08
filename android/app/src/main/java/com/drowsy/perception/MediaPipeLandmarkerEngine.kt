package com.drowsy.perception

import android.content.Context
import android.graphics.Bitmap
import com.drowsy.camera.CameraFrame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

/**
 * MediaPipe Face Landmarker wrapper — the only place MediaPipe types appear (§5.1, §7).
 * Model file: face_landmarker.task (download from MediaPipe, store in assets).
 * 468 landmarks → LandmarkPerceptionEngine geometry → PerceptionFrame.
 *
 * Keeps trackingQuality = faceConfidence * landmarkPresence; poor tracking dampens fatigue (§11).
 */
class MediaPipeLandmarkerEngine(
    private val context: Context,
    private val modelAssetPath: String = "face_landmarker.task",
    private val earClosedThreshold: Float = 0.20f,
    private val marYawnThreshold: Float = 0.55f,
    private val minFaceDetectionConfidence: Float = 0.5f,
) : DriverPerceptionEngine {

    private val geometry = LandmarkPerceptionEngine(earClosedThreshold, marYawnThreshold)
    private var landmarker: FaceLandmarker? = null
    private var initError: Exception? = null
    private var lastVideoTs = -1L
    val isReady: Boolean get() = landmarker != null
    val statusMessage: String
        get() = when {
            landmarker != null -> "MediaPipe Face Landmarker (468 pts)"
            initError != null -> "Model failed: ${initError!!.message}"
            else -> "Face landmarker not loaded"
        }

    @Synchronized
    fun initialize(): Boolean {
        if (landmarker != null) return true
        return try {
            val base = BaseOptions.builder().setModelAssetPath(modelAssetPath).build()
            val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(minFaceDetectionConfidence)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(false)
                .build()
            landmarker = FaceLandmarker.createFromOptions(context, opts)
            true
        } catch (e: Exception) {
            initError = e; false
        }
    }

    override fun processFrame(frame: CameraFrame): PerceptionFrame? {
        val lm = landmarker ?: run {
            if (!initialize()) return fallbackNoFace(frame.timestampMs)
            landmarker
        } ?: return fallbackNoFace(frame.timestampMs)

        return try {
            val src = frame.bitmap
            val argb = if (src.config == Bitmap.Config.ARGB_8888) src
                else src.copy(Bitmap.Config.ARGB_8888, false) ?: return fallbackNoFace(frame.timestampMs)
            val mpImage = BitmapImageBuilder(argb).build()
            var ts = frame.timestampMs
            if (ts <= lastVideoTs) ts = lastVideoTs + 1
            lastVideoTs = ts
            val result = try {
                lm.detectForVideo(mpImage, ts)
            } finally {
                try { mpImage.close() } catch (_: Exception) {}
            }
            // FaceLandmarkerResult has no detections()/categories() — presence is "faceLandmarks() non-empty".
            val landmarks = result.faceLandmarks().firstOrNull()
            if (landmarks == null || landmarks.isEmpty()) {
                return PerceptionFrame(false, 0f, 0f, 0.1f, timestampMs = frame.timestampMs)
            }
            val faceConf = minFaceDetectionConfidence.coerceAtLeast(0.7f)
            val pts = landmarks.map { Point2D(it.x(), it.y()) }
            val pf = geometry.process(pts, frame.timestampMs, faceConf)
            val tq = (faceConf * (pts.size / 468f)).coerceIn(0f, 1f)
            pf.copy(landmarkConfidence = tq, trackingQuality = tq, landmarks = pts)
        } catch (e: Exception) {
            initError = e
            fallbackNoFace(frame.timestampMs)
        }
    }

    private fun fallbackNoFace(ts: Long) = PerceptionFrame(false, 0f, 0f, 0f, timestampMs = ts)

    fun close() { try { landmarker?.close() } catch (_: Exception) {}; landmarker = null }
}
