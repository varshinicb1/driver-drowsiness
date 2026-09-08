package com.drowsy.perception

import com.drowsy.camera.CameraFrame

/**
 * Interface hides concrete model (§5.1, §7). Production impl is MediaPipe Face Landmarker;
 * tests use MockPerceptionEngine.
 *
 * ```kotlin
 * interface DriverPerceptionEngine {
 *     fun processFrame(frame: ImageFrame): PerceptionFrame?
 * }
 * ```
 */
interface DriverPerceptionEngine {
    fun processFrame(frame: CameraFrame): PerceptionFrame?
}

/** Deterministic mock for tests / fallback when model not loaded (§5). */
class MockPerceptionEngine(
    private val earClosedThreshold: Float = 0.20f,
    private val marYawnThreshold: Float = 0.55f,
) : DriverPerceptionEngine {
    private var next: PerceptionFrame? = null
    fun inject(frame: PerceptionFrame) { next = frame }
    override fun processFrame(frame: CameraFrame): PerceptionFrame {
        val injected = next
        if (injected != null) return injected.copy(timestampMs = frame.timestampMs)
        return PerceptionFrame(false, 0f, 0f, 0f, timestampMs = frame.timestampMs)
    }
    companion object {
        fun syntheticEye(ear: Float, thresh: Float = 0.20f) = EyeFeatures(ear, ear, ear, ear < thresh, if (ear < thresh) 1f else 0f)
        fun syntheticMouth(mar: Float, thresh: Float = 0.55f) = MouthFeatures(mar, mar > thresh, mar)
        fun syntheticHead(pitch: Float=0f, yaw: Float=0f, roll: Float=0f) =
            HeadPose(pitch, yaw, roll, kotlin.math.abs(pitch)>25f || kotlin.math.abs(yaw)>30f || kotlin.math.abs(roll)>25f)
    }
}

/**
 * Real geometry engine: caller supplies 468 landmarks (MediaPipe indices).
 * Model inference lives in MediaPipeLandmarkerEngine below; this class is pure geometry.
 */
class LandmarkPerceptionEngine(
    private val earClosedThreshold: Float = 0.20f,
    private val marYawnThreshold: Float = 0.55f,
) {
    // MediaPipe indices
    private val leftIdx = intArrayOf(33,160,158,133,153,144)
    private val rightIdx = intArrayOf(362,385,387,263,373,380)

    fun process(
        landmarks: List<Point2D>,
        timestampMs: Long,
        faceConfidence: Float = 0.95f,
        earTh: Float = earClosedThreshold,
        marTh: Float = marYawnThreshold,
    ): PerceptionFrame {
        val eye = eyeFeatures(landmarks, earTh)
        val mouth = mouthFeatures(landmarks, marTh)
        val hp = getHeadPose(landmarks)
        val gaze = getGaze(landmarks)
        return PerceptionFrame(
            true, faceConfidence, faceConfidence, faceConfidence,
            eye, mouth, hp, gaze, timestampMs,
            leftEye = eye.copy(earMean = eye.earLeft),
            rightEye = eye.copy(earMean = eye.earRight),
        )
    }

    private fun eyeFeatures(landmarks: List<Point2D>, earTh: Float): EyeFeatures {
        if (landmarks.size >= 468) {
            try {
                val left = leftIdx.map { landmarks[it] }
                val right = rightIdx.map { landmarks[it] }
                val earL = eyeAspectRatio(left)
                val earR = eyeAspectRatio(right)
                val earM = (earL + earR) / 2f
                return EyeFeatures(earL, earR, earM, earM < earTh, if (earM < earTh) 1f else 0f)
            } catch (_: Exception) {}
        }
        return EyeFeatures(0f, 0f, 0f, false, 0f)
    }

    private fun mouthFeatures(landmarks: List<Point2D>, marTh: Float): MouthFeatures {
        if (landmarks.size >= 468) {
            try {
                val h = hypot((landmarks[61].x - landmarks[291].x).toDouble(), (landmarks[61].y - landmarks[291].y).toDouble()).toFloat()
                val v = hypot((landmarks[13].x - landmarks[14].x).toDouble(), (landmarks[13].y - landmarks[14].y).toDouble()).toFloat()
                val mar = if (h > 1e-6f) v / h else 0f
                return MouthFeatures(mar, mar > marTh, mar)
            } catch (_: Exception) {}
        }
        return MouthFeatures(0f, false, 0f)
    }

    @Deprecated("Use process() with explicit thresholds")
    fun getEyeFeatures(landmarks: List<Point2D>): EyeFeatures = eyeFeatures(landmarks, earClosedThreshold)
    fun getMouthFeatures(landmarks: List<Point2D>): MouthFeatures = mouthFeatures(landmarks, marYawnThreshold)
    fun getHeadPose(landmarks: List<Point2D>) = estimateHeadPose(landmarks)
    fun getGaze(landmarks: List<Point2D>): GazeFeatures {
        val hp = estimateHeadPose(landmarks)
        val prob = (1f - (kotlin.math.abs(hp.yaw)/60f + kotlin.math.abs(hp.pitch)/60f)).coerceIn(0f,1f)
        return GazeFeatures(hp.yaw, hp.pitch, prob)
    }
    fun process(landmarks: List<Point2D>, timestampMs: Long, faceConfidence: Float = 0.95f): PerceptionFrame =
        process(landmarks, timestampMs, faceConfidence, earClosedThreshold, marYawnThreshold)

    private fun hypot(a: Double, b: Double) = kotlin.math.hypot(a, b)
}
